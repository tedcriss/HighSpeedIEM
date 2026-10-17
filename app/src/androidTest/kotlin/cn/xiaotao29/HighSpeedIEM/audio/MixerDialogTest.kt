package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Immediate routing, retained selection and native dialog dismissal via accessibility. */
@RunWith(AndroidJUnit4::class)
class MixerDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession
    private var inputId = 0

    @Before
    fun prepareMixer() {
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
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
        awaitMain { !session.busy }
        scenario.close()
    }

    @Test
    fun editsApplyImmediatelyAndSurviveOutsideBackAndRotation() {
        openDialog()
        clickRow(1)
        assertMask(3)
        screenshot("portrait")
        // The session remains authoritative even if routing changes elsewhere.
        scenario.onActivity { session.setMixerMask(0, 2) }
        await { row(0)?.isChecked == false && row(1)?.isChecked == true }
        clickRow(0)
        assertMask(3)
        scenario.recreate()
        await { row(1)?.isChecked == true }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        await { row(1)?.isChecked == true }
        screenshot("landscape")
        outside()
        await { row(1) == null }
        assertMask(3)
        openDialog()
        // Keyboard / remote focus and activation must toggle the entire row.
        assertTrue(row(0)!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await { row(0)?.isFocused == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        await { row(1)?.isFocused == true }
        assertEquals(true, row(1)?.isChecked)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { row(1)?.isChecked == false }
        assertMask(1)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        await { row(0)?.isFocused == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        await { row(1)?.isFocused == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { row(1) == null }
        assertMask(1)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        openDialog()
        clickRow(0)
        assertMask(0)
        clickRow(1)
        assertMask(2)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { row(1) == null }
        assertMask(2)
    }

    @Test
    fun longListAppliesLastChannelWithoutConfirmation() {
        scenario.onActivity {
            session.setParams(true, inputId, StreamParams(channelCount = 32))
            session.setMixerMask(0, 1)
        }
        openDialog()
        clickRow(0)
        assertMask(0)
        for (attempt in 0..20) {
            if (row(31) != null) break
            val list = nodes().first { node -> node.isScrollable }
            assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            SystemClock.sleep(100)
        }
        await { row(31) != null }
        clickRow(31)
        assertMask(Int.MIN_VALUE)
        screenshot("last-channel")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { row(31) == null }
        assertMask(Int.MIN_VALUE)
    }

    @Test
    fun changesApplyWhileForwardingWithoutStoppingAudio() {
        scenario.onActivity {
            session.setParams(true, inputId, StreamParams(channelCount = 1,
                sampleRateMode = SampleRateMode.SYNC_OUTPUT, exclusive = false))
            session.setParams(false, session.selectedOutputId!!, StreamParams(channelCount = 2,
                sampleRate = 48000, sampleRateMode = SampleRateMode.EXPLICIT, exclusive = false))
            session.updateRelayParams(RelayParams(inMMapEnabled = false, outMMapEnabled = false))
            session.setMixerMask(0, 1)
            it.startForwarding()
        }
        awaitMain { session.forwarding && !session.busy }
        openDialog()
        assertTrue(row(0)!!.isEnabled)
        clickRow(0)
        assertMask(0)
        val before = nativeFrames()
        SystemClock.sleep(350)
        assertTrue(nativeFrames() > before)
        clickRow(0)
        assertMask(1)
        outside()
        await { row(0) == null }
        awaitMain { session.forwarding && !session.busy }
        assertMask(1)
    }

    private fun openDialog() {
        await { nodes().any { it.contentDescription?.toString()?.startsWith("输出通道 0：混音输入") == true } }
        click(nodes().first { it.contentDescription?.toString()?.startsWith("输出通道 0：混音输入") == true })
        await { row(0) != null }
        assertFalse(nodes().any { it.text?.toString() in listOf("取消", "确定") })
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

    private fun outside() {
        val rect = Rect().also { row(0)!!.getBoundsInScreen(it) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                (rect.left / 2).toFloat(), rect.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "mixer-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    private fun nativeFrames() = NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty().asList()
        .chunked(2).associate { it[0] to it[1] }.getValue("FramesRead").toLong()

    private fun awaitMain(condition: () -> Boolean) = await {
        var value = false
        instrumentation.runOnMainSync { value = condition() }
        value
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
