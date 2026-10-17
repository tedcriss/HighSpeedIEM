package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real-device long run; IDs, duration, backlog mode and Activity orientation are runner arguments.
 * On MIUI, foreground MainActivity with adb after starting instrumentation if background launch is blocked.
 */
class DuplexSoakTest {
    @Test fun selectedDevicesLongRun() {
        val i = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = i.targetContext
        val seconds = (args.getString("seconds")?.toInt() ?: 180).coerceIn(10, 3600)
        val inId = args.getString("inputDeviceId")?.toInt() ?: 0
        val outId = args.getString("outputDeviceId")?.toInt() ?: 0
        val backlog = args.getString("backlog")?.toInt() ?: -1
        val orientation = args.getString("orientation")?.toInt()
        val rotateAt = args.getString("rotateAtSeconds")?.toInt()
        // Grant through the app's normal permission UI before invoking this
        // long run. Native audio telemetry does not need a UiAutomation connection.
        assertEquals("Allow HighSpeedIEM microphone permission before the soak run",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        if (Build.VERSION.SDK_INT >= 33)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val currentActivity = AtomicReference<Activity?>(null)
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    currentActivity.set(activity)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val application = context.applicationContext as MainApplication
        application.registerActivityLifecycleCallbacks(lifecycle)
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        i.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            orientation?.let { activity.requestedOrientation = it }
        }
        val failure = AtomicReference<String?>(null)
        val began = SystemClock.elapsedRealtime()
        NativeRelay.nativeAttachListener(object : NativeRelay.Listener {
            override fun onNativeError(sessionId: Long, text: String) { failure.set(text) }
            override fun onNativeEvent(sessionId: Long, sequence: Long, type: Int, value1: Long, value2: Long) {
                Log.i("DuplexSoakTest", JSONObject(mapOf("event" to type, "value1" to value1,
                    "value2" to value2, "elapsedMs" to SystemClock.elapsedRealtime()-began)).toString())
            }
        })
        fun info(direction: Int) = NativeRelay.nativeStreamInfo(direction).orEmpty()
            .asList().chunked(2).associate { it[0] to it[1] }
        try {
            val config = relayConfig(inId, StreamParams.defaultFor(true),
                outId, StreamParams.defaultFor(false), RelayParams(inputBacklogThresholdFrames=backlog))
            assertNull(startTestRelay(config, intArrayOf(1,1)))
            var previousInput = 0L
            var previousOutput = 0L
            repeat(seconds) { tick ->
                SystemClock.sleep(1000)
                val input = info(AAudio.DIRECTION_INPUT)
                val output = info(AAudio.DIRECTION_OUTPUT)
                Log.i("DuplexSoakTest", JSONObject(mapOf("second" to tick+1,
                    "elapsedMs" to SystemClock.elapsedRealtime()-began, "backlogMode" to backlog,
                    "orientation" to context.resources.configuration.orientation,
                    "input" to JSONObject(input), "output" to JSONObject(output))).toString())
                assertNull(failure.get())
                if (inId != 0) assertEquals(inId.toString(), input["DeviceId"])
                if (outId != 0) assertEquals(outId.toString(), output["DeviceId"])
                val read = input.getValue("FramesRead").toLong()
                val written = output.getValue("FramesWritten").toLong()
                assertTrue("Input stopped: $input", read > previousInput)
                assertTrue("Output stopped: $output", written > previousOutput)
                assertNotEquals("EXIT", input["CallbackState"])
                previousInput = read; previousOutput = written
                if (rotateAt != null && tick+1 == rotateAt) i.runOnMainSync {
                    currentActivity.get()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        } finally {
            val stopped = NativeRelay.nativeStop()
            NativeRelay.nativeAttachListener((context.applicationContext as MainApplication).session)
            i.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(lifecycle)
                currentActivity.get()?.let {
                    it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (orientation != null || rotateAt != null)
                        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    it.finish()
                }
            }
            assertNull(stopped)
        }
    }
}
