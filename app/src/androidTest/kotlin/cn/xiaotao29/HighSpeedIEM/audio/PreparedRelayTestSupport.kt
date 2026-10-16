package cn.xiaotao29.HighSpeedIEM.audio

/** Tests use the same prepare -> geometry -> front-end normalize -> start contract. */
internal fun startTestRelay(config: IntArray, masks: IntArray): String? {
    val error = NativeRelay.nativePrepare(config, masks)
    if (error != null) return error
    fun info(d: Int) = NativeRelay.nativeStreamInfo(d).orEmpty().asList().chunked(2).associate { it[0] to it[1] }
    val p = RelayParams(inputReadThresholdBursts = config[ConfigField.READ_BURSTS.ordinal], inputReadThresholdExtraFrames = config[ConfigField.READ_EXTRA.ordinal], inputBacklogThresholdFrames = config[ConfigField.BACKLOG.ordinal])
    val final = RelayParamRules.normalize(p, RelayParamRules.ChangedField.GEOMETRY,
        info(0)["FramesPerBurst"]?.toIntOrNull(), info(1)["BufferSizeInFrames"]?.toIntOrNull()).params
    return NativeRelay.nativeStartPrepared(intArrayOf(final.inputReadThresholdBursts, final.inputReadThresholdExtraFrames, final.inputBacklogThresholdFrames))
}
