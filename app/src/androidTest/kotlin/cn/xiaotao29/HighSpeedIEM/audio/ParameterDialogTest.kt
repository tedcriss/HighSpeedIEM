package cn.xiaotao29.HighSpeedIEM.audio

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

/** Native window bounds, immediate writes, outside/back dismissal and reopening. */
class ParameterDialogTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    @Test fun portrait() = exercise(false)
    @Test fun landscape() = exercise(true)

    private fun exercise(landscape: Boolean) {
        i.setInTouchMode(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: RelaySession
            scenario.onActivity {
                session = (it.application as MainApplication).session
                session.stopForwarding(clearSelection = true)
                session.refresh()
                session.updateRelayParams(RelayParams())
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            try {
                await { i.targetContext.resources.configuration.orientation ==
                    if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
                for (input in listOf(true, false)) {
                    var id = 0
                    var initial = false
                    scenario.onActivity {
                        id = (if (input) session.inputDevices else session.outputDevices).values.first { device ->
                            device.type == if (input) AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }.id
                        initial = session.paramsFor(input, id).exclusive
                        session.pendingTab = if (input) 0 else 1
                    }
                    val title = "${if (input) "输入" else "输出"}设备参数 · ID $id"
                    val description = "设置 ID $id 的开流参数"
                    open(description, title)
                    assertLargeInset(title)
                    screenshot("${if (input) "input" else "output"}-${if (landscape) "landscape" else "portrait"}")
                    scrollTo("Exclusive")
                    click(nodes().first { it.text?.toString() == "Exclusive" })
                    scenario.onActivity { assertEquals(!initial, session.paramsFor(input, id).exclusive) }
                    outside(title)
                    open(description, title)
                    scenario.onActivity { assertEquals(!initial, session.paramsFor(input, id).exclusive) }
                    i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await { !hasTitle(title) }
                    scenario.onActivity {
                        assertEquals(!initial, session.paramsFor(input, id).exclusive)
                        session.setParams(input, id, session.paramsFor(input, id).copy(exclusive = initial))
                        assertNull(session.selectedInputId)
                        assertNull(session.selectedOutputId)
                    }
                }
                scenario.onActivity { session.pendingTab = 2 }
                open("转发参数设置", "转发参数设置")
                assertLargeInset("转发参数设置")
                screenshot("relay-${if (landscape) "landscape" else "portrait"}")
                scrollTo("允许 MMAP（输入）")
                click(nodes().first { it.text?.toString() == "允许 MMAP（输入）" })
                scenario.onActivity { assertFalse(session.relayParams.inMMapEnabled) }
                outside("转发参数设置")
                open("转发参数设置", "转发参数设置")
                scenario.onActivity { assertFalse(session.relayParams.inMMapEnabled) }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await { !hasTitle("转发参数设置") }
                scenario.onActivity { assertFalse(session.relayParams.inMMapEnabled) }
            } finally {
                scenario.onActivity {
                    session.updateRelayParams(RelayParams())
                    session.stopForwarding(clearSelection = true)
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    private fun open(description: String, title: String) {
        await { nodes().any { it.contentDescription?.toString() == description } }
        click(nodes().first { it.contentDescription?.toString() == description })
        await { hasTitle(title) }
    }

    private fun assertLargeInset(title: String) {
        val bounds = panelBounds(title)
        val screen = i.uiAutomation.takeScreenshot()!!
        try {
            println("PARAMETER_BOUNDS $title $bounds display=${screen.width}x${screen.height}")
            assertTrue("Dialog must leave outside area on all edges", bounds.left > 0 && bounds.top > 0 &&
                bounds.right < screen.width && bounds.bottom < screen.height)
            assertTrue("Dialog should use most of the display", bounds.width() > screen.width * .8 && bounds.height() > screen.height * .8)
        } finally { screen.recycle() }
        assertFalse(nodes().any { it.contentDescription?.toString()?.startsWith("关闭") == true })
    }

    private fun panelBounds(title: String) = Rect().also { rect ->
        nodes().first { it.paneTitle?.toString() == title }.getBoundsInScreen(rect)
    }

    private fun outside(title: String) {
        val panel = panelBounds(title)
        val x = panel.left / 2
        val y = panel.centerY()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(i.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        await { !hasTitle(title) }
    }

    private fun screenshot(name: String) {
        val bitmap = i.uiAutomation.takeScreenshot()!!
        try { File(i.targetContext.getExternalFilesDir(null), "parameter-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    private fun hasTitle(title: String) = nodes().any { it.text?.toString() == title }
    private fun scrollTo(text: String) {
        repeat(20) {
            if (nodes().any { it.text?.toString() == text }) return
            val list = nodes().firstOrNull { it.isScrollable } ?: error("No scroll region for $text")
            assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            SystemClock.sleep(150)
        }
        error("Missing $text")
    }

    private fun click(start: AccessibilityNodeInfo) {
        var node = start
        while (!node.isClickable) node = node.parent ?: error("No click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val updated = condition()
        if (!updated) {
            println(nodes().joinToString("\n") { "text=${it.text}, description=${it.contentDescription}, pane=${it.paneTitle}" })
            screenshot("timeout")
        }
        assertTrue("Parameter dialog did not update", updated)
    }
}
