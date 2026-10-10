package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.audio.DeviceValidator
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.audio.NativeAudio
import cn.xiaotao29.HighSpeedIEM.audio.ValidationResult
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses real phone audio devices/streams and the production native callback.
 * Only topology snapshots/events are substituted; physical USB is not emulated.
 * Reflection keeps these fixtures entirely out of the release API. */
internal class DeviceLifecycleAudit(private val instrumentation: Instrumentation, private val s: MonitorSession) {
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        }
        error("$label; status=${s.status}, loading=${s.loadingPublic}, busy=${s.busyPublic}")
    }
    private fun field(name: String) = MonitorSession::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    fun run(): String {
        waitFor("initial idle") { !s.forwardingLockedPublic && s.inputs.isNotEmpty() && s.outputs.isNotEmpty() }
        val manager = instrumentation.targetContext.getSystemService(AudioManager::class.java)
        val original = manager.getDevices(AudioManager.GET_DEVICES_ALL)
        val reader = field("readDevices")
        val savedReader = reader.get(s)
        val callback = field("deviceCallback").get(s) as AudioDeviceCallback
        val audio = field("audio").get(s) as ExecutorService
        val cached = field("validated").get(s) as Map<String, ValidationResult>
        val reads = AtomicInteger()
        var snapshot = original
        var originalDisabled = emptySet<String>()
        main {
            originalDisabled = s.disabled.toSet()
            reader.set(s, { reads.incrementAndGet(); snapshot })
            s.switchTab(2)
        }
        val input = s.inputs.values.first { it.key !in s.disabled }
        val output = s.outputs.values.first { it.key !in s.disabled }
        val changed = arrayOf(input.device)
        val checks = ArrayList<String>()
        val ui = UiDevice.getInstance(instrumentation)
        fun inputVisible(present: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                val found = ui.findObject(By.descContains("ID ${input.device.id}，通道 ${input.index}，")) != null
                if (found == present) return
                SystemClock.sleep(50)
            }
            error("input UI presence did not become $present")
        }
        fun settled() = waitFor("discovery settled") { !s.forwardingLockedPublic }
        fun pair() = main {
            s.model.clear()
            s.setRouteSide(s.model.rows.first(), true, input.key)
            s.setRouteSide(s.model.rows.first(), false, output.key)
        }
        fun prepare(ready: () -> Unit = {
            instrumentation.targetContext.startForegroundService(
                Intent(instrumentation.targetContext, ForwardingService::class.java))
        }) = main { s.prepareForwarding(ready) }
        fun stop() {
            main { s.stop(null, false) }
            waitFor("cleanup and refresh", 30_000) { !s.forwardingLockedPublic && s.service == null }
        }
        var blockedAudio: CountDownLatch? = null
        var blockedTest: CountDownLatch? = null
        try {
            check(ValidationResult("silent input", 2).valid(0))
            check(ValidationResult("silent input", 2).valid(1))
            check(!ValidationResult("broken input", 2, "ErrorUnavailable").valid(0))
            val probe = NativeAudio.checkDevice(input.device.id, true, input.total)
            check(probe.size == 2 && probe[1].isEmpty()) { probe.contentToString() }
            check(NativeAudio.endpointStats(input.device.id, true).isEmpty())
            checks += "open-only/silent-input enabled"

            val unchanged = cached[output.deviceKey]
            main {
                s.enable(output, false)
                callback.onAudioDevicesAdded(changed)
                check(s.loadingPublic && s.operationsBlockedPublic)
                s.enable(output, true)
                s.selectInput(input.key)
                check(output.key in s.disabled && s.selectedInput == null)
            }
            settled()
            check(cached[output.deviceKey] === unchanged) { "unrelated endpoint re-probed" }
            check(output.key in s.disabled) { "manual disable lost" }
            main {
                // Enable choices control forwarding, independently of tests.
                s.selectOutput(output.key)
                check(s.selectedOutput == output.key)
                s.selectOutput(output.key)
                s.enable(output, true)
            }
            checks += "incremental cache/manual choices/loading lock"

            pair()
            main {
                snapshot = original.filter { it.id != input.device.id }.toTypedArray()
                callback.onAudioDevicesRemoved(changed)
            }
            settled()
            check(input.key !in s.inputs && s.model.rows.none { it.input == input.key })
            main { snapshot = original; callback.onAudioDevicesAdded(changed) }
            settled()
            check(input.key in s.inputs && input.key !in s.disabled)
            checks += "idle remove/re-add and route pruning"

            repeat(3) {
                main { s.switchTab(0) }
                inputVisible(true)
                main {
                    snapshot = original.filter { it.id != input.device.id }.toTypedArray()
                    callback.onAudioDevicesRemoved(changed)
                }
                settled()
                inputVisible(false)
                main {
                    // Save a shorter measured page, then expand its list while
                    // hidden. Returning used to restore an undersized array.
                    s.switchTab(2)
                    snapshot = original
                    callback.onAudioDevicesAdded(changed)
                }
                settled()
                main { s.switchTab(0) }
                inputVisible(true)
            }
            main { s.switchTab(2) }
            checks += "3 visible-remove/hidden-readd/page-restore cycles"

            // Hold the audio executor before an old scan, then replace its
            // topology. The obsolete scan must not publish or unlock the UI.
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            blockedAudio = release
            audio.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            check(entered.await(2, TimeUnit.SECONDS))
            main { callback.onAudioDevicesAdded(changed); s.refresh() }
            check(field("scanInFlight").getBoolean(s))
            main {
                repeat(40) { index ->
                    snapshot = if (index % 2 == 0) emptyArray() else original
                    callback.onAudioDevicesRemoved(original)
                }
                snapshot = original
                callback.onAudioDevicesAdded(original)
                check(s.loadingPublic)
            }
            SystemClock.sleep(300) // debounce collects the final snapshot
            release.countDown()
            blockedAudio = null
            settled()
            val sourceIds = s.inputs.values.map { it.device.id }.toSet()
            val sinkIds = s.outputs.values.map { it.device.id }.toSet()
            check(sourceIds == original.filter { it.isSource }.map { it.id }.toSet())
            check(sinkIds == original.filter { it.isSink }.map { it.id }.toSet())
            checks += "41-event burst/in-flight replacement/system-list parity"

            // Cleanup longer than the former 3-second drain timeout must not
            // unlock discovery or hand the same device to a new owner.
            val testExecutor = field("test").get(s) as ExecutorService
            val testEntered = CountDownLatch(1)
            val releaseTest = CountDownLatch(1)
            blockedTest = releaseTest
            testExecutor.execute { testEntered.countDown(); releaseTest.await(10, TimeUnit.SECONDS) }
            check(testEntered.await(2, TimeUnit.SECONDS))
            main { callback.onAudioDevicesAdded(changed); s.refresh() }
            SystemClock.sleep(3_300)
            main {
                check(s.loadingPublic && s.operationsBlockedPublic)
                s.prepareForwarding { error("forwarding accepted during discovery") }
                check(!s.startingPublic)
            }
            releaseTest.countDown()
            blockedTest = null
            settled()
            checks += "slow prior cleanup retains exclusive operation lock"

            main { s.switchTab(0); s.selectInput(input.key); s.updatePreview() }
            waitFor("preview open") { !s.previewOpeningPublic && s.previewKeyPublic == input.key }
            main {
                snapshot = original.filter { it.id != input.device.id }.toTypedArray()
                callback.onAudioDevicesRemoved(changed)
            }
            settled()
            check(s.selectedInput == null && s.spectrum.value.isEmpty())
            check(NativeAudio.endpointStats(input.device.id, true).isEmpty())
            main { snapshot = original; callback.onAudioDevicesAdded(changed); s.switchTab(2) }
            settled()
            checks += "preview removal releases capture"

            pair()
            var preparationHeld = false
            prepare { preparationHeld = true }
            waitFor("preparation callback") { preparationHeld }
            val beforePreparation = reads.get()
            val frozenRows = s.model.rows
            val frozenInput = s.inputs[input.key]
            main {
                snapshot = emptyArray()
                callback.onAudioDevicesRemoved(original)
                s.refresh()
                check(s.startingPublic && !s.loadingPublic)
                check(s.inputs[input.key] === frozenInput && s.model.rows === frozenRows)
            }
            check(reads.get() == beforePreparation)
            main { snapshot = original; callback.onAudioDevicesAdded(original) }
            stop()
            checks += "preparation freezes discovery until cleanup"

            pair()
            prepare()
            waitFor("forward running", 25_000) { s.runningPublic }
            val beforeRunning = reads.get()
            val runningRows = s.model.rows
            main {
                snapshot = original.filter { it.id != output.device.id }.toTypedArray()
                callback.onAudioDevicesRemoved(arrayOf(output.device))
                s.refresh()
                check(s.runningPublic && !s.loadingPublic && s.model.rows === runningRows)
            }
            SystemClock.sleep(400)
            check(reads.get() == beforeRunning)
            check(s.runningPublic) { "topology alone stopped forwarding" }
            stop()
            check(output.key !in s.outputs)
            check(NativeAudio.endpointStats(input.device.id, true).isEmpty())
            check(NativeAudio.endpointStats(output.device.id, false).isEmpty())
            main { snapshot = original; callback.onAudioDevicesAdded(arrayOf(output.device)) }
            settled()
            checks += "running defers changes/normal stop refreshes"

            pair()
            prepare()
            waitFor("forward running", 25_000) { s.runningPublic }
            main {
                snapshot = original.filter { it.id != input.device.id }.toTypedArray()
                callback.onAudioDevicesRemoved(changed)
                NativeAudio.injectError(-899)
            }
            waitFor("disconnect closes all owners", 30_000) { !s.forwardingLockedPublic && s.service == null }
            check(input.key !in s.inputs)
            check(NativeAudio.endpointStats(input.device.id, true).isEmpty())
            check(NativeAudio.endpointStats(output.device.id, false).isEmpty())
            check(s.notices.value?.contains("ErrorDisconnected") == true ||
                s.consumePendingMessages()?.contains("ErrorDisconnected") == true) { "raw disconnect notice missing" }
            checks += "native disconnect stops/releases/notifies/refreshes"

            main {
                s.consumeNotice()
                reader.set(s, { throw IllegalStateException("AudioManager device snapshot failure") })
                s.refresh()
            }
            settled()
            check(s.notices.value == "java.lang.IllegalStateException: AudioManager device snapshot failure")
            main {
                snapshot = original
                reader.set(s, { reads.incrementAndGet(); snapshot })
                s.refresh()
            }
            settled()
            checks += "enumeration failure preserves raw UI error/recovery"
            return checks.joinToString("; ")
        } finally {
            blockedAudio?.countDown()
            blockedTest?.countDown()
            main { snapshot = original; reader.set(s, savedReader); s.stop(null, false) }
            waitFor("final cleanup", 30_000) { !s.busyPublic }
            main { callback.onAudioDevicesAdded(original) }
            settled()
            main {
                s.disabled.clear()
                s.disabled.addAll(originalDisabled)
                s.model.clear()
                s.switchTab(0)
                s.consumeNotice()
            }
        }
    }
}
