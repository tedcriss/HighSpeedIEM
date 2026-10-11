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
    data class Event(val sessionId: Long, val settingsChanged: Boolean, val error: String)
    data class EndpointSnapshot(
        val sessionId: Long,
        val config: String,
        val settings: String,
        val telemetry: String,
        val revision: Long = 0,
    ) {
        fun display() = listOf(config, settings, telemetry).filter { it.isNotEmpty() }.joinToString("\n")
    }

    @Volatile var eventListener: ((Event) -> Unit)? = null
    private data class ObservedState(val id: Long = 0, val error: String = "")
    private val observed = java.util.concurrent.atomic.AtomicReference(ObservedState())
    val currentSessionId: Long get() = observed.get().id
    val currentError: String get() = observed.get().error
    private var observer: Thread? = null
    val loadError: String? = try {
        System.loadLibrary("highspeediem")
        null
    } catch (e: LinkageError) {
        e.toString()
    }

    /** Opens all routes at once; returns an empty string on success. */
    // 0: original stable policy; 1: minimum input/two output bursts;
    // 2: minimum input/one output burst, with bounded glitch recovery.
    @Synchronized
    fun startConfigured(routes: IntArray, latencyMode: Int = 2): String {
        observed.set(ObservedState())
        val error = startNative(routes, latencyMode)
        finishObserver()
        if (error.isNotEmpty()) return error
        val id = sessionId()
        observed.set(ObservedState(id))
        if (id != 0L) {
            observer = Thread({
                try {
                    while (currentSessionId == id) {
                        val event = awaitEvent(id)
                        if (event.isEmpty() || currentSessionId != id) break
                        observed.updateAndGet { if (it.id == id && event[1].isNotEmpty()) it.copy(error = event[1]) else it }
                        eventListener?.invoke(Event(id, event[0].toInt() and 2 != 0, event[1]))
                    }
                } catch (e: Exception) {
                    if (currentSessionId == id) {
                        observed.updateAndGet { if (it.id == id) it.copy(error = e.toString()) else it }
                        eventListener?.invoke(Event(id, false, e.toString()))
                    }
                } catch (e: LinkageError) {
                    if (currentSessionId == id) {
                        observed.updateAndGet { if (it.id == id) it.copy(error = e.toString()) else it }
                        eventListener?.invoke(Event(id, false, e.toString()))
                    }
                }
            }, "IEM-native-events").apply { isDaemon = true; start() }
        }
        return ""
    }
    private external fun startNative(routes: IntArray, latencyMode: Int): String
    private external fun sessionId(): Long
    private external fun awaitEvent(sessionId: Long): Array<String>

    private fun finishObserver() {
        observer?.join(5000)
        check(observer?.isAlive != true) { "java.util.concurrent.TimeoutException: native event dispatcher exit" }
        observer = null
    }

    /** Stops and releases every stream held by the engine. */
    @Synchronized
    fun stop() {
        observed.set(ObservedState())
        stopNative() // closes the engine and wakes the blocking waiter
        finishObserver()
    }
    private external fun stopNative()

    /** Low-frequency progress watchdog. Errors are delivered by the event bridge. */
    external fun checkHealth()
    external fun endpointPart(deviceId: Int, input: Boolean, part: Int): String

    /** Diagnostic compatibility reader; production uses events and checkHealth. */
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
    external fun awaitTestSpace(deviceId: Int): String
    external fun cancelTestWaiters()

    /** Ringtone frames pending in the software ring or the device playback buffer. */
    external fun testPending(deviceId: Int): Int

    /** Actual sample rate of an output endpoint, 0 when absent. */
    external fun outputRate(deviceId: Int): Int

    /** Verifies the lock-free ring and PCM conversions without audio devices. */
    external fun selfTest(): String

    /** Forces a raw engine error code so the failure path can be exercised. */
    external fun injectError(code: Int)
}
