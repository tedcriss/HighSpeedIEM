package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Live telemetry must refresh inside a stable native dialog without owning the relay. */
class RealtimeDialogTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val title = "实时流参数"

    @Test fun portrait() = exercise(false)
    @Test fun landscape() = exercise(true)

    private fun exercise(landscape: Boolean) {
        i.setInTouchMode(true)
        i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: RelaySession
            scenario.onActivity {
                session = (it.application as MainApplication).session
                session.stopForwarding(clearSelection = true)
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            try {
                await { i.targetContext.resources.configuration.orientation ==
                    if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
                scenario.onActivity { activity ->
                    session.refresh()
                    val input = session.inputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.id
                    val output = session.outputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id
                    session.toggleInputSelection(input)
                    session.toggleOutputSelection(output)
                    session.setParams(true, input, StreamParams(channelCount = 1, sampleRateMode = SampleRateMode.SYNC_OUTPUT, exclusive = false))
                    session.setParams(false, output, StreamParams(channelCount = 2, sampleRate = 48000, sampleRateMode = SampleRateMode.EXPLICIT, exclusive = false))
                    session.updateRelayParams(RelayParams(inMMapEnabled = false, outMMapEnabled = false))
                    session.setMixerMask(0, 1)
                    session.setMixerMask(1, 1)
                }
                await { nodes().any { it.text?.toString() == "转发控制" } }
                click(nodes().first { it.text?.toString() == "转发控制" })
                await { nodes().any { it.contentDescription?.toString() == "启动转发" } }
                scenario.onActivity { it.startForwarding() }
                awaitMain { session.forwarding && !session.busy }
                open()
                await { nodes().any { it.contentDescription?.toString() == "ChannelCount，1" } }
                val panel = panelBounds()
                val bitmap = i.uiAutomation.takeScreenshot()!!
                try {
                    assertTrue(panel.left > 0 && panel.top > 0 && panel.right < bitmap.width && panel.bottom < bitmap.height)
                    assertTrue(panel.width() > bitmap.width * .8 && panel.height() > bitmap.height * .8)
                } finally { bitmap.recycle() }
                assertFalse(nodes().any { it.contentDescription?.toString()?.startsWith("关闭") == true })
                screenshot("top", landscape)
                await { nodes().any { it.isFocused && it.isScrollable } }
                fun channelY() = bounds(nodes().first { it.contentDescription?.toString() == "ChannelCount，1" }).top
                val initialY = channelY()
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await { channelY() < initialY }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await { channelY() == initialY }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
                await { nodes().none { it.contentDescription?.toString() == "ChannelCount，1" } || channelY() < initialY }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_UP)
                await { nodes().any { it.contentDescription?.toString() == "ChannelCount，1" } && channelY() == initialY }
                scrollUntil { nodes().any { it.contentDescription?.toString()?.startsWith("FramesRead，") == true } }
                val first = displayedFrames()
                await { displayedFrames() > first }
                println("REALTIME_UI_REFRESH first=$first next=${displayedFrames()} panel=$panel")
                assertEquals(panel, panelBounds())
                val viewport = bounds(nodes().first { it.isScrollable })
                gesture(viewport.centerX(), viewport.bottom - 15, viewport.centerX(), viewport.top + 15)
                assertEquals(panel, panelBounds())
                assertEquals(viewport, bounds(nodes().first { it.isScrollable }))
                scrollUntil { nodes().any { it.text?.toString() == "输出流" } }
                screenshot("output", landscape)
                assertEquals(panel, panelBounds())
                // Outside dismissal consumes the gesture and leaves actual AAudio running.
                tap(panel.left / 2, panel.centerY())
                await { !hasTitle() }
                assertAdvancing(session)
                open()
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await { !hasTitle() }
                assertAdvancing(session)
                open()
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
                await { !hasTitle() }
                assertAdvancing(session)
                // A stopped relay removes the dialog and cancels its composition polling.
                open()
                scenario.onActivity { session.stopForwarding() }
                await { !hasTitle() }
                awaitMain { !session.forwarding && !session.busy }
            } finally {
                scenario.onActivity {
                    session.stopForwarding(clearSelection = true)
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                awaitMain { !session.busy }
                scenario.onActivity { session.updateRelayParams(RelayParams()) }
            }
        }
    }

    private fun assertAdvancing(session: RelaySession) {
        var running = false
        i.runOnMainSync { running = session.forwarding && !session.busy }
        assertTrue("Dismissing telemetry stopped the relay", running)
        val before = nativeFrames()
        SystemClock.sleep(400)
        assertTrue("Audio stopped advancing after dismissal", nativeFrames() > before)
    }
    private fun nativeFrames() = NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty().asList()
        .chunked(2).associate { it[0] to it[1] }.getValue("FramesRead").toLong()
    private fun displayedFrames() = nodes().first { it.contentDescription?.toString()?.startsWith("FramesRead，") == true }
        .contentDescription.toString().substringAfter('，').toLong()
    private fun open() {
        await { nodes().any { it.contentDescription?.toString() == "查看实时流参数" } }
        click(nodes().first { it.contentDescription?.toString() == "查看实时流参数" })
        await { hasTitle() }
    }
    private fun hasTitle() = nodes().any { it.text?.toString() == title }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun panelBounds() = bounds(nodes().first { it.paneTitle?.toString() == title })
    private fun click(start: AccessibilityNodeInfo) {
        var node = start
        while (!node.isClickable) node = node.parent ?: error("No click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scrollUntil(found: () -> Boolean) {
        repeat(35) {
            if (found()) return
            val scroll = nodes().first { it.isScrollable }
            assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            SystemClock.sleep(150)
        }
        error("Telemetry row not found")
    }
    private fun screenshot(stage: String, landscape: Boolean) {
        val dark = i.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val bitmap = i.uiAutomation.takeScreenshot()!!
        try { File(i.targetContext.getExternalFilesDir(null), "realtime-${if (landscape) "landscape" else "portrait"}-${if (dark) "dark" else "light"}-$stage.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun event(time: Long, action: Int, x: Int, y: Int) {
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(i.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun tap(x: Int, y: Int) {
        val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, x, y)
        event(time, MotionEvent.ACTION_UP, x, y)
    }
    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int) {
        val time = SystemClock.uptimeMillis()
        event(time, MotionEvent.ACTION_DOWN, x1, y1)
        try {
            for (step in 1..12) {
                event(time, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * step / 12, y1 + (y2 - y1) * step / 12)
                SystemClock.sleep(25)
            }
        } finally { event(time, MotionEvent.ACTION_UP, x2, y2) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result.add(node)
            for (j in 0 until node.childCount) node.getChild(j)?.let(::visit)
        }
        i.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun awaitMain(condition: () -> Boolean) = await {
        var value = false
        i.runOnMainSync { value = condition() }
        value
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val updated = condition()
        if (!updated) println(nodes().joinToString("\n") { "text=${it.text}, description=${it.contentDescription}, pane=${it.paneTitle}" })
        assertTrue("Realtime dialog state did not update", updated)
    }
}
