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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real AAudio IO; optional instrumentation arguments select physical device IDs. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class RelayProgressTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val nativeError = AtomicReference<String?>(null)
    private lateinit var activity: MainActivity

    @Before
    fun openForegroundActivity() {
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
        val closeError = NativeRelay.nativeStop()
        NativeRelay.nativeAttachListener(
            (instrumentation.targetContext.applicationContext as MainApplication).session,
        )
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        assertNull(closeError)
    }

    @Test
    fun exclusiveInputReadsWithoutUiPolling() {
        verifyProgress(exclusive = true, format = AAudio.FORMAT_PCM_FLOAT)
    }

    @Test
    fun sharedInputReadsWithoutUiPolling() {
        verifyProgress(exclusive = false, format = AAudio.FORMAT_PCM_FLOAT)
    }

    @Test
    fun repeatedStartStopAndLiveMixer() {
        repeat(3) { attempt ->
            verifyProgress(exclusive = attempt % 2 == 0, format = AAudio.FORMAT_PCM_I16)
            // Switching routes while running must not stall the input reader.
            NativeRelay.nativeSetMixer(0, 0)
            NativeRelay.nativeSetMixer(1, 1)
            val before = streamInfo(AAudio.DIRECTION_INPUT).getValue("FramesRead").toLong()
            SystemClock.sleep(300)
            assertTrue(streamInfo(AAudio.DIRECTION_INPUT).getValue("FramesRead").toLong() > before)
            assertNull(NativeRelay.nativeStop())
            assertTrue(streamInfo(AAudio.DIRECTION_INPUT).isEmpty())
            assertTrue(streamInfo(AAudio.DIRECTION_OUTPUT).isEmpty())
        }
    }

    @Test
    fun frontEndResolvedInputUsesOutputPcmSettings() {
        verifyProgress(exclusive = false, format = AAudio.FORMAT_PCM_I16,
            sampleRate = 44100, automaticInput = true)
    }

    private fun verifyProgress(exclusive: Boolean, format: Int,
        sampleRate: Int = 48000, automaticInput: Boolean = false) {
        val inId = arguments.getString("inputDeviceId")?.toInt() ?: 0
        val outId = arguments.getString("outputDeviceId")?.toInt() ?: 0
        val inChannels = arguments.getString("inputChannels")?.toInt() ?: 1
        val config = intArrayOf(
            inId, inChannels, sampleRate,
            if (automaticInput) AAudio.FORMAT_UNSPECIFIED else format, if (exclusive) 1 else 0,
            AAudio.PERFORMANCE_LOW_LATENCY, AAudio.PRESET_GENERIC, 0, 0,
            outId, 2, sampleRate, format, 0,
            AAudio.PERFORMANCE_LOW_LATENCY, AAudio.USAGE_MEDIA, AAudio.CONTENT_MUSIC,
            0, 4, 0, 0, 0, 1, 1,
        )
        nativeError.set(null)
        assertNull(startTestRelay(config, intArrayOf(1, 1)))
        // Deliberately do not query state, timestamps or frame counters during startup.
        // Diagnostic polling must never be required to make an MMAP input advance.
        SystemClock.sleep(1500)
        val input = streamInfo(AAudio.DIRECTION_INPUT)
        val output = streamInfo(AAudio.DIRECTION_OUTPUT)
        Log.i("RelayProgressTest", "exclusive=$exclusive input=$input output=$output")
        assertNull(nativeError.get())
        assertEquals(sampleRate.toString(), input["SampleRate"])
        assertEquals(output["Format"], input["Format"])
        assertEquals("AAUDIO_STREAM_STATE_STARTED", input["State"])
        assertTrue("Input stalled: $input", input.getValue("FramesRead").toLong() >= 4800)
        assertTrue("Output stalled: $output", output.getValue("FramesWritten").toLong() >= 4800)
        if (arguments.getString("requireSignal") == "true") {
            assertEquals("No non-silent PCM reached the output callback: $output", "true", output["NonSilentOutputSeen"])
        }
        if (inId != 0) assertEquals(inId.toString(), input["DeviceId"])
        if (outId != 0) assertEquals(outId.toString(), output["DeviceId"])
        if (arguments.getString("requireExclusiveMmap") == "true" && exclusive) {
            assertEquals("AAUDIO_SHARING_MODE_EXCLUSIVE", input["SharingMode"])
            assertEquals("true", input["MMapUsed"])
        }
    }

    private fun streamInfo(direction: Int): Map<String, String> =
        NativeRelay.nativeStreamInfo(direction).orEmpty().asList().chunked(2)
            .associate { it[0] to it[1] }
}
