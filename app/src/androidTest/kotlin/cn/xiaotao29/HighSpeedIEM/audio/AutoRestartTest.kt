package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.app.ActivityManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Real native error callback -> front-end close/delay -> ordinary FGS-owned start. */
class AutoRestartTest {
    private external fun nativeInjectError(error: Int)
    private val i = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession
    private fun main(block: () -> Unit) = i.runOnMainSync(block)
    private fun state(check: () -> Boolean): Boolean {
        var result = false
        main { result = check() }
        return result
    }
    private fun await(message: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(10)
        }
        fail(message + "\n" + nodes().joinToString("\n") { "${it.text} / ${it.contentDescription}" })
    }
    private fun idle() = state { !session.busy && !session.forwarding && !session.restartWaiting && session.owner == null }
    private fun info(d: Int) = NativeRelay.nativeStreamInfo(d).orEmpty().asList().chunked(2).associate { it[0] to it[1] }
    @Suppress("DEPRECATION")
    private fun foregroundService() = i.targetContext.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.foreground && it.service.className == ForwardingService::class.java.name }

    @Before fun open() {
        i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { session = (it.application as MainApplication).session; session.stopForwarding(clearSelection = true) }
        await("initial stop", ::idle)
        main { session.refresh(); session.updateRelayParams(RelayParams()); session.pendingTab = 2 }
    }
    @After fun close() {
        main { session.stopForwarding(clearSelection = true) }
        await("final stop", ::idle)
        main {
            session.updateRelayParams(RelayParams())
            while (session.pendingError != null) session.consumeError(session.pendingError!!.id)
            session.refresh()
        }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        scenario.close()
    }
    private fun select(auto: Boolean, invalid: Boolean = false) {
        main {
            val input = session.inputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val output = session.outputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            session.updateRelayParams(RelayParams(autoRestart = auto))
            session.toggleInputSelection(input.id); session.toggleOutputSelection(output.id)
            session.setParams(true, input.id, StreamParams(channelCount = 1,
                sampleRateMode = SampleRateMode.EXPLICIT, sampleRate = 48000, format = if (invalid) -12345 else AAudio.FORMAT_PCM_FLOAT))
            session.setParams(false, output.id, StreamParams(channelCount = 2,
                sampleRateMode = SampleRateMode.EXPLICIT, sampleRate = 48000, format = if (invalid) -12345 else AAudio.FORMAT_PCM_FLOAT))
            session.setMixerMask(0, 1); session.setMixerMask(1, 1)
        }
        scenario.onActivity { it.startForwarding() }
        if (!invalid) await("initial start") { state { session.forwarding && !session.busy } }
    }

    @Test fun disabledByDefaultReleasesAndClears() {
        assertFalse(session.relayParams.autoRestart)
        select(false)
        nativeInjectError(-899)
        await("default error stop", ::idle)
        main { assertNull(session.selectedInputId); assertNull(session.selectedOutputId); assertTrue(session.mixer.isEmpty()) }
        assertTrue(info(0).isEmpty()); assertTrue(info(1).isEmpty())
    }

    @Test fun allErrorCodesRetryAfterCleanupAndKeepConfiguration() {
        select(true)
        var input = 0; var output = 0
        lateinit var params: StreamParams
        main { input = session.selectedInputId!!; output = session.selectedOutputId!!; params = session.paramsFor(true, input) }
        for (code in listOf(-899, -896, -7777)) {
            val previous = NativeRelay.nativeSessionId()
            val owner = session.owner
            nativeInjectError(code)
            await("waiting for code $code") { state { session.restartWaiting } }
            val closedAt = SystemClock.elapsedRealtime()
            assertTrue(info(0).isEmpty()); assertTrue(info(1).isEmpty())
            main {
                assertFalse(session.busy); assertFalse(session.forwarding)
                assertEquals(input, session.selectedInputId); assertEquals(output, session.selectedOutputId)
                assertEquals(params, session.paramsFor(true, input)); assertEquals(1, session.mixerMask(0))
                session.toggleInputSelection(input); session.setMixerMask(0, 0)
                session.updateRelayParams(RelayParams())
                assertEquals(input, session.selectedInputId); assertEquals(1, session.mixerMask(0)); assertTrue(session.relayParams.autoRestart)
            }
            assertTrue(foregroundService())
            SystemClock.sleep(350)
            assertEquals("retry started before 500ms", previous, NativeRelay.nativeSessionId())
            await("restart for code $code") { state { session.forwarding && session.activeSessionId > previous } }
            assertTrue(SystemClock.elapsedRealtime() - closedAt >= 450)
            assertSame(owner, session.owner)
            assertTrue(foregroundService())
            val frames = info(1).getValue("FramesRead").toLong()
            await("restarted input progresses") { info(1)["FramesRead"]?.toLong()?.let { it > frames } == true }
            // Old generation callbacks cannot stop the replacement stream.
            session.onNativeError(previous, "stale callback")
            SystemClock.sleep(50)
            assertTrue(state { session.forwarding })
        }
    }

    @Test fun repeatedOpenFailuresHaveNoRetryLimitAndAbortClears() {
        select(true, invalid = true)
        val initial = NativeRelay.nativeSessionId()
        await("repeated backend prepare failures") { NativeRelay.nativeSessionId() >= initial + 3 }
        await("waiting to cancel") { state { session.restartWaiting } }
        main { session.abortRestart() }
        await("abort closes FGS", ::idle)
        val stopped = NativeRelay.nativeSessionId()
        SystemClock.sleep(800)
        assertEquals(stopped, NativeRelay.nativeSessionId())
        main { assertFalse(session.relayParams.autoRestart); assertNull(session.selectedInputId); assertNull(session.selectedOutputId); assertTrue(session.mixer.isEmpty()) }
        assertTrue(info(0).isEmpty()); assertTrue(info(1).isEmpty())
    }

    @Test fun selectedDeviceDisappearingDuringWaitCancelsWithoutUnchecking() {
        select(true)
        nativeInjectError(-899)
        await("waiting") { state { session.restartWaiting } }
        main {
            val device = session.inputDevices.getValue(session.selectedInputId!!)
            val field = RelaySession::class.java.getDeclaredField("deviceCallback").apply { isAccessible = true }
            (field.get(session) as AudioDeviceCallback).onAudioDevicesRemoved(arrayOf(device))
            assertFalse(session.inputDevices.containsKey(device.id))
        }
        await("missing device stops retry", ::idle)
        val stopped = NativeRelay.nativeSessionId()
        SystemClock.sleep(800)
        assertEquals(stopped, NativeRelay.nativeSessionId())
        main { assertTrue(session.relayParams.autoRestart); assertNull(session.selectedInputId); assertTrue(session.mixer.isEmpty()) }
    }

    @Test fun rejectedConfigurationDoesNotSuppressNextSuccessfulSessionsError() {
        select(true)
        main { session.stopForwarding(clearSelection = false) }
        await("ordinary user stop", ::idle)
        main {
            val id = session.selectedInputId!!
            session.setParams(true, id, session.paramsFor(true, id).copy(channelCount = 1000))
        }
        val nativeId = NativeRelay.nativeSessionId()
        scenario.onActivity { it.startForwarding() }
        await("rejected prepare retries without consuming a native ID") {
            state { session.restartNotice?.id?.let { it >= 3 } == true && session.restartWaiting }
        }
        assertEquals(nativeId, NativeRelay.nativeSessionId())
        main { session.abortRestart() }
        await("abort invalid config", ::idle)
        select(true)
        nativeInjectError(-899)
        await("callback after rejected prepare is handled") { state { session.restartWaiting } }
    }

    @Test fun userStopCancelsFutureRetriesAndIgnoresLateErrors() {
        select(true)
        val id = session.activeSessionId
        main { session.stopForwarding(clearSelection = false) }
        session.onNativeError(id, "late callback during user stop")
        await("user stop", ::idle)
        SystemClock.sleep(800)
        assertEquals(id, NativeRelay.nativeSessionId())
        main { assertTrue(session.relayParams.autoRestart); assertNotNull(session.selectedInputId); assertEquals(1, session.mixerMask(0)) }
    }

    @Test fun retryWorksWhileActivityIsInBackground() {
        select(true)
        scenario.moveToState(Lifecycle.State.CREATED)
        val old = NativeRelay.nativeSessionId()
        nativeInjectError(-899)
        await("background restart") { state { session.forwarding && session.activeSessionId > old } }
        assertTrue(foregroundService())
        val frames = info(1).getValue("FramesRead").toLong()
        await("background input progresses") { info(1)["FramesRead"]?.toLong()?.let { it > frames } == true }
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun portraitControls() = controls(false)
    @Test fun landscapeControls() = controls(true)
    private fun controls(landscape: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("orientation") { i.targetContext.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        main { session.pendingTab = 2 }
        click("转发参数设置", description = true)
        click("自动重启")
        main { assertTrue(session.relayParams.autoRestart) }
        SystemClock.sleep(350) // Capture the settled native dialog, after its enter animation.
        screenshot("checkbox-${if (landscape) "landscape" else "portrait"}")
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        select(true)
        nativeInjectError(-899)
        await("automatic restart Snackbar") { nodes().any { it.text?.startsWith("自动重启中") == true } }
        screenshot("restart-${if (landscape) "landscape" else "portrait"}")
        // A prepare error keeps exercising the real 500ms windows until the UI action lands.
        main { session.stopForwarding(clearSelection = true) }
        await("stop before persistent failure", ::idle)
        select(true, invalid = true)
        click("中止尝试", description = true)
        await("UI abort", ::idle)
        main { assertFalse(session.relayParams.autoRestart); assertNull(session.selectedOutputId) }
        click("转发参数设置", description = true)
        await("checkbox is unchecked after abort") {
            var checkbox = nodes().firstOrNull { it.text?.toString() == "自动重启" } ?: return@await false
            while (!checkbox.isCheckable) checkbox = checkbox.parent ?: return@await false
            !checkbox.isChecked
        }
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result.add(n); for (j in 0 until n.childCount) n.getChild(j)?.let(::visit) }
        i.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun click(label: String, description: Boolean = false) {
        await("click $label") {
            val node = nodes().firstOrNull { it.isVisibleToUser && (if (description) it.contentDescription?.toString() else it.text?.toString()) == label } ?: return@await false
            var button: AccessibilityNodeInfo? = node
            while (button != null && !button.isClickable) button = button.parent
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun screenshot(name: String) {
        val dir = i.targetContext.getExternalFilesDir("auto-restart-qa")!!
        File(dir, "$name.png").outputStream().use { i.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
