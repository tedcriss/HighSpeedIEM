package cn.xiaotao29.HighSpeedIEM.audio

/**
 * JNI bridge to the native Oboe engine. All functions are safe to call from any
 * thread; the native side serialises control operations internally.
 *
 * Route spec layout (int per endpoint, 14 values per route):
 * [deviceId, channelIndex, channelCount, sampleRate, format, exclusive, inputPreset] x2
 * deviceId -1 denotes "absent" (used by preview requests and test adapters).
 */
object NativeAudio {
    val loadError: String? = try {
        System.loadLibrary("highspeediem")
        null
    } catch (e: LinkageError) {
        e.toString()
    }

    /** Opens all routes at once; returns an empty string on success. */
    external fun startConfigured(routes: IntArray): String

    /** Stops and releases every stream held by the engine. */
    external fun stop()

    /** Returns and clears a fatal engine error, releasing streams first. */
    external fun pollError(): String

    /** Actual opened-stream parameters for one device endpoint. */
    external fun endpointStats(deviceId: Int, input: Boolean): String

    /** Probes exclusive-mode support: 1 supported, 0 denied, -1 unknown. */
    external fun exclusiveSupport(deviceId: Int, input: Boolean, channels: Int): Int

    /** Warms AAudio function pointers; returns the stream channel count. */
    external fun probe(deviceId: Int, input: Boolean): Int

    /**
     * Opens and closes a device without recording. Returns [channels, error].
     */
    external fun checkDevice(deviceId: Int, input: Boolean, channels: Int): Array<String>

    /** Spectrum of an input channel: [sampleRate, dBFS bins...]; empty when idle. */
    external fun spectrum(deviceId: Int, channel: Int): FloatArray

    /** Starts one monitoring (preview) endpoint inside an otherwise idle engine. */
    external fun monitorConfigured(endpoint: IntArray): String

    /** Writes mono samples to one output channel for ringtone testing. */
    external fun writeTest(deviceId: Int, channel: Int, mono: FloatArray, offset: Int, frames: Int): Int

    /** Ringtone frames pending in the software ring or the device playback buffer. */
    external fun testPending(deviceId: Int): Int

    /** Actual sample rate of an output endpoint, 0 when absent. */
    external fun outputRate(deviceId: Int): Int

    /** Verifies the lock-free ring and PCM conversions without audio devices. */
    external fun selfTest(): String

    /** Forces a raw engine error code so the failure path can be exercised. */
    external fun injectError(code: Int)
}
