package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.app.KeyguardManager
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioDeviceInfo
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production Activity -> foreground service -> session, never native-only startup. */
class LockScreenRelayTest {
    @Test fun forwardingSurvivesScreenOffAndUnlockInBothOrientations() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val session = (context.applicationContext as MainApplication).session
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceActive() = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == ForwardingService::class.java.name && it.foreground }
        fun shell(command: String) = i.uiAutomation.executeShellCommand(command).close()
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        fun onMain(block: () -> Unit) = i.runOnMainSync(block)
        fun state(check: () -> Boolean): Boolean {
            var value = false
            onMain { value = check() }
            return value
        }
        fun info(direction: Int) = NativeRelay.nativeStreamInfo(direction).orEmpty()
            .asList().chunked(2).associate { it[0] to it[1] }
        fun advancing(label: String) {
            val beforeIn = info(1).getValue("FramesRead").toLong()
            val beforeOut = info(0).getValue("FramesWritten").toLong()
            SystemClock.sleep(5000)
            val input = info(1); val output = info(0)
            Log.i("LockScreenRelayTest", "$label locked=${keyguard.isKeyguardLocked} interactive=${power.isInteractive} input=$input output=$output")
            assertTrue("$label: session stopped", state { session.forwarding && !session.busy })
            assertTrue("$label: input stopped: $input", input.getValue("FramesRead").toLong() > beforeIn)
            assertTrue("$label: output stopped: $output", output.getValue("FramesWritten").toLong() > beforeOut)
            assertNotEquals("EXIT", input["CallbackState"])
        }
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    SystemClock.sleep(1500)
                    scenario.onActivity { activity ->
                        session.refresh()
                        session.toggleInputSelection(session.inputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.id)
                        session.toggleOutputSelection(session.outputDevices.values.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id)
                        session.setMixerMask(0, 1); session.setMixerMask(1, 1)
                        activity.startForwarding()
                        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) activity.startForwarding()
                    }
                    waitFor("relay did not start") { state { session.forwarding } }
                    // Repeated delivery must not detach the service from the live session.
                    // This can also happen after rapid repeated taps before onStartCommand.
                    ContextCompat.startForegroundService(context, Intent(context, ForwardingService::class.java))
                    SystemClock.sleep(1000)
                    assertTrue("duplicate start detached service", state { session.owner != null && session.forwarding })
                    assertTrue("duplicate start removed foreground service", serviceActive())
                    advancing("orientation=$orientation foreground")
                    shell("input keyevent 223")
                    waitFor("screen did not turn off") { !power.isInteractive }
                    waitFor("keyguard did not lock") { keyguard.isKeyguardLocked }
                    repeat(6) { advancing("orientation=$orientation screen-off-$it") }
                    assertTrue("foreground service lost while locked", serviceActive())
                    shell("input keyevent 224")
                    shell("wm dismiss-keyguard")
                    advancing("orientation=$orientation unlocked")
                    onMain { session.stopForwarding(clearSelection = true) }
                    waitFor("relay did not stop") { state { !session.busy && !session.forwarding && session.owner == null } }
                    waitFor("service did not stop") { !serviceActive() }
                    val powerDump = ParcelFileDescriptor.AutoCloseInputStream(
                        i.uiAutomation.executeShellCommand("dumpsys power")).bufferedReader().use { it.readText() }
                    assertFalse("wake lock leaked after stop", Regex("PARTIAL_WAKE_LOCK\\s+'highspeediem:relay'").containsMatchIn(powerDump))
                }
            } finally {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
                onMain { session.stopForwarding(clearSelection = true) }
                waitFor("cleanup did not stop relay") { state { !session.busy && !session.forwarding } }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    @Test fun failedAudioOpenStopsServiceAndReportsOriginalError() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val session = (context.applicationContext as MainApplication).session
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                session.refresh()
                session.toggleInputSelection(session.inputDevices.values.first { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.id)
                session.toggleOutputSelection(session.outputDevices.values.first { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id)
                val inputId = session.selectedInputId!!
                session.setParams(true, inputId, session.paramsFor(true, inputId).copy(inputPreset = Int.MAX_VALUE))
                while (session.pendingError != null) session.consumeError(session.pendingError!!.id)
            }
            // Let real AAudio reject a test-only invalid preset after FGS promotion.
            fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
                i.uiAutomation.executeShellCommand(command)).bufferedReader().use { reader -> reader.readText() }
            try {
                ContextCompat.startForegroundService(context, Intent(context, ForwardingService::class.java))
                val deadline = SystemClock.elapsedRealtime() + 10000
                var error: String? = null
                while (error == null && SystemClock.elapsedRealtime() < deadline) {
                    i.runOnMainSync { error = session.pendingError?.text }
                    SystemClock.sleep(100)
                }
                Log.i("LockScreenRelayTest", "failed audio open error=$error")
                assertTrue("original platform error missing: $error", error?.contains("AAUDIO_ERROR_ILLEGAL_ARGUMENT") == true)
                SystemClock.sleep(500)
                val services = shell("dumpsys activity services ${context.packageName}")
                assertFalse("failed startup left service running: $services", services.contains("ServiceRecord{"))
                val powerDump = shell("dumpsys power")
                assertFalse("failed startup leaked wake lock", Regex("PARTIAL_WAKE_LOCK\\s+'highspeediem:relay'").containsMatchIn(powerDump))
            } finally {
                i.runOnMainSync {
                    session.stopForwarding(clearSelection = true)
                    session.inputParams.clear()
                    session.pendingError?.let { session.consumeError(it.id) }
                }
            }
        }
    }
}
