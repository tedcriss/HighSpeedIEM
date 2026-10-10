package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioDeviceInfo
import android.os.Build

/** Result of actually opening a device during discovery. */
data class ValidationResult(
    val identity: String,
    val channels: Int,
    val error: String = "",
    /** -1 unknown, 0 not granted, 1 exclusive opens succeed. */
    val exclusiveSupport: Int = -1,
) {
    fun valid(channel: Int): Boolean = error.isEmpty() && channel in 0 until channels

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Serial open-only discovery on the session's audio executor. Android capture
 * policy can silence/deny competing recorders, so discovery never opens several
 * inputs together. It never starts recording or judges silence as failure.
 */
class DeviceValidator {
    data class Target(val device: AudioDeviceInfo, val input: Boolean)

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
            val values = NativeAudio.checkDevice(device.id, input, declared)
            val channels = values[0].toInt()
            result = result.copy(
                channels = channels,
                error = values[1],
            )
        } catch (e: Exception) {
            result = result.copy(error = e.toString())
        } catch (e: LinkageError) {
            result = result.copy(error = e.toString())
        }
        return result
    }

    fun scan(devices: List<Target>, permission: Boolean, obsolete: () -> Boolean): Map<String, ValidationResult> {
        val results = LinkedHashMap<String, ValidationResult>()
        for (t in devices) {
            if (obsolete()) break // Close the current probe before abandoning the old snapshot.
            val k = key(t.device, t.input)
            var r = check(t.device, t.input, permission)
            if (!obsolete() && r.error.isEmpty()) {
                try {
                    r = r.copy(exclusiveSupport = NativeAudio.exclusiveSupport(t.device.id, t.input, r.channels))
                } catch (e: Exception) {
                    r = r.copy(error = e.toString())
                } catch (e: LinkageError) {
                    r = r.copy(error = e.toString())
                }
            }
            results[k] = r
        }
        return results
    }
}
