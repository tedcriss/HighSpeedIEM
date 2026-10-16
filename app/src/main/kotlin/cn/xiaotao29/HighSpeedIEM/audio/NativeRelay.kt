package cn.xiaotao29.HighSpeedIEM.audio

import androidx.annotation.Keep

/**
 * JNI bridge to the native AAudio relay engine (librelay). All calls are
 * blocking; callers must run them off the main thread except
 * [nativeAttachListener] and [nativeSetMixer], which are cheap.
 */
object NativeRelay {

    /** Receives [onNativeError] on an arbitrary native thread. */
    interface Listener {
        @Keep
        fun onNativeError(text: String)
    }

    init {
        System.loadLibrary("relay")
    }

    /**
     * Flat int config, order fixed with jni_bridge.cpp:
     * in: deviceId, channelCount, sampleRate, format, exclusive, performanceMode,
     *     inputPreset, bufferCapacity, bufferSizeBursts
     * out: deviceId, channelCount, sampleRate, format, exclusive, performanceMode,
     *     usage, contentType, bufferCapacity, bufferSizeBursts, autoBufferSize,
     *     readThresholdBursts, readThresholdExtraFrames,
     *     inputBacklogThresholdFrames, inMMapEnabled, outMMapEnabled
     * Buffer/threshold/MMAP entries come from the session-level RelayParams,
     * the rest from the per-device StreamParams.
     */
    external fun nativeStart(config: IntArray, mixerMasks: IntArray): String?

    /** Idempotent; returns the first close error text, or null on success. */
    external fun nativeStop(): String?

    /** Live mixer update; mask bit i routes input channel i into the output channel. */
    external fun nativeSetMixer(outChannel: Int, mask: Int)

    /** Flat key/value pairs for one direction (0 = output, 1 = input); null when closed. */
    external fun nativeStreamInfo(direction: Int): Array<String>?

    /**
     * Runtime backlog-resync episode count since the last start. Cheap atomic
     * read; polled from a non-realtime thread to surface snackbar notices.
     */
    external fun nativeResyncEvents(): Int

    external fun nativeAttachListener(listener: Listener?)
}
