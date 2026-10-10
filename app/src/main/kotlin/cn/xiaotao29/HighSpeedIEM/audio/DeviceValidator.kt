package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioDeviceInfo
import android.os.Build

/** Result of actually opening a device during discovery. */
data class ValidationResult(
    val identity: String,
    val channels: Int,
    val error: String = "",
    val frames: Long = 0,
    val peaks: FloatArray = FloatArray(0),
    /** -1 unknown, 0 not granted, 1 exclusive opens succeed. */
    val exclusiveSupport: Int = -1,
) {
    fun valid(input: Boolean, channel: Int): Boolean =
        error.isEmpty() && channel < channels && (!input || peaks.getOrNull(channel) ?: 0f > 0f)

    fun retry(input: Boolean): Boolean {
        if (error.isNotEmpty()) return true
        if (input) return peaks.any { it == 0f }
        return false
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Bounded discovery worker pool. Runs entirely outside the realtime engine and
 * always closes its own streams. Results are cached by device identity so a
 * plain refresh keeps the user's manual enable choices.
 */
class DeviceValidator {
    private val workers = java.util.concurrent.Executors.newFixedThreadPool(4)

    data class Target(val device: AudioDeviceInfo, val input: Boolean)

    fun shutdown() { workers.shutdown() }

    companion object {
        fun key(device: AudioDeviceInfo, input: Boolean) = (if (input) "i" else "o") + device.id

        fun identity(device: AudioDeviceInfo, input: Boolean, permission: Boolean): String =
            key(device, input) + "/" + device.type + "/" + device.productName + "/" +
                (if (Build.VERSION.SDK_INT >= 28) device.address else "") + "/" +
                device.channelCounts.contentToString() + device.sampleRates.contentToString() +
                device.encodings.contentToString() + (if (input) "/mic=$permission" else "")

        /** Largest declared channel count. */
        fun declared(device: AudioDeviceInfo): Int = device.channelCounts.maxOrNull() ?: 0
    }

    private fun check(device: AudioDeviceInfo, input: Boolean, permission: Boolean): ValidationResult {
        val declared = declared(device)
        var result = ValidationResult(identity(device, input, permission), declared.coerceIn(1, 32))
        if (input && !permission) {
            return result.copy(error = "android.permission.RECORD_AUDIO: PERMISSION_DENIED")
        }
        try {
            if (declared > 32) {
                throw IllegalArgumentException("AudioDeviceInfo.channelCounts=" + device.channelCounts.contentToString())
            }
            val values = NativeAudio.checkDevice(device.id, input, declared, 1200)
            val channels = values[0].toInt()
            result = result.copy(
                channels = channels,
                error = values[1],
                frames = values[2].toLong(),
                peaks = FloatArray(channels) { c -> values[c + 3].toFloat() },
            )
        } catch (e: Exception) {
            result = result.copy(error = e.toString())
        } catch (e: LinkageError) {
            result = result.copy(error = e.toString())
        }
        return result
    }

    fun scan(devices: List<Target>, permission: Boolean): Map<String, ValidationResult> {
        val results = LinkedHashMap<String, ValidationResult>()
        val tasks = LinkedHashMap<String, java.util.concurrent.Future<ValidationResult>>()
        // Oboe initialises AAudio function pointers lazily; warm once before parallel opens.
        if (devices.isNotEmpty()) {
            NativeAudio.probe(devices.first().device.id, devices.first().input)
        }
        for (t in devices) {
            tasks[key(t.device, t.input)] = workers.submit<ValidationResult> { check(t.device, t.input, permission) }
        }
        for (t in devices) {
            val k = key(t.device, t.input)
            results[k] = try {
                tasks[k]!!.get()
            } catch (e: Exception) {
                ValidationResult(identity(t.device, t.input, permission), declared(t.device).coerceIn(1, 32), e.toString())
            }
        }
        // All parallel streams closed; re-check failures and silent channels alone.
        for (t in devices) {
            val k = key(t.device, t.input)
            val previous = results[k] ?: continue
            if (previous.retry(t.input) && (!t.input || permission)) {
                val retry = check(t.device, t.input, permission)
                if (retry.error.isEmpty()) {
                    val merged = FloatArray(retry.channels) { c ->
                        maxOf(retry.peaks.getOrElse(c) { 0f }, previous.peaks.getOrElse(c) { 0f })
                    }
                    results[k] = retry.copy(peaks = merged)
                }
            }
        }
        for (t in devices) {
            val r = results.getValue(key(t.device, t.input))
            if (r.error.isEmpty()) {
                try {
                    results[key(t.device, t.input)] =
                        r.copy(exclusiveSupport = NativeAudio.exclusiveSupport(t.device.id, t.input, r.channels))
                } catch (e: LinkageError) {
                    // Leave support unknown; the checkbox stays disabled.
                }
            }
        }
        return results
    }
}
