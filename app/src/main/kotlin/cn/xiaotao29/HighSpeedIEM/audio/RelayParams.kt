package cn.xiaotao29.HighSpeedIEM.audio

/**
 * Session-level relay parameters edited in the relay params dialog. Unlike
 * [StreamParams] these are independent of the selected devices and survive
 * device switches; changes take effect the next time forwarding starts.
 * Zero values mean "system default" (no builder/setter call).
 */
data class RelayParams(
    /** Input AAudioStreamBuilder_setBufferCapacityInFrames request; 0 = system default. */
    val inBufferCapacity: Int = 0,
    /** Output AAudioStreamBuilder_setBufferCapacityInFrames request; 0 = system default. */
    val outBufferCapacity: Int = 0,
    /**
     * Input buffer size as a multiple of the stream's actual FramesPerBurst;
     * 0 = keep the system default size (setter not called). Best effort: the
     * backend decides what is actually adopted.
     */
    val inBufferSizeBursts: Int = 0,
    /** Output buffer size as a multiple of FramesPerBurst; 0 = setter not called. */
    val outBufferSizeBursts: Int = 4,
    /**
     * Input read threshold F = [inputReadThresholdBursts] × actual output
     * FramesPerBurst + [inputReadThresholdExtraFrames]: the minimum frames
     * available on the input before the output callback reads. Both 0 = gate
     * off. Not a read size and not a post-read remainder.
     */
    val inputReadThresholdBursts: Int = 0,
    val inputReadThresholdExtraFrames: Int = 0,
    /** Above this backlog return to DRAIN. 0 disables the check. */
    val inputBacklogThresholdFrames: Int = 0,
    /**
     * true = preserve the platform MMAP policy for the input (system may use MMAP
     * and falls back by capability); false = request AAUDIO_POLICY_NEVER.
     * AAUDIO_POLICY_ALWAYS is never used. Independent of per-device
     * exclusive sharing mode; the realtime MMapUsed value shows the actual
     * result, not this request.
     */
    val inMMapEnabled: Boolean = true,
    /** Output counterpart of [inMMapEnabled]. */
    val outMMapEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT = RelayParams()
    }
}
