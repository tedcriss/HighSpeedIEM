package cn.xiaotao29.HighSpeedIEM.audio

import androidx.annotation.Keep

object NativeRelay {
    @Keep
    interface Listener {
        @Keep fun onNativeError(sessionId: Long, text: String)
        @Keep fun onNativeEvent(sessionId: Long, sequence: Long, type: Int, value1: Long, value2: Long)
    }
    init { System.loadLibrary("relay") }
    /** 24 integers: input[9], output[10], thresholds[3], MMAP[2]. */
    external fun nativePrepare(config: IntArray, mixerMasks: IntArray): String?
    /** Final front-end normalized [bursts, extraFrames, backlogFrames]. */
    external fun nativeStartPrepared(thresholds: IntArray): String?
    external fun nativeSessionId(): Long
    external fun nativeStop(): String?
    external fun nativeSetMixer(outChannel: Int, mask: Int)
    external fun nativeStreamInfo(direction: Int): Array<String>?
    external fun nativeAttachListener(listener: Listener?)
}
