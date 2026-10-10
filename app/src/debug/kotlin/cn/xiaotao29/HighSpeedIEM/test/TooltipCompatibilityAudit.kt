package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import java.io.File

/** Actual popup/card bounds, including edge cards, rotation and large text on one phone. */
internal class TooltipCompatibilityAudit(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val session: MonitorSession,
) {
    private val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "tooltip-qa")
        .apply { mkdirs() }
    private val results = mutableListOf<String>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        check(condition()) { message }
    }
    private fun settle() {
        await("session busy") { session.visible && !session.operationsBlockedPublic }
        SystemClock.sleep(700)
    }
    private fun capture(name: String) {
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
    private fun cardDescription(c: Channel) = "ID ${c.device.id}，通道 ${c.index}，${c.device.productName}，" +
        if (c.input) "查看实时频谱" else "播放或停止测试铃声"

    private fun exercise(name: String, columns: Int, zeroOverlap: Boolean) {
        for (tab in 0..1) {
            main { session.switchTab(tab); session.consumeNotice() }
            settle()
            val listTag = if (tab == 0) "input-channel-list" else "output-channel-list"
            await("missing $listTag after tab change") { device.hasObject(By.res(listTag)) }
            val list = device.findObject(By.res(listTag)) ?: error("missing $listTag")
            // Native scroll container is a descendant of the tagged portrait wrapper.
            val scroll = if (list.isScrollable) list else list.findObject(By.scrollable(true))
            repeat(8) { if (scroll?.scroll(Direction.UP, 1f) != true) return@repeat }
            settle()
            val channels = (if (tab == 0) session.inputs else session.outputs).values.take(columns)
            for (channel in channels) {
                val node = device.findObject(By.desc(cardDescription(channel))) ?: error("card missing")
                val card = node.visibleBounds
                val viewport = device.findObject(By.res(listTag)).visibleBounds
                val header = device.findObject(By.desc("允许 ID ${channel.device.id} 通道 ${channel.index} 用于转发"))
                    ?: error("header missing")
                val headerY = header.visibleBounds.centerY()
                if (tab == 0 && channel.key !in session.disabled) {
                    device.click(card.centerX(), headerY)
                    checkPopup("$name-input-${channel.index}-spectrum", "spectrum-tooltip", card, viewport, zeroOverlap)
                    device.pressBack()
                    await("spectrum did not dismiss") { session.selectedInput == null &&
                        !device.hasObject(By.res("spectrum-tooltip")) && !session.operationsBlockedPublic }
                }
                val capability = device.findObject(By.desc("查看 ID ${channel.device.id} 通道 ${channel.index} 的设备能力"))
                    ?: error("capability action missing")
                capability.click()
                checkPopup("$name-$tab-${channel.index}-capabilities", "capability-tooltip", card, viewport, zeroOverlap)
                // Scrolling long details must leave the close action fixed and accessible.
                val close = device.findObject(By.text("关闭")) ?: error("close missing")
                val closeBounds = close.visibleBounds
                device.findObject(By.res("capability-tooltip"))?.findObject(By.scrollable(true))
                    ?.scroll(Direction.DOWN, 1f)
                check(device.findObject(By.text("关闭")).visibleBounds == closeBounds) { "close moved with body" }
                device.findObject(By.text("关闭")).click()
                await("capability did not dismiss") { !device.hasObject(By.res("capability-tooltip")) }
                check(session.selectedInput == null && session.selectedOutput == null) { "nested action selected card" }
            }
        }
    }

    private fun checkPopup(name: String, tag: String, card: Rect, viewport: Rect, noOverlap: Boolean) {
        await("missing $tag") { device.hasObject(By.res(tag)) }
        SystemClock.sleep(600)
        val popup = device.findObject(By.res(tag)).visibleBounds
        check(viewport.contains(popup)) { "$name left content viewport: popup=$popup viewport=$viewport" }
        check(popup.contains(device.findObject(By.text("关闭")).visibleBounds)) { "$name clipped close" }
        val overlap = Rect(card).apply { if (!intersect(popup)) setEmpty() }
        val area = overlap.width().toLong() * overlap.height()
        if (noOverlap) check(area == 0L) { "$name covers card: popup=$popup card=$card" }
        else check(area < card.width().toLong() * card.height() * 0.6) {
            "$name hides most of card: popup=$popup card=$card"
        }
        results.add("$name overlap=$area cardArea=${card.width().toLong() * card.height()} popup=$popup card=$card")
        capture(name)
    }

    fun run(): String {
        val configurator = Configurator.getInstance()
        val idleTimeout = configurator.waitForIdleTimeout
        // The live spectrum continually redraws. Poll real nodes after bounded
        // settling rather than waiting ten seconds for every accessibility read.
        configurator.waitForIdleTimeout = 500
        val size = device.executeShellCommand("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val font = device.executeShellCommand("settings get system font_scale").trim()
        try {
            main { session.stop(null, false); session.switchTab(0); session.consumeNotice() }
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("wm size reset")
            device.setOrientationNatural()
            settle()
            exercise("portrait", 1, true)
            device.setOrientationLeft()
            settle()
            exercise("landscape-2-columns", 2, true)
            device.executeShellCommand("wm size 1080x3200")
            settle()
            exercise("landscape-3-columns", 3, true)
            device.executeShellCommand("wm size 1080x1600")
            settle()
            exercise("landscape-1-column", 1, false)
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("settings put system font_scale 2.0")
            settle()
            exercise("landscape-font-200", 1, false)
            device.setOrientationNatural()
            settle()
            exercise("portrait-font-200", 1, false)
            File(directory, "geometry.txt").writeText(results.joinToString("\n"))
            return "${results.size} real popups; portrait, landscape 1/2/3 columns, 200% font; safe bounds, card exposure, fixed close, no nested selection"
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            if (device.hasObject(By.res("spectrum-tooltip")) || device.hasObject(By.res("capability-tooltip"))) {
                device.pressBack()
            }
            device.executeShellCommand("wm size ${size ?: "reset"}")
            device.executeShellCommand("settings put system font_scale $font")
            device.setOrientationNatural()
            device.unfreezeRotation()
            main { session.stop(null, false); session.switchTab(0); session.consumeNotice() }
            configurator.waitForIdleTimeout = idleTimeout
        }
    }
}
