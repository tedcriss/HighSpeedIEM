package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import java.io.File
import java.util.Collections

/** Repeatable warm flows on the same phone. Emulator frame times are comparative evidence only. */
internal class PerformanceAudit(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val session: MonitorSession,
) {
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(predicate()) { label }
    }

    fun run(): String {
        val activity = session.listener as MainActivity
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "performance-qa").apply { mkdirs() }
        val report = StringBuilder()
        val worker = HandlerThread("frame-audit").apply { start() }
        val frames = Collections.synchronizedList(ArrayList<Long>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            }
        }
        var phase = ""
        var cpuStart = 0L
        var versionStart = 0
        fun begin(name: String) {
            phase = name
            frames.clear()
            main { cpuStart = Debug.threadCpuTimeNanos(); versionStart = session.version.value }
            device.executeShellCommand("dumpsys gfxinfo ${instrumentation.targetContext.packageName} reset")
        }
        fun end() {
            var cpu = 0L
            main { cpu = Debug.threadCpuTimeNanos() - cpuStart }
            val sorted = synchronized(frames) { frames.toList().sorted() }
            fun percentile(fraction: Double): Double = if (sorted.isEmpty()) 0.0 else
                sorted[((sorted.size - 1) * fraction).toInt()] / 1_000_000.0
            report.appendLine("$phase: frames=${sorted.size}, p50Ms=${percentile(0.5)}, p95Ms=${percentile(0.95)}, mainCpuMs=${cpu / 1_000_000}, sessionUpdates=${session.version.value - versionStart}")
            File(directory, "$phase-gfxinfo.txt").writeText(device.executeShellCommand("dumpsys gfxinfo ${instrumentation.targetContext.packageName} framestats"))
        }
        try {
            device.setOrientationNatural()
            main { session.stop(null, false) }
            await("cleanup/discovery") { session.visible && !session.forwardingLockedPublic }
            main { session.switchTab(0) }
            SystemClock.sleep(1500)
            main { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
            begin("idle")
            SystemClock.sleep(5000)
            end()
            // Warm each destination before collecting navigation frames.
            repeat(3) { main { session.switchTab(it) }; SystemClock.sleep(500) }
            begin("tabs")
            repeat(18) { main { session.switchTab(it % 3) }; SystemClock.sleep(350) }
            end()
            val input = session.inputs.values.first { it.key !in session.disabled }
            val output = session.outputs.values.first { it.key !in session.disabled }
            main {
                session.model.clear()
                session.setRouteSide(session.model.rows.last(), true, input.key)
                session.setRouteSide(session.model.rows.first(), false, output.key)
                session.switchTab(2)
                activity.startForwarding()
            }
            await("forwarding did not start: ${session.notices.value}") { session.runningPublic }
            SystemClock.sleep(1500)
            begin("forward-visible")
            SystemClock.sleep(10000)
            check(session.runningPublic) { "forwarding stopped: ${session.notices.value}" }
            end()
            main { session.switchTab(0) }
            SystemClock.sleep(1000)
            begin("forward-other-tab")
            SystemClock.sleep(5000)
            check(session.runningPublic) { "forwarding stopped on input tab" }
            end()
            File(directory, "meminfo.txt").writeText(device.executeShellCommand("dumpsys meminfo ${instrumentation.targetContext.packageName}"))
            File(directory, "summary.txt").writeText(report.toString())
            return report.toString().trim().replace("\n", " | ")
        } finally {
            main {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                session.stop(null, false)
                session.switchTab(2)
            }
            await("final audio cleanup") { !session.forwardingLockedPublic }
            worker.quitSafely()
            device.unfreezeRotation()
        }
    }
}
