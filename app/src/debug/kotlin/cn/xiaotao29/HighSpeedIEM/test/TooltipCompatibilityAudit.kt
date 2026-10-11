package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
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

    /** Route-info popup over a real running pair: gated action, avoidance, fixed close. */
    private fun exerciseRouteInfo(name: String, zeroOverlap: Boolean) {
        main { session.switchTab(2); session.consumeNotice() }
        settle()
        val input = session.inputs.values.firstOrNull { it.key !in session.disabled } ?: return
        val output = session.outputs.values.firstOrNull { it.key !in session.disabled } ?: return
        main {
            session.model.clear()
            val row = session.model.rows.last()
            session.setRouteSide(row, true, input.key)
            session.setRouteSide(row, false, output.key)
        }
        settle()
        val infoDesc = "查看转发 1的运行信息"
        await("route info action missing") { device.hasObject(By.desc(infoDesc)) }
        // A merged Compose wrapper may report enabled=true; exercise the touch path.
        device.findObject(By.desc(infoDesc)).click()
        SystemClock.sleep(600)
        check(!device.hasObject(By.res("route-info-tooltip"))) { "route info opened while stopped" }
        main {
            session.prepareForwarding {
                if (session.startingPublic && !session.loadingPublic) {
                    instrumentation.targetContext.startForegroundService(
                        Intent(instrumentation.targetContext, ForwardingService::class.java))
                }
            }
        }
        // Starting opens real streams; give it the same budget as the start scenarios.
        val startDeadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < startDeadline &&
            !(session.runningPublic && session.actualStats.isNotEmpty())) SystemClock.sleep(200)
        check(session.runningPublic && session.actualStats.isNotEmpty()) { "route forwarding did not start" }
        settle()
        val card = device.findObject(By.res("task-route-0"))?.visibleBounds ?: error("route card missing")
        val viewport = device.findObject(By.res("task-route-list")).visibleBounds
        device.findObject(By.desc(infoDesc)).click()
        checkPopup("$name-route-info", "route-info-tooltip", card, viewport, zeroOverlap)
        device.findObject(By.text("关闭")).click()
        await("route info did not dismiss") { !device.hasObject(By.res("route-info-tooltip")) }
        main { session.stop(null, false) }
        await("route forwarding did not stop") { !session.runningPublic && !session.operationsBlockedPublic }
        settle()
    }

    /** Params popup over an idle pair: table controls, avoidance, fixed close. */
    private fun exerciseRouteParams(name: String, zeroOverlap: Boolean) {
        main { session.switchTab(2); session.consumeNotice() }
        settle()
        val input = session.inputs.values.firstOrNull { it.key !in session.disabled } ?: return
        val output = session.outputs.values.firstOrNull { it.key !in session.disabled } ?: return
        main {
            if (session.model.validCount() == 0) {
                val row = session.model.rows.last()
                session.setRouteSide(row, true, input.key)
                session.setRouteSide(row, false, output.key)
            }
        }
        settle()
        val paramsDesc = "设置转发 1的转发参数"
        await("params action missing") { device.hasObject(By.desc(paramsDesc)) }
        val card = device.findObject(By.res("task-route-0"))?.visibleBounds ?: error("route card missing")
        val viewport = device.findObject(By.res("task-route-list")).visibleBounds
        device.findObject(By.desc(paramsDesc)).click()
        checkPopup("$name-route-params", "route-params-tooltip", card, viewport, zeroOverlap)
        val close = device.findObject(By.res("route-params-tooltip"))
            ?.findObject(By.text("关闭")) ?: error("params close missing")
        close.click()
        await("params did not dismiss") { !device.hasObject(By.res("route-params-tooltip")) }
        settle()
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
        val savedRoutes = session.model.rows.map { it.input to it.output }
        try {
            main { session.stop(null, false); session.switchTab(0); session.consumeNotice() }
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("wm size reset")
            device.setOrientationNatural()
            settle()
            exercise("portrait", 1, true)
            exerciseRouteInfo("portrait", true)
            exerciseRouteParams("portrait", true)
            device.setOrientationLeft()
            settle()
            exercise("landscape-2-columns", 2, true)
            exerciseRouteInfo("landscape-2-columns", true)
            exerciseRouteParams("landscape-2-columns", true)
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
            exerciseRouteInfo("portrait-font-200", false)
            exerciseRouteParams("portrait-font-200", false)
            File(directory, "geometry.txt").writeText(results.joinToString("\n"))
            return "${results.size} real popups; portrait, landscape 1/2/3 columns, 200% font; safe bounds, card exposure, fixed close, no nested selection; route info gated/one-shot"
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            if (device.hasObject(By.res("spectrum-tooltip")) || device.hasObject(By.res("capability-tooltip"))
                || device.hasObject(By.res("route-info-tooltip"))) {
                device.pressBack()
            }
            device.executeShellCommand("wm size ${size ?: "reset"}")
            device.executeShellCommand("settings put system font_scale $font")
            device.setOrientationNatural()
            device.unfreezeRotation()
            main { session.stop(null, false) }
            // Route editing stays locked until the stop cleanup finishes.
            val stopDeadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < stopDeadline && session.operationsBlockedPublic) SystemClock.sleep(150)
            main {
                session.model.clear()
                savedRoutes.forEach { (input, output) ->
                    if (input == null && output == null) return@forEach
                    val row = session.model.rows.last()
                    input?.let { session.setRouteSide(row, true, it) }
                    output?.let { session.setRouteSide(row, false, it) }
                }
                session.switchTab(0)
                session.consumeNotice()
            }
            configurator.waitForIdleTimeout = idleTimeout
        }
    }
}
