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
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Buffer telemetry is not a hardware round-trip latency measurement. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class LatencyBufferTest {
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
            override fun onNativeError(sessionId: Long, text: String) { nativeError.set(text) }
            override fun onNativeEvent(sessionId: Long, sequence: Long, type: Int, value1: Long, value2: Long) = Unit
        })
    }

    @After
    fun closeStreams() {
        assertNull(NativeRelay.nativeStop())
        NativeRelay.nativeAttachListener((instrumentation.targetContext.applicationContext as MainApplication).session)
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun bufferSnapshot() {
        assertNull(startTestRelay(config(), intArrayOf(1, 1)))
        val initial = info(0)
        Log.i("LatencyBufferTest", "initial output=$initial")
        val burst = initial.getValue("FramesPerBurst").toInt()
        val capacity = initial.getValue("BufferCapacityInFrames").toInt()
        val size = initial.getValue("BufferSizeInFrames").toInt()
        assertTrue(size in 1..capacity)
        if (arguments.getString("requireFourBursts") == "true") {
            assertEquals(minOf(capacity, burst * 4), size)
        }
        repeat(5) {
            SystemClock.sleep(500)
            Log.i("LatencyBufferTest", "sample=$it input=${info(1)} output=${info(0)}")
        }
        assertTrue(info(1).getValue("StartupFramesDiscarded").toLong() > 0)
        val readBefore = info(1).getValue("FramesRead").toLong()
        // Repeated UI-side updates race against real output callbacks. Invalid
        // input bits and invalid output indices must not access PCM out of bounds.
        repeat(2000) { index ->
            NativeRelay.nativeSetMixer(index % 2, if (index % 3 == 0) -1 else 1)
            NativeRelay.nativeSetMixer(32, -1)
        }
        SystemClock.sleep(300)
        assertTrue(info(1).getValue("FramesRead").toLong() > readBefore)
        assertNull(nativeError.get())
    }

    @Test
    fun normalPerformanceUsesExplicitBufferSize() {
        assertNull(startTestRelay(config(AAudio.PERFORMANCE_NONE, 4096), intArrayOf(1, 1)))
        val initial = info(0)
        SystemClock.sleep(1500)
        val later = info(0)
        assertEquals(initial["BufferSizeInFrames"], later["BufferSizeInFrames"])
        assertEquals(initial["BufferCapacityInFrames"], later["BufferCapacityInFrames"])
        assertTrue(later.getValue("BufferCapacityInFrames").toInt() >= 4096)
        assertTrue(info(1).getValue("FramesRead").toLong() > 0)
        assertNull(nativeError.get())
    }

    private fun config(performance: Int = AAudio.PERFORMANCE_LOW_LATENCY, capacity: Int = 0): IntArray {
        val inId = arguments.getString("inputDeviceId")?.toInt() ?: 0
        val outId = arguments.getString("outputDeviceId")?.toInt() ?: 0
        val inChannels = arguments.getString("inputChannels")?.toInt() ?: 1
        return intArrayOf(
            inId, inChannels, 48000, AAudio.FORMAT_PCM_I16, 1,
            AAudio.PERFORMANCE_LOW_LATENCY, AAudio.PRESET_GENERIC, 0, 0,
            outId, 2, 48000, AAudio.FORMAT_PCM_I16, 0,
            performance, AAudio.USAGE_MEDIA, AAudio.CONTENT_MUSIC, capacity, 4, 0, 0, 0, 1, 1,
        )
    }

    private fun info(direction: Int) = NativeRelay.nativeStreamInfo(direction).orEmpty()
        .asList().chunked(2).associate { it[0] to it[1] }
}
