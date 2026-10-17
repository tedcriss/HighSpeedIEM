package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real emulator AAudio, default auto recovery, and both phone orientations. */
class AutomaticBacklogTest {
    @Test fun defaultAutomaticRecoveryKeepsDuplexAdvancingAcrossRotation() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val failure = AtomicReference<String?>(null)
        NativeRelay.nativeAttachListener(object : NativeRelay.Listener {
            override fun onNativeError(sessionId: Long, text: String) { failure.set(text) }
            override fun onNativeEvent(sessionId: Long, sequence: Long, type: Int, value1: Long, value2: Long) = Unit
        })
        fun input() = NativeRelay.nativeStreamInfo(AAudio.DIRECTION_INPUT).orEmpty()
            .asList().chunked(2).associate { it[0] to it[1] }
        try {
            val config = relayConfig(0, StreamParams.defaultFor(true).copy(channelCount=1),
                0, StreamParams.defaultFor(false).copy(channelCount=2), RelayParams())
            assertEquals(-1, config[ConfigField.BACKLOG.ordinal])
            assertNull(startTestRelay(config, intArrayOf(1,1)))
            var previous = 0L
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                i.runOnMainSync { activity.requestedOrientation = orientation }
                repeat(6) {
                    // No diagnostic calls between observations; audio owns recovery.
                    SystemClock.sleep(5000)
                    val info = input()
                    Log.i("AutomaticBacklogTest", "orientation=$orientation input=$info")
                    assertNull(failure.get())
                    val read = info.getValue("FramesRead").toLong()
                    assertTrue("Input stopped: $info", read > previous)
                    assertTrue(info.getValue("InputBacklogLimitFrames").toInt() > 0)
                    assertNotEquals("EXIT", info["CallbackState"])
                    previous = read
                }
            }
        } finally {
            val stopped = NativeRelay.nativeStop()
            NativeRelay.nativeAttachListener((context.applicationContext as MainApplication).session)
            i.runOnMainSync { activity.finish() }
            assertNull(stopped)
        }
    }
}
