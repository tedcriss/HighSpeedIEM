package cn.xiaotao29.HighSpeedIEM.audio

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verify draft/commit behavior through the same accessibility actions as users. */
@RunWith(AndroidJUnit4::class)
class MixerDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession
    private var inputId = 0

    @Before
    fun prepareMixer() {
        instrumentation.setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            session = (it.application as MainApplication).session
            session.refresh()
            inputId = session.inputDevices.values.first { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.id
            val outputId = session.outputDevices.values.first { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id
            session.toggleInputSelection(inputId)
            session.toggleOutputSelection(outputId)
            session.setParams(true, inputId, StreamParams(channelCount = 2))
            session.setMixerMask(0, 1)
            session.pendingTab = 2
        }
    }

    @After
    fun close() {
        // Remote navigation leaves the device outside touch mode. Restore it
        // so later tests do not auto-focus and open unrelated device tooltips.
        instrumentation.setInTouchMode(true)
        scenario.onActivity {
            session.stopForwarding(clearSelection = true)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        scenario.close()
    }

    @Test
    fun cancelAndBackDiscardDraftWhileConfirmCommitsAllChannels() {
        openDialog()
        clickRow(1)
        assertMask(1)
        scenario.recreate()
        await { row(1)?.isChecked == true }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        await { row(1)?.isChecked == true }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        await { row(1)?.isChecked == true }
        clickText("取消")
        assertMask(1)
        openDialog()
        await { row(1)?.isChecked == false }
        clickRow(1)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { row(1) == null }
        assertMask(1)
        openDialog()
        // Keyboard / remote focus and activation must toggle the entire row.
        assertTrue(row(0)!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await { row(0)?.isFocused == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        await { row(1)?.isFocused == true }
        assertEquals(false, row(1)?.isChecked)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { row(1)?.isChecked == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        await { row(0)?.isFocused == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        await { row(1)?.isFocused == true }
        clickText("确定")
        await { row(1) == null }
        assertMask(3)
        openDialog()
        clickRow(0)
        clickRow(1)
        clickText("确定")
        assertMask(0)
    }

    @Test
    fun longListKeepsActionsVisibleAndCanSelectLastChannel() {
        scenario.onActivity { session.setParams(true, inputId, StreamParams(channelCount = 32)) }
        openDialog()
        repeat(20) {
            assertTrue(nodes().any { it.text?.toString() == "确定" && it.isVisibleToUser })
            if (row(31) == null) {
                val list = nodes().first { node -> node.isScrollable }
                assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                SystemClock.sleep(100)
            }
        }
        await { row(31) != null }
        clickRow(31)
        clickText("确定")
        assertMask(Int.MIN_VALUE)
    }

    private fun openDialog() {
        await { nodes().any { it.contentDescription?.toString()?.startsWith("输出通道 0：混音输入") == true } }
        click(nodes().first { it.contentDescription?.toString()?.startsWith("输出通道 0：混音输入") == true })
        await { row(0) != null }
    }

    private fun row(channel: Int): AccessibilityNodeInfo? {
        var node = nodes().firstOrNull { it.text?.toString() == "ID $inputId · 通道 $channel" } ?: return null
        while (!node.isCheckable) node = node.parent ?: return null
        return node
    }

    private fun clickRow(channel: Int) {
        val before = row(channel)!!.isChecked
        click(row(channel)!!)
        await { row(channel)?.isChecked == !before }
    }

    private fun clickText(text: String) {
        click(nodes().first { it.text?.toString() == text })
        SystemClock.sleep(200)
    }

    private fun click(start: AccessibilityNodeInfo) {
        var node = start
        while (!node.isClickable) node = node.parent ?: error("No click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun assertMask(expected: Int) = scenario.onActivity { assertEquals(expected, session.mixerMask(0)) }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return nodes
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Mixer dialog state did not update", condition())
    }
}
