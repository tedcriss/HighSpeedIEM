package cn.xiaotao29.HighSpeedIEM.audio

/**
 * Per-device AAudio open-stream request parameters edited in the params
 * dialog. Zero geometry / format values let AAudio decide. Exclusive sharing
 * is requested by default; AAudio may fall back to shared mode. Buffer capacity/size moved to the
 * session-level [RelayParams]; they are device-independent.
 */
data class StreamParams(
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val sampleRateMode: SampleRateMode = SampleRateMode.AUTO,
    val format: Int = AAudio.FORMAT_UNSPECIFIED,
    val exclusive: Boolean = true,
    val performanceMode: Int = AAudio.PERFORMANCE_LOW_LATENCY,
    // Input only.
    val inputPreset: Int = AAudio.PRESET_GENERIC,
    // Output only.
    val usage: Int = AAudio.USAGE_MEDIA,
    val contentType: Int = AAudio.CONTENT_MUSIC,
) {
    companion object {
        fun defaultFor(input: Boolean) = StreamParams(
            sampleRateMode = if (input) SampleRateMode.SYNC_OUTPUT else SampleRateMode.AUTO,
        )
    }
}
