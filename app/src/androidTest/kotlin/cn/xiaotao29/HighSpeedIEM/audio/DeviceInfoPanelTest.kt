package cn.xiaotao29.HighSpeedIEM.audio

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real touch scrolling, window geometry and native outside dismissal. */
@RunWith(AndroidJUnit4::class)
class DeviceInfoPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun inputPortrait() = exercise(input = true, landscape = false)
    @Test fun outputPortrait() = exercise(input = false, landscape = false)
    @Test fun inputLandscape() = exercise(input = true, landscape = true)
    @Test fun outputLandscape() = exercise(input = false, landscape = true)

    private fun exercise(input: Boolean, landscape: Boolean) {
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var deviceId = 0
            scenario.onActivity {
                val session = (it.application as MainApplication).session
                session.stopForwarding(clearSelection = true)
                session.refresh()
                deviceId = (if (input) session.inputDevices else session.outputDevices).values.first { device ->
                    device.type == if (input) AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }.id
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                session.pendingTab = if (input) 0 else 1
            }
            try {
                await { instrumentation.targetContext.resources.configuration.orientation ==
                    if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
                val description = "查看 ID $deviceId 的设备能力"
                await { nodes().any { it.contentDescription?.toString() == description } }
                var button = nodes().first { it.contentDescription?.toString() == description }
                while (!button.isClickable) button = button.parent ?: error("No clickable capability button")
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val title = "设备能力 · ID $deviceId"
                await { nodes().any { it.text?.toString() == title } }
                SystemClock.sleep(400)
                val initialWindow = bounds(instrumentation.uiAutomation.rootInActiveWindow!!)
                val initialTitle = bounds(nodes().first { it.text?.toString() == title })
                val initialViewport = bounds(viewport())
                val initialTypeY = typeY()
                assertFalse(nodes().any { it.contentDescription?.toString()?.startsWith("关闭") == true })
                val box = initialViewport
                // Gesture coordinates come from the accessibility scroll viewport.
                drag(box.centerX(), box.bottom - 8, box.top + 8) {
                    assertGeometry(initialWindow, initialTitle, initialViewport, title)
                }
                SystemClock.sleep(350)
                if (landscape) assertTrue("The compressed table did not scroll", typeY() < initialTypeY)
                assertGeometry(initialWindow, initialTitle, initialViewport, title)
                repeat(3) {
                    assertTrue(viewport().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) || typeY() == initialTypeY)
                    SystemClock.sleep(180)
                    assertGeometry(initialWindow, initialTitle, initialViewport, title)
                }
                await { typeY() == initialTypeY }
                assertTrue(nodes().any { it.contentDescription?.toString()?.startsWith("getProductName，") == true })
                // Select a point outside the measured floating window, inside the app display.
                val display = instrumentation.targetContext.resources.displayMetrics
                val outsideY = if (initialWindow.bottom + 12 < display.heightPixels) initialWindow.bottom + 12
                    else initialWindow.top - 12
                assertTrue("No room outside panel", outsideY > 0)
                tap(initialWindow.centerX(), outsideY)
                await { nodes().none { it.text?.toString() == title } }
                scenario.onActivity {
                    val session = (it.application as MainApplication).session
                    assertEquals(null, session.selectedInputId)
                    assertEquals(null, session.selectedOutputId)
                }
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    private fun assertGeometry(window: Rect, titleBounds: Rect, scroll: Rect, title: String) {
        assertEquals("Scrolling resized/repositioned the window", window, bounds(instrumentation.uiAutomation.rootInActiveWindow!!))
        assertEquals("Scrolling moved the fixed title", titleBounds, bounds(nodes().first { it.text?.toString() == title }))
        assertEquals("Scrolling changed the viewport", scroll, bounds(viewport()))
    }

    // A short portrait table has no scroll range, so native accessibility
    // correctly omits isScrollable. The panel still gives this region focus.
    private fun viewport() = nodes().first { it.isFocused }
    private fun typeY() = bounds(nodes().first { it.contentDescription?.toString()?.startsWith("getType，") == true }).top
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    private fun drag(x: Int, fromY: Int, toY: Int, duringMove: () -> Unit) {
        val downTime = SystemClock.uptimeMillis()
        event(downTime, MotionEvent.ACTION_DOWN, x, fromY)
        try {
            for (step in 1..12) {
                event(downTime, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / 12)
                SystemClock.sleep(35)
                duringMove()
            }
        } finally {
            event(downTime, MotionEvent.ACTION_UP, x, toY)
        }
    }

    private fun tap(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        event(downTime, MotionEvent.ACTION_DOWN, x, y)
        event(downTime, MotionEvent.ACTION_UP, x, y)
    }

    private fun event(downTime: Long, action: Int, x: Int, y: Int) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Device info panel did not update", condition())
    }
}
