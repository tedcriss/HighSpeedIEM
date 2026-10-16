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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
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
    var params by remember { mutableStateOf(session.relayParams) }
    fun update(next: RelayParams) {
        params = next
        session.updateRelayParams(next)
    }

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
    val backlogOptions = (listOf(0) + (1..10).map { it * 100 } + (2..10).map { it * 1000 })
        .map { it to if (it == 0) "关闭（0）" else "$it" }

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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ParamDropdown(
                            label = "BufferSizeInFrames（输出缓冲大小）",
                            selectedLabel = if (params.outBufferSizeBursts == 0) {
                                "系统默认（0）"
                            } else {
                                "${params.outBufferSizeBursts} × FramesPerBurst"
                            },
                            options = sizeOptions,
                            enabled = !params.outAutoBufferSize,
                            onSelect = { update(params.copy(outBufferSizeBursts = it)) },
                            modifier = Modifier.weight(1f),
                        )
                        ParamCheckboxRow(
                            label = "程序自动设置",
                            checked = params.outAutoBufferSize,
                            onCheckedChange = { update(params.copy(outAutoBufferSize = it)) },
                        )
                    }
                    SectionNote(
                        "勾选“程序自动设置”：沿用低延迟模式下 2 × FramesPerBurst 的初始" +
                            "缓冲并在欠载时自适应增大，输出缓冲大小下拉框禁用。取消勾选：采用" +
                            "手动配置（0＝不调用 setter，保留系统默认），并禁用自动调节。",
                    )
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
                        selectedLabel = if (params.inputBacklogThresholdFrames == 0) {
                            "关闭（0）"
                        } else {
                            "${params.inputBacklogThresholdFrames}"
                        },
                        options = backlogOptions,
                        onSelect = { update(params.copy(inputBacklogThresholdFrames = it)) },
                    )
                    SectionNote(
                        "转发中输入可用帧数超过该阈值时，触发一次有上限的重新同步：以非阻塞" +
                            "读取丢弃旧数据，恢复到阈值一半以下再继续转发，期间允许短暂静音。" +
                            "带迟滞与冷却，同一次恢复只提示一次 Snackbar，不报错、不停止转发。" +
                            "关闭（0）＝完全禁用运行期间恢复，启动排空不受影响。该阈值独立于" +
                            "输入读取门槛，不受输入缓冲大小裁剪。",
                    )
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
