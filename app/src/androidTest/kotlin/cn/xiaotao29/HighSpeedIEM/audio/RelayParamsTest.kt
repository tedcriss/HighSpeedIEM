package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Session-level relay parameters (buffer size multiples, auto/manual output
 * sizing, input read threshold gate) against real AAudio IO. Optional
 * instrumentation arguments select physical device IDs like the other tests.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class RelayParamsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private lateinit var activity: MainActivity
    private val nativeError = AtomicReference<String?>(null)

    @Before
    fun openActivity() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()
        NativeRelay.nativeAttachListener(object : NativeRelay.Listener {
            override fun onNativeError(text: String) { nativeError.set(text) }
        })
    }

    @After
    fun closeStreams() {
        assertNull(NativeRelay.nativeStop())
        NativeRelay.nativeAttachListener((instrumentation.targetContext.applicationContext as MainApplication).session)
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun zeroThresholdKeepsLegacyBehavior() {
        assertNull(NativeRelay.nativeStart(config(), intArrayOf(1, 1)))
        SystemClock.sleep(1500)
        assertRelayProgresses("F=0")
        // The realtime row exists even when the callback gate is off.
        assertAvailableFramesRow(AAudio.DIRECTION_INPUT)
        assertAvailableFramesRow(AAudio.DIRECTION_OUTPUT)
        assertNull(nativeError.get())
    }

    @Test
    fun gateWithSmallThresholdKeepsRunning() {
        // F = 2 output bursts + 64 frames; the gate must pass (or the stall
        // valve must open) so the input is never permanently silenced.
        assertNull(NativeRelay.nativeStart(
            config(thresholdBursts = 2, thresholdExtraFrames = 64), intArrayOf(1, 1)))
        SystemClock.sleep(1500)
        assertRelayProgresses("F=2*burst+64")
        assertAvailableFramesRow(AAudio.DIRECTION_INPUT)
        assertNull(nativeError.get())
    }

    @Test
    fun gateThresholdAboveInputBufferStillRuns() {
        // F is capped at the actual input BufferSizeInFrames, so an oversized
        // request must degrade to the cap, not deadlock the relay.
        assertNull(NativeRelay.nativeStart(
            config(thresholdBursts = 10, thresholdExtraFrames = 1024), intArrayOf(1, 1)))
        SystemClock.sleep(1500)
        val input = info(AAudio.DIRECTION_INPUT)
        Log.i("RelayParamsTest", "oversized F input=$input")
        assertRelayProgresses("F=10*burst+1024")
        assertNull(nativeError.get())
    }

    @Test
    fun manualOutputBufferDisablesAutoTuning() {
        assertNull(NativeRelay.nativeStart(
            config(outBufferSizeBursts = 4, outAutoBufferSize = false), intArrayOf(1, 1)))
        val initial = info(AAudio.DIRECTION_OUTPUT)
        Log.i("RelayParamsTest", "manual output=$initial")
        val burst = initial.getValue("FramesPerBurst").toInt()
        val capacity = initial.getValue("BufferCapacityInFrames").toInt()
        val size = initial.getValue("BufferSizeInFrames").toInt()
        assertTrue("size $size out of range (capacity $capacity)", size in 1..capacity)
        // The setter may not adopt the request exactly, but it must stay
        // within the requested clamp; and with auto tuning disabled the size
        // must not grow on its own afterwards.
        assertTrue("size $size exceeds requested clamp ${minOf(capacity, burst * 4)}",
            size <= minOf(capacity, burst * 4) + burst)
        SystemClock.sleep(2000)
        val later = info(AAudio.DIRECTION_OUTPUT)
        assertEquals(initial["BufferSizeInFrames"], later["BufferSizeInFrames"])
        assertRelayProgresses("manual output buffer")
        assertNull(nativeError.get())
    }

    @Test
    fun inputBufferSizeRequestIsBestEffort() {
        assertNull(NativeRelay.nativeStart(config(inBufferSizeBursts = 2), intArrayOf(1, 1)))
        val input = info(AAudio.DIRECTION_INPUT)
        Log.i("RelayParamsTest", "input sized=$input")
        val size = input.getValue("BufferSizeInFrames").toInt()
        val capacity = input.getValue("BufferCapacityInFrames").toInt()
        if (capacity > 0) assertTrue(size in 1..capacity)
        assertRelayProgresses("input buffer size 2*burst")
        assertNull(nativeError.get())
    }

    private fun assertRelayProgresses(label: String) {
        val before = info(AAudio.DIRECTION_INPUT).getValue("FramesRead").toLong()
        val outBefore = info(AAudio.DIRECTION_OUTPUT).getValue("FramesWritten").toLong()
        SystemClock.sleep(600)
        val after = info(AAudio.DIRECTION_INPUT).getValue("FramesRead").toLong()
        val outAfter = info(AAudio.DIRECTION_OUTPUT).getValue("FramesWritten").toLong()
        assertTrue("$label: input stalled at $before frames", after > before)
        assertTrue("$label: output stalled at $outBefore frames", outAfter > outBefore)
    }

    private fun assertAvailableFramesRow(direction: Int) {
        val value = info(direction)["getAvailableFrames()"]
        assertNotNull("getAvailableFrames() row missing", value)
        assertTrue("unexpected availability value: $value",
            value == "Unavailable" || (value!!.toLongOrNull() ?: -1L) >= 0)
    }

    private fun config(
        inBufferSizeBursts: Int = 0,
        outBufferSizeBursts: Int = 0,
        outAutoBufferSize: Boolean = true,
        thresholdBursts: Int = 0,
        thresholdExtraFrames: Int = 0,
    ): IntArray {
        val inId = arguments.getString("inputDeviceId")?.toInt() ?: 0
        val outId = arguments.getString("outputDeviceId")?.toInt() ?: 0
        val inChannels = arguments.getString("inputChannels")?.toInt() ?: 1
        return intArrayOf(
            inId, inChannels, 48000, AAudio.FORMAT_PCM_I16, 1,
            AAudio.PERFORMANCE_LOW_LATENCY, AAudio.PRESET_GENERIC, 0, inBufferSizeBursts,
            outId, 2, 48000, AAudio.FORMAT_PCM_I16, 0,
            AAudio.PERFORMANCE_LOW_LATENCY, AAudio.USAGE_MEDIA, AAudio.CONTENT_MUSIC,
            0, outBufferSizeBursts, if (outAutoBufferSize) 1 else 0,
            thresholdBursts, thresholdExtraFrames,
        )
    }

    private fun info(direction: Int) = NativeRelay.nativeStreamInfo(direction).orEmpty()
        .asList().chunked(2).associate { it[0] to it[1] }
}
