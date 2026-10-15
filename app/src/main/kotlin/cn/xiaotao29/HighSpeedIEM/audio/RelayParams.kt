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
    val outBufferSizeBursts: Int = 0,
    /**
     * true = existing automatic output sizing (initial 2×burst in low-latency
     * mode plus adaptive growth on xruns) and [outBufferSizeBursts] is ignored;
     * false = manual sizing and no adaptive retuning.
     */
    val outAutoBufferSize: Boolean = true,
    /**
     * Input read threshold F = [inputReadThresholdBursts] × actual output
     * FramesPerBurst + [inputReadThresholdExtraFrames]: the minimum frames
     * available on the input before the output callback reads. Both 0 = gate
     * off. Not a read size and not a post-read remainder.
     */
    val inputReadThresholdBursts: Int = 0,
    val inputReadThresholdExtraFrames: Int = 0,
) {
    companion object {
        val DEFAULT = RelayParams()
    }
}
