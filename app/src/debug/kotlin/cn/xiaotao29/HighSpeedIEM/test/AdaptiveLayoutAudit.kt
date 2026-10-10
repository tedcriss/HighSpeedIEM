package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import java.io.File

/** Exercise the production window dispatcher on one phone, including genuine configuration changes. */
internal class AdaptiveLayoutAudit(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val session: MonitorSession,
) {
    private val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "layout-qa")
        .apply { mkdirs() }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        check(condition()) { message }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun settle() {
        await("discovery/configuration busy") { !session.operationsBlockedPublic && session.visible }
        SystemClock.sleep(800)
    }
    private fun capture(name: String) {
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
    private fun bounds(tag: String): Rect = (device.findObject(By.res(tag)) ?: error("missing $tag")).visibleBounds
    private fun tab(index: Int) {
        // Use the actual native rail/bar click targets, not a direct session mutation.
        val label = listOf("输入设置", "输出设置", "任务控制")[index]
        if (device.findObject(By.text(label)) == null) {
            val rail = device.findObject(By.res("landscape-navigation"))
            val scroll = rail?.findObject(By.scrollable(true))
            scroll?.fling(Direction.UP)
            repeat(6) {
                if (device.findObject(By.text(label)) == null) scroll?.scroll(Direction.DOWN, 0.7f)
            }
        }
        await("missing $label") { device.findObject(By.text(label)) != null }
        device.findObject(By.text(label)).click()
        await("tab did not change") { session.tab == index }
        val pageTag = listOf("input-channel-list", "output-channel-list", "task-route-list")[index]
        await("missing $pageTag") { device.findObject(By.res(pageTag)) != null }
        settle()
    }
    private data class CardSnapshot(val contentDescription: String, val visibleBounds: Rect)

    private fun cards(input: Boolean): List<CardSnapshot> {
        repeat(5) {
            val snapshot = device.findObjects(By.descStartsWith("ID ")).mapNotNull { node ->
                try { CardSnapshot(node.contentDescription, node.visibleBounds) }
                catch (_: androidx.test.uiautomator.StaleObjectException) { null }
            }.filter { it.visibleBounds.height() > 0 && it.contentDescription.endsWith(
                if (input) "查看实时频谱" else "播放或停止测试铃声") }
                .sortedWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left }))
            if (snapshot.isNotEmpty()) return snapshot
            SystemClock.sleep(150)
        }
        error("no visible channel cards")
    }

    private fun checkColumns(expected: Int, input: Boolean) {
        val nodes = cards(input)
        check(nodes.size >= expected) { "too few visible cards: ${nodes.size}" }
        val firstTop = nodes.first().visibleBounds.top
        val row = nodes.filter { it.visibleBounds.top == firstTop }
        check(row.size == expected) { "expected $expected columns, got ${row.map { it.visibleBounds }}" }
        val channels = (if (input) session.inputs else session.outputs).values.toList()
        row.forEachIndexed { index, node ->
            check(node.contentDescription.startsWith("ID ${channels[index].device.id}，通道 ${channels[index].index}，")) {
                "card order differs from portrait"
            }
        }
    }

    fun run(): String {
        val density = device.executeShellCommand("wm density")
            .lineSequence().last { it.contains("density:") }.substringAfter(":").trim()
        val sizeOutput = device.executeShellCommand("wm size")
        val sizeOverride = sizeOutput.lineSequence().firstOrNull { it.startsWith("Override size:") }
            ?.substringAfter(":")?.trim()
        val font = device.executeShellCommand("settings get system font_scale").trim()
        val originalRows = session.model.rows.toList()
        val checks = mutableListOf<String>()
        try {
            main { session.stop(null, false); session.consumeNotice(); session.switchTab(0) }
            settle()
            device.setOrientationNatural()
            await("portrait layout missing") { device.findObject(By.res("layout-portrait")) != null }
            settle()
            checkColumns(1, true)
            capture("portrait-input")
            checks.add("portrait single column")

            device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
            device.setOrientationLeft()
            await("landscape rail missing") { device.findObject(By.res("landscape-navigation")) != null }
            settle()
            val rail = bounds("landscape-navigation")
            val landscapeBounds = bounds("layout-landscape")
            val appTitle = device.findObjects(By.text("高速耳返"))
            check(appTitle.size == 1 && appTitle.single().visibleBounds.right <= rail.right)
            val labels = listOf("输入设置", "输出设置", "任务控制").map {
                (device.findObject(By.text(it)) ?: error("rail item missing")).visibleBounds
            }
            check(labels.zipWithNext().all { (a, b) -> a.top < b.top })
            check(kotlin.math.abs((labels.first().top + labels.last().bottom) / 2 - rail.centerY()) < rail.height() / 8)
            checkColumns(2, true)
            capture("landscape-input-2-columns")
            // Open a real card in the second column: popup must stay within the safe window.
            val second = cards(true)[1]
            val anchor = second.visibleBounds
            device.click(anchor.centerX(), anchor.top + 35)
            await("spectrum did not open") { session.selectedInput != null && device.findObject(By.text("关闭")) != null }
            capture("landscape-spectrum-second-column")
            val close = device.findObject(By.text("关闭")) ?: error("tooltip close missing")
            check(close.visibleBounds.left > rail.right && close.visibleBounds.bottom <= landscapeBounds.bottom)
            close.click()
            SystemClock.sleep(800)
            await("preview not released") { session.selectedInput == null && !session.previewOpeningPublic }
            tab(1)
            checkColumns(2, false)
            val capability = device.findObjects(By.descStartsWith("查看 ID ")).filter { it.visibleBounds.height() > 0 }.take(2).last()
            capability.click()
            await("capabilities did not open") { device.findObject(By.text("关闭")) != null }
            capture("landscape-output-capabilities")
            device.findObject(By.text("关闭")).click()
            checks.add("rail brand/centre; 2-column input/output; anchored spectrum/capabilities")

            tab(0)
            device.findObject(By.res("input-channel-list")).scroll(Direction.DOWN, 1f)
            settle()
            val scrollAnchor = cards(true).first().contentDescription
            device.setOrientationNatural()
            await("portrait rotation missing") { device.findObject(By.res("layout-portrait")) != null }
            settle()
            check(cards(true).any { it.contentDescription == scrollAnchor }) { "portrait lost the visible channel anchor" }
            device.setOrientationLeft()
            await("landscape return missing") { device.findObject(By.res("landscape-navigation")) != null }
            settle()
            check(cards(true).any { it.contentDescription == scrollAnchor }) { "landscape lost the visible channel anchor" }
            device.findObject(By.res("input-channel-list")).fling(Direction.UP)
            settle()
            checks.add("scrolled channel anchor survives portrait/landscape round trip")

            val input = session.inputs.values.first { it.key !in session.disabled }.key
            main {
                session.model.clear()
                repeat(8) { session.setRouteSide(session.model.rows.last(), true, input) }
            }
            tab(2)
            val lane = bounds("task-toolbar-lane")
            val viewport = bounds("task-route-list")
            check(lane.right <= viewport.left && lane.top == viewport.top && lane.bottom == viewport.bottom)
            val toolbar = bounds("task-forwarding-toolbar")
            val first = bounds("task-route-0")
            val secondRoute = bounds("task-route-1")
            check(first.top == secondRoute.top && first.right <= secondRoute.left)
            capture("landscape-task-2-columns")
            device.findObject(By.res("task-route-list")).scroll(Direction.DOWN, 1f)
            settle()
            check(bounds("task-forwarding-toolbar") == toolbar)
            device.findObject(By.res("task-route-list")).fling(Direction.DOWN)
            settle()
            check(device.findObject(By.res("task-route-8")) != null) { "last route unreachable" }
            checks.add("task gutter, ordered grid, fixed toolbar, last route reachable")
            val output = session.outputs.values.first { it.key !in session.disabled }.key
            main { session.setRouteSide(session.model.rows.first(), false, output) }
            await("vertical start label stale") { device.findObject(By.desc("启动 1 个转发")) != null }
            device.findObject(By.desc("启动 1 个转发")).click()
            await("vertical button did not start") { session.runningPublic }
            await("vertical stop button missing") { device.findObject(By.desc("停止转发")) != null }
            capture("landscape-task-running")
            device.findObject(By.desc("停止转发")).click()
            await("vertical stop did not release") { !session.runningPublic && !session.operationsBlockedPublic }
            main { session.setRouteSide(session.model.rows.first(), false, null) }
            checks.add("vertical start/stop and native forwarding")

            for ((targetSize, expected) in listOf("1080x1600" to 1, "1080x3200" to 3)) {
                device.executeShellCommand("wm size $targetSize")
                await("resized landscape missing") { device.findObject(By.res("layout-landscape")) != null }
                settle()
                tab(0)
                checkColumns(expected, true)
                capture("landscape-input-$expected-columns")
                tab(1)
                checkColumns(expected, false)
                capture("landscape-output-$expected-columns")
                tab(2)
                device.findObject(By.res("task-route-list")).fling(Direction.UP)
                settle()
                val row = (0..8).mapNotNull { device.findObject(By.res("task-route-$it")) }
                val y = row.minOf { it.visibleBounds.top }
                check(row.count { it.visibleBounds.top == y } == expected)
                check(bounds("task-toolbar-lane").right <= bounds("task-route-list").left)
                capture("landscape-task-$expected-columns")
            }
            checks.add("all 3 pages adapt to 1/2/3 columns")

            device.executeShellCommand("wm size ${sizeOverride ?: "reset"}")
            device.executeShellCommand("wm density $density")
            for (scale in listOf("1.5", "2.0")) {
                device.executeShellCommand("settings put system font_scale $scale")
                settle()
                tab(0)
                checkColumns(1, true)
                tab(2)
                check(bounds("task-toolbar-lane").right <= bounds("task-route-list").left)
                val laneRight = bounds("task-toolbar-lane").right
                val toolbarScroll = device.findObjects(By.scrollable(true))
                    .firstOrNull { it.visibleBounds.right <= laneRight && it.visibleBounds.left >= bounds("landscape-navigation").right }
                toolbarScroll?.scroll(Direction.DOWN, 1f)
                capture("landscape-font-$scale-task")
                val action = device.findObject(By.desc("启动 0 个转发")) ?: error("large font action missing")
                check(action.visibleBounds.height() >= 48)
            }
            checks.add("150%/200% font reduces columns; vertical action remains reachable")

            device.executeShellCommand("settings put system font_scale $font")
            device.executeShellCommand("wm size 600x600")
            await("compact priority missing") { device.findObject(By.res("layout-compact")) != null }
            settle()
            check(device.findObject(By.res("landscape-navigation")) == null)
            check(device.findObject(By.desc("页面指示器")) != null)
            capture("compact-production-window")
            checks.add("600px compact priority")
            return checks.joinToString("; ")
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            device.executeShellCommand("wm size ${sizeOverride ?: "reset"}")
            device.executeShellCommand("wm density $density")
            device.executeShellCommand("settings put system font_scale $font")
            device.setOrientationNatural()
            device.unfreezeRotation()
            settle()
            main {
                session.stop(null, false)
                session.model.clear()
                for (original in originalRows.filterNot { it.blank() }) {
                    val blank = session.model.rows.last()
                    session.setRouteSide(blank, true, original.input)
                    session.setRouteSide(blank, false, original.output)
                }
                session.switchTab(0)
            }
        }
    }
}
