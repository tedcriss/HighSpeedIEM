package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioDeviceInfo

/**
 * Per-device recording/playback parameters. A value of 0 means "automatic" and
 * lets the system choose the rate/format. Automatic input preset prefers the
 * native VoicePerformance path, with VoiceRecognition compatibility fallback.
 */
data class DeviceSettings(
    val rate: Int = 0,
    val format: Int = 0,
    val preset: Int = 0,
    val exclusive: Boolean = false,
    /** -1 unknown, 0 not granted, 1 exclusive opens succeed. */
    val exclusiveSupport: Int = -1,
) {
    fun copyOf(rate: Int = this.rate, format: Int = this.format, preset: Int = this.preset,
               exclusive: Boolean = this.exclusive, exclusiveSupport: Int = this.exclusiveSupport) =
        DeviceSettings(rate, format, preset, exclusive, exclusiveSupport)

    companion object {
        fun formatName(value: Int): String = when (value) {
            1 -> "PCM 16 位"
            2 -> "PCM Float 32 位"
            3 -> "PCM 24 位"
            4 -> "PCM 32 位"
            else -> "自动"
        }

        /** Candidate sample rates; the device's declared list when available. */
        fun rates(device: AudioDeviceInfo): IntArray {
            val values = sortedSetOf(0)
            val declared = device.sampleRates
            val list = if (declared.isEmpty())
                intArrayOf(8000, 16000, 32000, 44100, 48000, 88200, 96000, 192000) else declared
            for (n in list) if (n > 0) values.add(n)
            return values.toIntArray()
        }

        /** Candidate PCM encodings; the device's declared list when available. */
        fun formats(device: AudioDeviceInfo): IntArray {
            val values = sortedSetOf(0)
            val declared = device.encodings
            if (declared.isEmpty()) return intArrayOf(0, 1, 2, 3, 4)
            for (n in declared) {
                // Map Android encoding constants onto the app's internal codes.
                when (n) {
                    2 -> values.add(1)  // ENCODING_PCM_16BIT
                    4 -> values.add(2)  // ENCODING_PCM_FLOAT
                    21 -> values.add(3) // ENCODING_PCM_24BIT_PACKED
                    22 -> values.add(4) // ENCODING_PCM_32BIT
                }
            }
            return values.toIntArray()
        }

        val PRESET_VALUES = intArrayOf(0, 6, 9, 10)
        val PRESET_LABELS = arrayOf("自动", "VOICE_RECOGNITION", "UNPROCESSED", "VOICE_PERFORMANCE")
    }
}
