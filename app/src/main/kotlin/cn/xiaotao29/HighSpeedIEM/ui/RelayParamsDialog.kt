package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
 * Large inset dialog editing the session-level [RelayParams]: input/output
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
        it to "$it"
    }
    // Zero frames and 2^0 = 1 frame are distinct options.
    val extraFrameOptions = (listOf(0) + (0..10).map { 1 shl it }).map {
        it to "$it"
    }
    fun backlogLabel(value: Int) = when (value) {
        -1 -> "自动（持续积压恢复）"
        0 -> "关闭（0）"
        else -> "$value"
    }
    val backlogOptions = RelayParamRules.backlogs
        .map { it to backlogLabel(it) }
    val phaseOptions = RelayParams.PHASE_COUNT_OPTIONS.map { it to "$it" }

    ParameterDialog(title = "转发参数设置", onDismissRequest = onDismiss) {
        ParamCheckboxRow(
            label = "自动重启",
            checked = params.autoRestart,
            onCheckedChange = { update(params.copy(autoRestart = it)) },
            enabled = !session.operationsBlocked,
        )
        SectionNote("如遇报错，等待 500 ms 后重试")
        HorizontalDivider()
        SectionHeader("输入流")
        ParamDropdown(
            label = "BufferCapacity",
            selectedLabel = if (params.inBufferCapacity == 0) {
                "自动（0）"
            } else {
                "${params.inBufferCapacity}"
            },
            options = capacityOptions,
            onSelect = { update(params.copy(inBufferCapacity = it)) },
        )
        ParamDropdown(
            label = "BufferSize",
            selectedLabel = if (params.inBufferSizeBursts == 0) {
                "系统默认（0）"
            } else {
                "${params.inBufferSizeBursts} × FramesPerBurst"
            },
            options = sizeOptions,
            onSelect = { update(params.copy(inBufferSizeBursts = it)) },
        )
        ParamCheckboxRow(
            label = "尝试输入 MMAP",
            checked = params.inMMapEnabled,
            onCheckedChange = { update(params.copy(inMMapEnabled = it)) },
        )
        SectionNote("使用 MMAP 可提高内存性能")
        HorizontalDivider()
        SectionHeader("输出流")
        ParamDropdown(
            label = "BufferCapacity",
            selectedLabel = if (params.outBufferCapacity == 0) {
                "自动（0）"
            } else {
                "${params.outBufferCapacity}"
            },
            options = capacityOptions,
            onSelect = { update(params.copy(outBufferCapacity = it)) },
        )
        ParamDropdown(
            label = "BufferSize",
            selectedLabel = if (params.outBufferSizeBursts == 0) "系统默认（0）" else "${params.outBufferSizeBursts} × FramesPerBurst",
            options = sizeOptions,
            onSelect = { update(params.copy(outBufferSizeBursts = it)) },
        )
        ParamCheckboxRow(
            label = "尝试输出 MMAP",
            checked = params.outMMapEnabled,
            onCheckedChange = { update(params.copy(outMMapEnabled = it)) },
        )
        SectionNote("使用 MMAP 可提高内存性能")
        HorizontalDivider()
        SectionHeader("输入读取门槛")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ParamDropdown(
                label = "输出 burst 倍数",
                selectedLabel = "${params.inputReadThresholdBursts}",
                options = thresholdBurstOptions,
                onSelect = { update(params.copy(inputReadThresholdBursts = it)) },
                modifier = Modifier.weight(1f),
            )
            ParamDropdown(
                label = "附加帧数",
                selectedLabel = "${params.inputReadThresholdExtraFrames}",
                options = extraFrameOptions,
                onSelect = {
                    update(params.copy(inputReadThresholdExtraFrames = it))
                },
                modifier = Modifier.weight(1f),
            )
        }
        SectionNote("读取输入缓冲的最低帧数门槛 = 倍数 × 输出 burst + 附加帧数")
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
                label = "DRAIN 稳定次数",
                selectedLabel = "${params.drainStableThreshold}",
                options = phaseOptions,
                onSelect = { update(params.copy(drainStableThreshold = it)) },
                enabled = !session.operationsBlocked,
                modifier = Modifier.weight(1f),
            )
            ParamDropdown(
                label = "DRAIN 结束次数",
                selectedLabel = "${params.drainMaxExecutions}",
                options = phaseOptions,
                onSelect = { update(params.copy(drainMaxExecutions = it)) },
                enabled = !session.operationsBlocked,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        SectionHeader("DISCARD 同步阶段")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ParamDropdown(
                label = "DISCARD 稳定次数",
                selectedLabel = "${params.discardStableThreshold}",
                options = phaseOptions,
                onSelect = { update(params.copy(discardStableThreshold = it)) },
                enabled = !session.operationsBlocked,
                modifier = Modifier.weight(1f),
            )
            ParamDropdown(
                label = "DISCARD 结束次数",
                selectedLabel = "${params.discardMaxExecutions}",
                options = phaseOptions,
                onSelect = { update(params.copy(discardMaxExecutions = it)) },
                enabled = !session.operationsBlocked,
                modifier = Modifier.weight(1f),
            )
        }
        session.parameterAdjustment?.let { SectionNote(it) }
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
    enabled: Boolean = true,
) {
    Row(
        modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
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
