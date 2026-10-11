package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.audio.NativeAudio
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

internal object NativeEventTestApi {
    external fun freezeProgress()
    external fun resizeBuffer(deviceId: Int, frames: Int): Int
}

/** Real streams and production callbacks on the phone. The two native fault
 * hooks exist only in Debug; no Oboe callback or audio thread is blocked. */
internal class EventDrivenAudit(private val instrumentation: Instrumentation, private val s: MonitorSession) {
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            main { result = condition() }
            if (result) return
            SystemClock.sleep(20)
        }
        error("$label: ${s.status}, native=${NativeAudio.currentSessionId}")
    }
    private fun field(name: String) = MonitorSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun eventFdCount() = File("/proc/self/fd").list().orEmpty().count {
        try { android.system.Os.readlink("/proc/self/fd/$it") == "anon_inode:[eventfd]" }
        catch (_: android.system.ErrnoException) { false } // descriptor closed during enumeration
    }

    fun run(): String {
        val ui = UiDevice.getInstance(instrumentation)
        val events = CopyOnWriteArrayList<NativeAudio.Event>()
        val listener = NativeAudio.eventListener
        NativeAudio.eventListener = { events.add(it); listener?.invoke(it) }
        val checks = ArrayList<String>()
        fun stop() {
            main { s.stop(null, false) }
            waitFor("stop releases all owners") { !s.operationsBlockedPublic && s.service == null }
        }
        waitFor("discovery") { !s.forwardingLockedPublic && s.inputs.isNotEmpty() && s.outputs.isNotEmpty() }
        val input = s.inputs.values.first { it.key !in s.disabled }
        val output = s.outputs.values.first { it.key !in s.disabled }
        val originalDisabled = s.disabled.toSet()
        val savedMode = ui.executeShellCommand("cmd appops get ${instrumentation.targetContext.packageName} RECORD_AUDIO")
        val restoreMode = Regex("RECORD_AUDIO: (\\w+)").find(savedMode)?.groupValues?.get(1) ?: "allow"
        fun start() {
            waitFor("ready to start") { !s.forwardingLockedPublic }
            main {
                s.consumeNotice()
                s.switchTab(2)
                s.enable(input, true); s.enable(output, true)
                s.model.clear()
                val row = s.model.rows.last()
                s.setRouteSide(row, true, input.key)
                s.setRouteSide(row, false, output.key)
                s.prepareForwarding {
                    instrumentation.targetContext.startForegroundService(Intent(instrumentation.targetContext, ForwardingService::class.java))
                }
            }
            waitFor("running", 25000) { s.runningPublic && s.actualStats.isNotEmpty() }
        }
        try {
            start()
            check(field("appOpsWatching").getBoolean(s)) { "AppOps callback not registered" }
            val id = NativeAudio.currentSessionId
            val config = s.endpointSnapshots.value.getValue(output.deviceKey).config
            val beforeSettings = s.endpointSnapshots.value.getValue(output.deviceKey)
            val requested = Regex("burst=(\\d+)").find(config)!!.groupValues[1].toInt()
            val actual = NativeEventTestApi.resizeBuffer(output.device.id, requested)
            check(actual > 0) { "resize returned $actual" }
            waitFor("settings change event", 2000) {
                events.any { it.sessionId == id && it.settingsChanged } &&
                    s.endpointSnapshots.value.getValue(output.deviceKey).revision > beforeSettings.revision
            }
            check(s.endpointSnapshots.value.getValue(output.deviceKey).config === config) { "config re-read" }
            main { s.switchTab(1) }
            val counters = s.endpointSnapshots.value.mapValues { it.value.telemetry }
            SystemClock.sleep(3600)
            check(s.endpointSnapshots.value.mapValues { it.value.telemetry } == counters) { "hidden task telemetry sampled" }
            checks.add("open config cached; actual buffer event/revision; hidden telemetry stopped")

            val errorAt = SystemClock.uptimeMillis()
            val failures = (1..12).map { Thread { NativeAudio.injectError(-899) }.apply { this.start() } }
            failures.forEach { it.join() }
            waitFor("fatal event", 2000) { !s.runningPublic }
            val eventLatency = SystemClock.uptimeMillis() - errorAt
            waitFor("fatal cleanup") { !s.operationsBlockedPublic && s.service == null }
            check(events.count { it.sessionId == id && it.error.isNotEmpty() } == 1) { "duplicate error event" }
            check(NativeAudio.endpointStats(input.device.id, true).isEmpty())
            check(NativeAudio.endpointStats(output.device.id, false).isEmpty())
            check(s.notices.value.orEmpty().contains("ErrorDisconnected")) { "original error lost" }
            checks.add("12 concurrent errors reported once; all endpoints closed (${eventLatency}ms)")

            start()
            val newId = NativeAudio.currentSessionId
            val receive = MonitorSession::class.java.getDeclaredMethod("onNativeEvent", NativeAudio.Event::class.java).apply { isAccessible = true }
            main { receive.invoke(s, NativeAudio.Event(id, true, "STALE_ERROR")) }
            check(s.runningPublic && NativeAudio.currentSessionId == newId && s.notices.value != "STALE_ERROR")
            NativeEventTestApi.freezeProgress()
            val stallAt = SystemClock.uptimeMillis()
            waitFor("unreported stall watchdog", 4200) { !s.runningPublic }
            waitFor("stall cleanup") { !s.operationsBlockedPublic }
            check(s.notices.value.orEmpty().contains("ErrorTimeout")) { "stall raw error missing" }
            checks.add("stale generation ignored; frame stall detected in ${SystemClock.uptimeMillis() - stallAt}ms")

            start()
            val opsAt = SystemClock.uptimeMillis()
            ui.executeShellCommand("cmd appops set --uid ${instrumentation.targetContext.packageName} RECORD_AUDIO ignore")
            waitFor("AppOps event stops capture", 4000) { !s.runningPublic }
            waitFor("AppOps cleanup") { !s.operationsBlockedPublic }
            val error = s.notices.value.orEmpty()
            check(error.contains("AppOpsManager.OPSTR_RECORD_AUDIO") || error.contains("isClientSilenced")) { error }
            checks.add("real AppOps denial event stops capture (${SystemClock.uptimeMillis() - opsAt}ms)")
            ui.executeShellCommand("cmd appops set --uid ${instrumentation.targetContext.packageName} RECORD_AUDIO $restoreMode")

            // Many genuine starts/stops exercise eventfd and waiter ownership.
            val spec = intArrayOf(input.device.id, input.index, input.total, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0)
            val fdCount = File("/proc/self/fd").list()!!.size
            val eventFds = eventFdCount()
            repeat(20) {
                check(NativeAudio.startConfigured(spec).isEmpty())
                NativeAudio.stop()
            }
            val fdAfter = File("/proc/self/fd").list()!!.size
            check(fdAfter <= fdCount + 2) { "descriptor leak: $fdCount -> $fdAfter" }
            waitFor("eventfd cleanup", 2000) { eventFdCount() <= eventFds }
            check(Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "IEM-native-events" }) { "native waiter leaked" }
            val handler = field("main").get(s) as Handler
            main {
                for (name in listOf("healthWatchdog", "spectrumSample", "telemetrySample", "operationDeadline")) {
                    check(!handler.hasCallbacks(field(name).get(s) as Runnable)) { "idle timer: $name" }
                }
            }
            checks.add("20 start/stop cycles; fd=$fdCount->$fdAfter eventfd=$eventFds->${eventFdCount()}; no waiter or idle timers")
            return checks.joinToString("; ")
        } finally {
            ui.executeShellCommand("cmd appops set --uid ${instrumentation.targetContext.packageName} RECORD_AUDIO $restoreMode")
            NativeAudio.stop()
            stop()
            NativeAudio.eventListener = listener
            main {
                s.disabled.clear(); s.disabled.addAll(originalDisabled)
                s.switchTab(2)
            }
        }
    }
}
