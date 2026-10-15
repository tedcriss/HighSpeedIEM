package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native accessibility checks also exercise the user-visible Snackbar. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class ErrorReportingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession

    @Before
    fun openActivity() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { session = (it.application as MainApplication).session }
    }

    @After
    fun closeStreams() {
        if (::session.isInitialized) {
            onMain { session.stopForwarding(clearSelection = true) }
            await("Relay did not stop") { !session.busy && !session.forwarding }
        }
        if (::scenario.isInitialized) scenario.close()
        if (::session.isInitialized) onMain {
            while (session.pendingError != null) session.consumeError(session.pendingError!!.id)
        }
    }

    @Test
    fun rawErrorRemainsVisible() {
        val raw = "AAUDIO_ERROR_DISCONNECTED\n原始错误：USB audio device disconnected"
        onMain { session.showError(raw) }
        awaitMessage(raw)
        SystemClock.sleep(500)
        assertTrue("Snackbar disappeared prematurely", messageVisible(raw))
    }

    @Test
    fun removingActiveInputClosesStreamsAndShowsError() {
        val input = startRelay()
        await("Relay did not start") { session.forwarding }
        onMain { removeDevice(input) }
        await("Removed input left the relay running") { !session.busy && !session.forwarding }
        assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty().isEmpty())
        awaitMessage("转发已停止：音频设备 ID ${input.id} 已被移除")
    }

    @Test
    fun removingActiveOutputClosesStreamsAndShowsError() {
        startRelay()
        await("Relay did not start") { session.forwarding }
        var removedId = 0
        onMain {
            removedId = session.selectedOutputId!!
            removeDevice(session.outputDevices.getValue(removedId))
        }
        await("Removed output left the relay running") { !session.busy && !session.forwarding }
        assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_OUTPUT).orEmpty().isEmpty())
        awaitMessage("转发已停止：音频设备 ID $removedId 已被移除")
    }

    @Test
    fun removingUnselectedDevicePreservesMixerAndRelay() {
        startRelay()
        await("Relay did not start") { session.forwarding }
        onMain {
            val other = session.inputDevices.values.first { it.id != session.selectedInputId }
            removeDevice(other)
            assertEquals(1, session.mixerMask(0))
            assertTrue(session.forwarding)
            assertNull(session.pendingError)
        }
    }

    @Test
    fun removalDuringStartStillClosesNewStreams() {
        val input = startRelay { removeDevice(it) }
        await("Removal during startup was ignored") { !session.busy && !session.forwarding }
        assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty().isEmpty())
        assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_OUTPUT).orEmpty().isEmpty())
        awaitMessage("转发已停止：音频设备 ID ${input.id} 已被移除")
    }

    @Test
    fun nativeErrorDuringStartIsNotLost() {
        val raw = "AAudio 错误回调（输入流）: AAUDIO_ERROR_DISCONNECTED"
        startRelay { session.onNativeError(raw) }
        awaitMessage(raw)
        await("Native error during startup was ignored") { !session.busy && !session.forwarding }
        assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty().isEmpty())
    }

    @Test
    fun repeatedErrorsQueueWithoutCancellingCurrentSnackbar() {
        val raw = "AAUDIO_ERROR_DISCONNECTED\nUSB input removed"
        var firstId = 0L
        onMain {
            session.showError(raw)
            firstId = session.pendingError!!.id
            session.showError(raw)
            session.showError("AAUDIO_ERROR_INVALID_STATE")
        }
        awaitMessage(raw)
        SystemClock.sleep(400)
        assertEquals(firstId, session.pendingError?.id)
        dismissSnackbar()
        await("Repeated error was lost") { session.pendingError?.id != firstId }
        awaitMessage(raw)
        dismissSnackbar()
        awaitMessage("AAUDIO_ERROR_INVALID_STATE")
        dismissSnackbar()
        await("Completed notices were not acknowledged") { session.pendingError == null }
    }

    @Test
    fun snackbarSurvivesRotationRecreationAndBackground() {
        val raw = "AAUDIO_ERROR_DISCONNECTED\nUSB 音频设备已断开，转发已停止"
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("Portrait rotation failed") {
            instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        onMain { session.showError(raw) }
        awaitMessage(raw)
        screenshot("snackbar-portrait.png")
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("Landscape rotation failed") {
            instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        awaitMessage(raw)
        screenshot("snackbar-landscape.png")
        scenario.recreate()
        awaitMessage(raw)
        scenario.moveToState(Lifecycle.State.CREATED)
        // Longer than a normal Long Snackbar: a hidden Activity must not
        // silently consume an error before the user returns.
        SystemClock.sleep(11000)
        assertEquals(raw, session.pendingError?.text)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitMessage(raw)
        dismissSnackbar()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun startRelay(afterStart: (AudioDeviceInfo) -> Unit = {}): AudioDeviceInfo {
        lateinit var input: AudioDeviceInfo
        onMain {
            session.refresh()
            input = session.inputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val output = session.outputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            session.toggleInputSelection(input.id)
            session.toggleOutputSelection(output.id)
            session.setParams(true, input.id, StreamParams(channelCount = 1, sampleRate = 48000, format = AAudio.FORMAT_PCM_FLOAT))
            session.setParams(false, output.id, StreamParams(channelCount = 2, sampleRate = 48000, format = AAudio.FORMAT_PCM_FLOAT))
            session.toggleMixerChannel(0, 0)
            session.startEngine(object : RelaySession.Owner {
                override fun relayStarted() = Unit
                override fun relayClosed() = Unit
            })
            afterStart(input)
        }
        return input
    }

    private fun removeDevice(device: AudioDeviceInfo) {
        // Invoke the exact framework callback with a real descriptor. This is
        // event simulation, not a physical USB unplug test on the emulator.
        val field = RelaySession::class.java.getDeclaredField("deviceCallback").apply { isAccessible = true }
        (field.get(session) as AudioDeviceCallback).onAudioDevicesRemoved(arrayOf(device))
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return nodes
    }

    private fun messageVisible(message: String): Boolean =
        visibleNodes().any { it.isVisibleToUser && it.text?.toString() == message }

    private fun awaitMessage(message: String) = await("Snackbar missing: $message") { messageVisible(message) }

    private fun dismissSnackbar() {
        var clicked = false
        await("Snackbar close action missing") {
            if (clicked) return@await true
            val matches = visibleNodes().filter { it.text?.toString() == "关闭" }
            clicked = matches.any { node ->
                var button: AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            clicked
        }
        SystemClock.sleep(250)
    }

    private fun screenshot(name: String) {
        val directory = instrumentation.targetContext.getExternalFilesDir("snackbar-qa")!!
        File(directory, name).outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun await(failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        if (!condition()) Log.e("ErrorReportingTest", visibleNodes().joinToString("\n") {
            "text=${it.text} desc=${it.contentDescription} visible=${it.isVisibleToUser}"
        })
        assertTrue(failure, condition())
    }
}
