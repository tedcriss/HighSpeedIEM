package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Production dropdowns, start rejection and native session validation on phone IO. */
class PhaseParamsTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession

    @Before fun launch() {
        i.setInTouchMode(true)
        i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            session = (it.application as MainApplication).session
            session.stopForwarding(clearSelection = true)
            session.refresh()
            session.updateRelayParams(RelayParams())
            session.toggleInputSelection(session.inputDevices.values.first { d -> d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.id)
            session.toggleOutputSelection(session.outputDevices.values.first { d -> d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id)
            session.pendingTab = 2
        }
    }

    @After fun close() {
        NativeRelay.nativeStop()
        i.setInTouchMode(true)
        scenario.onActivity {
            session.updateRelayParams(RelayParams())
            session.stopForwarding(clearSelection = true)
            while (session.pendingError != null) session.consumeError(session.pendingError!!.id)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        scenario.close()
    }

    @Test fun portraitDropdownsAndStartRejection() = exerciseDialog(false)
    @Test fun landscapeDropdownsAndStartRejection() = exerciseDialog(true)

    @Test fun automaticBacklogDefaultCanBeDisabledAndSurvivesRotation() {
        await { nodes().any { it.contentDescription?.toString() == "转发参数设置" } }
        click(nodes().first { it.contentDescription?.toString() == "转发参数设置" })
        scrollTo("输入高积压阈值（帧）")
        assertTrue(nodes().any { it.text?.toString() == "自动（持续积压恢复）" })
        selectLabel("输入高积压阈值（帧）", "关闭（0）")
        scenario.onActivity { assertEquals(0, session.relayParams.inputBacklogThresholdFrames) }
        selectLabel("输入高积压阈值（帧）", "自动（持续积压恢复）")
        scenario.onActivity {
            assertEquals(-1, session.relayParams.inputBacklogThresholdFrames)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        await { i.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        scenario.recreate()
        scenario.onActivity { assertEquals(-1, session.relayParams.inputBacklogThresholdFrames) }
        // The existing portrait/landscape navigation can rebuild ForwardPage
        // and close its local dialog. Reopen it to verify the retained value.
        await { nodes().any { it.contentDescription?.toString() == "转发参数设置" || it.text?.toString() == "转发参数设置" } }
        if (nodes().none { it.text?.toString() == "转发参数设置" }) {
            click(nodes().first { it.contentDescription?.toString() == "转发参数设置" })
        }
        scrollTo("输入高积压阈值（帧）")
        assertTrue(nodes().any { it.text?.toString() == "自动（持续积压恢复）" })
        scenario.onActivity { assertEquals(-1, session.relayParams.inputBacklogThresholdFrames) }
    }

    private fun exerciseDialog(landscape: Boolean) {
        scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        await { i.targetContext.resources.configuration.orientation ==
            if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        await { nodes().any { it.contentDescription?.toString() == "转发参数设置" } }
        click(nodes().first { it.contentDescription?.toString() == "转发参数设置" })
        select("DRAIN 稳定次数", 2)
        select("DRAIN 结束次数", 1)
        select("DISCARD 稳定次数", 3)
        select("DISCARD 结束次数", 20)
        val screenshot = i.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        File(i.targetContext.getExternalFilesDir(null), "phase-${if (landscape) "landscape" else "portrait"}.png")
            .outputStream().use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot!!.recycle()
        scenario.onActivity {
            assertEquals(2, session.relayParams.drainStableThreshold)
            assertEquals(1, session.relayParams.drainMaxExecutions)
            assertEquals(3, session.relayParams.discardStableThreshold)
            assertEquals(20, session.relayParams.discardMaxExecutions)
            assertTrue(session.prepareStartError()!!.contains("DRAIN"))
        }
        select("DRAIN 结束次数", 10)
        select("DISCARD 结束次数", 1)
        scenario.onActivity {
            assertTrue(session.prepareStartError()!!.contains("DISCARD"))
        }
        // Recreate the Activity while the dialog is open: session selections survive.
        scenario.recreate()
        scenario.onActivity { assertEquals(1, session.relayParams.discardMaxExecutions) }
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { nodes().none { it.text?.toString() == "转发参数设置" } }
        // The same preflight used by the service must reject and show its original error.
        scenario.onActivity {
            session.startEngine(object : RelaySession.Owner {
                override fun relayStarted() = fail("Invalid counts started forwarding")
                override fun relayClosed() = Unit
            })
            assertFalse(session.busy || session.forwarding)
            assertTrue(session.pendingError!!.text.contains("DISCARD"))
        }
        await { nodes().any { it.text?.toString()?.contains("DISCARD 强制退出次数（1）不能小于稳定阈值（3）") == true } }
    }

    @Test fun nativeRejectsInvalidLimitsBeforeStartingAndAcceptsCustomLimits() {
        val config = relayConfig(0, StreamParams(channelCount=1, sampleRate=48000, sampleRateMode=SampleRateMode.EXPLICIT,
            format=AAudio.FORMAT_PCM_I16,exclusive=false),
            0, StreamParams(channelCount=2, sampleRate=48000, sampleRateMode=SampleRateMode.EXPLICIT,
                format=AAudio.FORMAT_PCM_I16,exclusive=false), RelayParams())
        for ((p, name) in listOf(
            RelayParams(drainStableThreshold=5,drainMaxExecutions=4) to "DRAIN",
            RelayParams(discardStableThreshold=5,discardMaxExecutions=4) to "DISCARD",
            RelayParams(discardMaxExecutions=150) to "DISCARD",
        )) {
            assertNull(NativeRelay.nativePrepare(config, intArrayOf(1,1)))
            val error = NativeRelay.nativeStartPrepared(p.startThresholds())
            assertTrue(error.orEmpty(), error?.contains(name) == true)
            val info = NativeRelay.nativeStreamInfo(1).orEmpty().asList().chunked(2).associate { it[0] to it[1] }
            assertEquals("AAUDIO_STREAM_STATE_OPEN", info["State"])
            assertEquals("0", info["FramesRead"])
            assertNull(NativeRelay.nativeStop())
        }
        assertNull(NativeRelay.nativePrepare(config,intArrayOf(1,1)))
        assertNull(NativeRelay.nativeStartPrepared(RelayParams(drainStableThreshold=2,drainMaxExecutions=10,
            discardStableThreshold=3,discardMaxExecutions=20).startThresholds()))
        SystemClock.sleep(700)
        val info = NativeRelay.nativeStreamInfo(1).orEmpty().asList().chunked(2).associate { it[0] to it[1] }
        assertTrue(info.getValue("FramesRead").toLong() > 0)
    }

    private fun select(label: String, value: Int) = selectLabel(label, value.toString())

    private fun selectLabel(label: String, value: String) {
        scrollTo(label)
        // A visible label can belong to a field clipped by the scroll viewport.
        // Ask Compose to bring the whole anchor into view before opening its popup.
        var anchor = nodes().first { it.text?.toString()?.contains(label) == true }
        while (!anchor.isClickable) anchor = anchor.parent ?: error("No dropdown anchor")
        anchor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(300)
        click(nodes().first { it.text?.toString()?.contains(label) == true })
        scrollTo(value, exact = true)
        click(nodes().first { it.text?.toString() == value })
        await { nodes().none { it.className?.toString() == "android.widget.PopupWindow" } }
        SystemClock.sleep(150)
    }

    private fun scrollTo(text: String, exact: Boolean = false) {
        fun found() = nodes().any { if (exact) it.text?.toString() == text else it.text?.toString()?.contains(text) == true }
        val directions = if (exact) listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        for (direction in directions) {
            // A landscape popup may show only one or two of the 47 choices.
            for (attempt in 0..80) {
                if (found()) return
                val list = nodes().firstOrNull { it.isScrollable } ?: break
                if (!list.performAction(direction)) break
                SystemClock.sleep(150)
            }
        }
        assertTrue("Missing $text; visible: ${nodes().mapNotNull { it.text }}", found())
    }

    private fun click(start: AccessibilityNodeInfo) {
        var node = start
        while (!node.isClickable) node = node.parent ?: error("No click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser) result.add(n)
            for (j in 0 until n.childCount) n.getChild(j)?.let(::visit)
        }
        i.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Phase parameter UI did not update", condition())
    }
}
