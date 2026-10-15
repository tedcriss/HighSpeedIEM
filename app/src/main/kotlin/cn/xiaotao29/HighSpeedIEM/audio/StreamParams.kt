package cn.xiaotao29.HighSpeedIEM.audio

/**
 * Per-device AAudio open-stream request parameters edited in the params
 * dialog. Zero / false values mean "let AAudio decide" (AAUDIO_FORMAT_UNSPECIFIED,
 * channelCount 0, sampleRate 0, bufferCapacity 0, SHARED).
 */
data class StreamParams(
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val format: Int = AAudio.FORMAT_UNSPECIFIED,
    val exclusive: Boolean = false,
    val performanceMode: Int = AAudio.PERFORMANCE_LOW_LATENCY,
    // Input only.
    val inputPreset: Int = AAudio.PRESET_GENERIC,
    // Output only.
    val usage: Int = AAudio.USAGE_MEDIA,
    val contentType: Int = AAudio.CONTENT_MUSIC,
    val bufferCapacity: Int = 0,
) {
    companion object {
        val DEFAULT = StreamParams()
    }
}
