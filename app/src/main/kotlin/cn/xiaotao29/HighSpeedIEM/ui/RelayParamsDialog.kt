package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.RelayParamRules
import cn.xiaotao29.HighSpeedIEM.audio.RelayParams
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/**
 * Full-screen dialog editing the session-level [RelayParams]: input/output
 * buffer capacity and size plus the input read threshold. The parameters are
 * device-independent and survive device switches; changes apply to the session
 * immediately and take effect the next time forwarding starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayParamsDialog(session: RelaySession, onDismiss: () -> Unit) {
    val params = session.relayParams
    fun update(next: RelayParams) { session.updateRelayParams(next) }

    val capacityOptions = AAudio.BUFFER_CAPACITIES.map { it to if (it == 0) "自动（0）" else "$it" }
    val sizeOptions = (0..16).map {
        it to if (it == 0) "系统默认（0）" else "$it × FramesPerBurst"
    }
    val thresholdBurstOptions = (0..10).map {
        it to if (it == 0) "0（不加倍数）" else "$it × 输出 FramesPerBurst"
    }
    // "0 帧" and "2^0 = 1 帧" are distinct options.
    val extraFrameOptions = (listOf(0) + (0..10).map { 1 shl it }).map {
        it to if (it == 0) "0 帧" else "$it 帧"
    }
    fun backlogLabel(value: Int) = when (value) {
        -1 -> "自动（持续积压恢复）"
        0 -> "关闭（0）"
        else -> "$value"
    }
    val backlogOptions = RelayParamRules.backlogs
        .map { it to backlogLabel(it) }
    val phaseOptions = RelayParams.PHASE_COUNT_OPTIONS.map { it to "$it" }

    ImmersiveDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(safeContentInsets())) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarqueeText(
                        "转发参数设置",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭转发参数设置")
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionHeader("输入流")
                    ParamDropdown(
                        label = "BufferCapacityInFrames（输入缓冲容量）",
                        selectedLabel = if (params.inBufferCapacity == 0) {
                            "自动（0）"
                        } else {
                            "${params.inBufferCapacity}"
                        },
                        options = capacityOptions,
                        onSelect = { update(params.copy(inBufferCapacity = it)) },
                    )
                    ParamDropdown(
                        label = "BufferSizeInFrames（输入缓冲大小）",
                        selectedLabel = if (params.inBufferSizeBursts == 0) {
                            "系统默认（0）"
                        } else {
                            "${params.inBufferSizeBursts} × FramesPerBurst"
                        },
                        options = sizeOptions,
                        onSelect = { update(params.copy(inBufferSizeBursts = it)) },
                    )
                    SectionNote(
                        "0＝保留开流后的系统默认缓冲大小；N＝请求 N × 该流实际 " +
                            "FramesPerBurst，且不超过实际 BufferCapacityInFrames。输入侧按后端" +
                            "实际支持情况生效，不构成保证有效的输入延迟控制。",
                    )
                    ParamCheckboxRow(
                        label = "允许 MMAP（输入）",
                        checked = params.inMMapEnabled,
                        onCheckedChange = { update(params.copy(inMMapEnabled = it)) },
                    )
                    SectionNote(
                        "开启＝遵循系统 MMAP 策略，系统允许时使用 MMAP；" +
                            "关闭＝请求 AAUDIO_POLICY_NEVER（需要系统提供策略接口，" +
                            "无法兑现时启动将明确报错）。实际是否使用 MMAP 以实时流参数中的 " +
                            "MMapUsed 为准，开关状态不代表实际结果。",
                    )
                    HorizontalDivider()
                    SectionHeader("输出流")
                    ParamDropdown(
                        label = "BufferCapacityInFrames（输出缓冲容量）",
                        selectedLabel = if (params.outBufferCapacity == 0) {
                            "自动（0）"
                        } else {
                            "${params.outBufferCapacity}"
                        },
                        options = capacityOptions,
                        onSelect = { update(params.copy(outBufferCapacity = it)) },
                    )
                    ParamDropdown(
                        label = "BufferSizeInFrames（输出缓冲大小）",
                        selectedLabel = if (params.outBufferSizeBursts == 0) "系统默认（0）" else "${params.outBufferSizeBursts} × FramesPerBurst",
                        options = sizeOptions,
                        onSelect = { update(params.copy(outBufferSizeBursts = it)) },
                    )
                    SectionNote("默认 2 × 实际 FramesPerBurst，裁剪到实际容量；0 保留系统默认。运行期间不自动调整。")
                    ParamCheckboxRow(
                        label = "允许 MMAP（输出）",
                        checked = params.outMMapEnabled,
                        onCheckedChange = { update(params.copy(outMMapEnabled = it)) },
                    )
                    SectionNote(
                        "含义与输入侧相同：开启＝遵循系统策略，关闭＝禁止 MMAP。" +
                            "策略为进程级，引擎在分别打开输出、输入流之前串行设置各自策略，" +
                            "结束后恢复原策略。",
                    )
                    HorizontalDivider()
                    SectionHeader("输入读取门槛")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ParamDropdown(
                            label = "门槛倍数（× 输出 burst）",
                            selectedLabel = if (params.inputReadThresholdBursts == 0) {
                                "0（不加倍数）"
                            } else {
                                "${params.inputReadThresholdBursts} × 输出 FramesPerBurst"
                            },
                            options = thresholdBurstOptions,
                            onSelect = { update(params.copy(inputReadThresholdBursts = it)) },
                            modifier = Modifier.weight(1f),
                        )
                        ParamDropdown(
                            label = "附加帧数",
                            selectedLabel = if (params.inputReadThresholdExtraFrames == 0) {
                                "0 帧"
                            } else {
                                "${params.inputReadThresholdExtraFrames} 帧"
                            },
                            options = extraFrameOptions,
                            onSelect = {
                                update(params.copy(inputReadThresholdExtraFrames = it))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SectionNote(
                        "读取前的最低可用帧数 F＝倍数 × 实际输出 FramesPerBurst ＋ 附加帧数，" +
                            "并与输入流实际 BufferSizeInFrames 取较小值。转发回调中输入可用帧数" +
                            "达到 F 才读取；F 不是每次读取的数量，也不是读取后必须剩余的帧数。" +
                            "两项均为 0 时不做门槛判断。",
                    )
                    HorizontalDivider()
                    SectionHeader("输入积压恢复")
                    ParamDropdown(
                        label = "输入高积压阈值（帧）",
                        selectedLabel = backlogLabel(params.inputBacklogThresholdFrames),
                        options = backlogOptions,
                        onSelect = { update(params.copy(inputBacklogThresholdFrames = it)) },
                    )
                    SectionNote(
                        "默认自动：按实际输入、输出缓冲节奏和读取门槛保留余量，" +
                            "持续积压约 1 秒后清空、同步，再开始正常转发。" +
                            "正数超限立即恢复，且必须严格高于有效读取门槛；0 关闭。允许反复恢复，期间输出短暂静音。" +
                            "可在实时输入参数查看当前阈值与恢复次数。",
                    )
                    HorizontalDivider()
                    SectionHeader("DRAIN 清空阶段")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ParamDropdown(
                            label = "DRAIN 稳定阈值（次）",
                            selectedLabel = "${params.drainStableThreshold}",
                            options = phaseOptions,
                            onSelect = { update(params.copy(drainStableThreshold = it)) },
                            enabled = !session.operationsBlocked,
                            modifier = Modifier.weight(1f),
                        )
                        ParamDropdown(
                            label = "DRAIN 强制退出次数",
                            selectedLabel = "${params.drainMaxExecutions}",
                            options = phaseOptions,
                            onSelect = { update(params.copy(drainMaxExecutions = it)) },
                            enabled = !session.operationsBlocked,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SectionNote("连续达到稳定阈值后退出，不稳定则复位；达到强制退出次数时退出。默认 7 / 200。")
                    HorizontalDivider()
                    SectionHeader("DISCARD 同步阶段")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ParamDropdown(
                            label = "DISCARD 稳定阈值（次）",
                            selectedLabel = "${params.discardStableThreshold}",
                            options = phaseOptions,
                            onSelect = { update(params.copy(discardStableThreshold = it)) },
                            enabled = !session.operationsBlocked,
                            modifier = Modifier.weight(1f),
                        )
                        ParamDropdown(
                            label = "DISCARD 强制退出次数",
                            selectedLabel = "${params.discardMaxExecutions}",
                            options = phaseOptions,
                            onSelect = { update(params.copy(discardMaxExecutions = it)) },
                            enabled = !session.operationsBlocked,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SectionNote("连续达到稳定阈值后退出，不稳定则复位；达到强制退出次数时退出。默认 7 / 200。")
                    SectionNote("每个阶段的强制退出次数必须大于或等于稳定阈值，否则启动转发时将报错。")
                    session.parameterAdjustment?.let { SectionNote(it) }
                    HorizontalDivider()
                    Text(
                        "参数在下次启动转发时生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    MarqueeText(title, style = MaterialTheme.typography.titleMedium)
}

/** The app's shared checkbox row: native Checkbox inside a toggleable row. */
@Composable
private fun ParamCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        MarqueeText(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
