package cn.xiaotao29.HighSpeedIEM.audio

import kotlin.math.abs

/** Pure rules shared by controls, device changes, and the prepared geometry. */
object RelayParamRules {
    enum class ChangedField { BURSTS, EXTRA, BACKLOG, GEOMETRY }
    data class Result(val params: RelayParams, val reason: String? = null)
    val extras = listOf(0) + (0..10).map { 1 shl it }
    val backlogs = listOf(0) + (1..10).map { it * 100 } + (2..10).map { it * 1000 }

    fun normalize(p: RelayParams, changed: ChangedField, burst: Int? = null, inputSize: Int? = null): Result {
        if (p.inputBacklogThresholdFrames == 0) return Result(p)
        fun floor(b: Int, e: Int): Long? {
            if (b > 0 && (burst == null || burst <= 0)) return null
            val raw = b.toLong() * (burst ?: 0) + e
            return if (inputSize != null && inputSize > 0) minOf(raw, inputSize.toLong()) else raw
        }
        val f = floor(p.inputReadThresholdBursts, p.inputReadThresholdExtraFrames)
        if (f != null && p.inputBacklogThresholdFrames > f) return Result(p)
        val next = if (changed == ChangedField.BACKLOG) {
            var found: RelayParams? = null
            for (b in p.inputReadThresholdBursts downTo 0) {
                val extra = extras.filter { it <= p.inputReadThresholdExtraFrames &&
                    floor(b, it)?.let { v -> v < p.inputBacklogThresholdFrames } == true }
                    .minWithOrNull(compareBy<Int> { abs(it - p.inputReadThresholdExtraFrames) }.thenBy { it })
                if (extra != null) { found = p.copy(inputReadThresholdBursts = b, inputReadThresholdExtraFrames = extra); break }
            }
            found ?: p.copy(inputReadThresholdBursts = 0, inputReadThresholdExtraFrames = 0)
        } else p.copy(inputBacklogThresholdFrames = if (f == null) 0 else backlogs.firstOrNull { it > f } ?: 0)
        return Result(next, if (next == p) null else if (changed == ChangedField.BACKLOG)
            "已调整输入读取门槛，使其低于积压阈值" else "已调整输入积压阈值，使其高于读取门槛；未知 burst 或无有效选项时关闭积压判断")
    }

    fun positiveRates(raw: IntArray) = raw.filter { it > 0 }.distinct().sorted()
    fun inputRates(raw: IntArray): List<Int> = positiveRates(raw).let {
        if (it.isEmpty()) listOf(SYNC_CHOICE) else listOf(SYNC_CHOICE, 0) + it
    }
    fun outputRates(raw: IntArray, selectedInputRaw: IntArray?, sync: Boolean): List<Int> {
        val own = positiveRates(raw)
        if (own.isNotEmpty()) return (if (sync) emptyList() else listOf(0)) + own
        if (selectedInputRaw == null) return emptyList()
        return positiveRates(selectedInputRaw).ifEmpty { listOf(48000) }
    }
    // A UI option only; never serialized into an AAudio config.
    const val SYNC_CHOICE = -1
}
