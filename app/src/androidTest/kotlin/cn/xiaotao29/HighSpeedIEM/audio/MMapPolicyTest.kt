package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real streams under a simulated platform NEVER policy, as on legacy TVs. */
@RunWith(AndroidJUnit4::class)
class MMapPolicyTest {
    private external fun nativePolicy(policy: Int): Int

    @Test
    fun allowMmapRespectsPlatformNever() = verifyPolicy(inputAllowed = true, outputAllowed = true)

    @Test
    fun mixedDirectionsRestorePolicyOnSuccessAndFailure() {
        verifyPolicy(inputAllowed = true, outputAllowed = false)
        verifyPolicy(inputAllowed = false, outputAllowed = true)
        verifyPolicy(inputAllowed = true, outputAllowed = false, invalidInput = true)
        verifyPolicy(inputAllowed = true, outputAllowed = false, platformPolicy = 0)
    }

    private fun verifyPolicy(inputAllowed: Boolean, outputAllowed: Boolean, invalidInput: Boolean = false, platformPolicy: Int = 1) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        // Load the app library before calling the debug-only JNI test seam.
        assertNull(NativeRelay.nativeStop())
        val original = nativePolicy(-1)
        assertTrue("Policy getter unavailable: $original", original >= 0)
        try {
            assertEquals(platformPolicy, nativePolicy(platformPolicy)) // NEVER or UNSPECIFIED
            val config = intArrayOf(
                0, 1, 48000, AAudio.FORMAT_PCM_I16, 0,
                AAudio.PERFORMANCE_LOW_LATENCY, AAudio.PRESET_GENERIC, 0, 0,
                0, 2, 48000, AAudio.FORMAT_PCM_I16, 0,
                AAudio.PERFORMANCE_LOW_LATENCY, AAudio.USAGE_MEDIA, AAudio.CONTENT_MUSIC,
                0, 4, 0, 0, 0, if (inputAllowed) 1 else 0, if (outputAllowed) 1 else 0,
            )
            if (invalidInput) config[3] = 12345 // fail after output opened / policy overridden
            val startedAt = SystemClock.elapsedRealtime()
            val error = startTestRelay(config, intArrayOf(1, 1))
            assertTrue("Startup took too long", SystemClock.elapsedRealtime() - startedAt < 10000)
            assertEquals("Process policy leaked", platformPolicy, nativePolicy(-1))
            if (invalidInput) {
                assertNotNull(error)
                assertTrue(NativeRelay.nativeStreamInfo(AAudio.DIRECTION_OUTPUT).isNullOrEmpty())
            } else {
                assertNull(error)
                SystemClock.sleep(1500)
                for (direction in listOf(AAudio.DIRECTION_INPUT, AAudio.DIRECTION_OUTPUT)) {
                    val info = NativeRelay.nativeStreamInfo(direction).orEmpty().asList().chunked(2)
                        .associate { it[0] to it[1] }
                    if (platformPolicy == 1) assertEquals("false", info["MMapUsed"])
                    val counter = if (direction == AAudio.DIRECTION_INPUT) "FramesRead" else "FramesWritten"
                    assertTrue("$counter stalled: $info", info.getValue(counter).toLong() > 0)
                }
            }
        } finally {
            val closeError = NativeRelay.nativeStop()
            val restored = nativePolicy(original)
            instrumentation.runOnMainSync { activity.finish() }
            assertNull(closeError)
            assertEquals(original, restored)
        }
    }
}
