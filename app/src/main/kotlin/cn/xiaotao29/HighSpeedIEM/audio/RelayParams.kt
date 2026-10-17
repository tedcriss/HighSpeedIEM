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
    val outBufferSizeBursts: Int = 2,
    /**
     * Input read threshold F = [inputReadThresholdBursts] × actual output
     * FramesPerBurst + [inputReadThresholdExtraFrames]: the minimum frames
     * available on the input before the output callback reads. Both 0 = gate
     * off. Not a read size and not a post-read remainder.
     */
    val inputReadThresholdBursts: Int = 0,
    val inputReadThresholdExtraFrames: Int = 0,
    /** -1 = automatic sustained recovery; 0 = off; positive = immediate DRAIN threshold. */
    val inputBacklogThresholdFrames: Int = -1,
    /** Consecutive stable callbacks / maximum callbacks for each recovery phase. */
    val drainStableThreshold: Int = 7,
    val drainMaxExecutions: Int = 200,
    val discardStableThreshold: Int = 7,
    val discardMaxExecutions: Int = 200,
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
    /** Checked at start, without silently changing the user's selections. */
    fun phaseStartError(): String? {
        for ((phase, stable, maximum) in listOf(
            Triple("DRAIN", drainStableThreshold, drainMaxExecutions),
            Triple("DISCARD", discardStableThreshold, discardMaxExecutions),
        )) {
            if (stable !in PHASE_COUNT_OPTIONS || maximum !in PHASE_COUNT_OPTIONS)
                return "转发已阻止：$phase 退出参数不在允许的选项范围内"
            if (maximum < stable)
                return "转发已阻止：$phase 强制退出次数（$maximum）不能小于稳定阈值（$stable）"
        }
        return null
    }

    /** Final start-only parameters; order matches RelayEngine::startPrepared. */
    internal fun startThresholds() = intArrayOf(
        inputReadThresholdBursts, inputReadThresholdExtraFrames, inputBacklogThresholdFrames,
        drainStableThreshold, drainMaxExecutions, discardStableThreshold, discardMaxExecutions,
    )

    companion object {
        val PHASE_COUNT_OPTIONS = (1..9).toList() + (10..100 step 5) + (200..2000 step 100)
        val DEFAULT = RelayParams()
    }
}
