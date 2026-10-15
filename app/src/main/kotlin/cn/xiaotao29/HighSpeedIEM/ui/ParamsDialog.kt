package cn.xiaotao29.HighSpeedIEM.ui

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import cn.xiaotao29.HighSpeedIEM.audio.StreamParams

/**
 * Full-screen dialog editing the AAudio open-stream request parameters of one
 * device. Changes apply to the session immediately; they take effect the next
 * time forwarding starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamParamsDialog(
    session: RelaySession,
    input: Boolean,
    device: AudioDeviceInfo,
    onDismiss: () -> Unit,
) {
    var params by remember(device.id) { mutableStateOf(session.paramsFor(input, device.id)) }
    fun update(next: StreamParams) {
        params = next
        session.setParams(input, device.id, next)
    }

    val channelOptions = listOf(0 to "自动（0）") +
        device.channelCounts.distinct().sorted().map { it to "$it" }
    val rateOptions = listOf(0 to "自动（0）") +
        device.sampleRates.distinct().sorted().map { it to "$it Hz" }
    val formatOptions = listOf(AAudio.FORMAT_UNSPECIFIED to "AAUDIO_FORMAT_UNSPECIFIED（自动）") +
        device.encodings.toList().mapNotNull { encoding ->
            AAudio.formatFromEncoding(encoding)?.let { it to AAudio.formatName(it) }
        }.distinctBy { it.first }
    val performanceOptions = AAudio.PERFORMANCE_MODES.map { it to AAudio.performanceName(it) }
    val presetOptions = AAudio.INPUT_PRESETS.map { it to AAudio.presetName(it) }
    val usageOptions = AAudio.USAGES.map { it to AAudio.usageName(it) }
    val contentTypeOptions = AAudio.CONTENT_TYPES.map { it to AAudio.contentTypeName(it) }
    val bufferOptions = AAudio.BUFFER_CAPACITIES.map { it to if (it == 0) "自动（0）" else "$it" }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarqueeText(
                        if (input) "输入设备参数 · ID ${device.id}" else "输出设备参数 · ID ${device.id}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭参数设置")
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
                    ParamDropdown(
                        label = "ChannelCount（通道数）",
                        selectedLabel = if (params.channelCount == 0) "自动（0）" else "${params.channelCount}",
                        options = channelOptions,
                        onSelect = { update(params.copy(channelCount = it)) },
                    )
                    ParamDropdown(
                        label = "SampleRate（采样率）",
                        selectedLabel = if (params.sampleRate == 0) "自动（0）" else "${params.sampleRate} Hz",
                        options = rateOptions,
                        onSelect = { update(params.copy(sampleRate = it)) },
                    )
                    ParamDropdown(
                        label = "Format（采样格式）",
                        selectedLabel = AAudio.formatName(params.format),
                        options = formatOptions,
                        onSelect = { update(params.copy(format = it)) },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = params.exclusive,
                                role = Role.Checkbox,
                                onValueChange = { update(params.copy(exclusive = it)) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = params.exclusive, onCheckedChange = null)
                        MarqueeText(
                            "Exclusive（AAUDIO_SHARING_MODE_EXCLUSIVE）",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    ParamDropdown(
                        label = "PerformanceMode（性能模式）",
                        selectedLabel = AAudio.performanceName(params.performanceMode),
                        options = performanceOptions,
                        onSelect = { update(params.copy(performanceMode = it)) },
                    )
                    if (input) {
                        ParamDropdown(
                            label = "InputPreset（输入预设）",
                            selectedLabel = AAudio.presetName(params.inputPreset),
                            options = presetOptions,
                            onSelect = { update(params.copy(inputPreset = it)) },
                        )
                    }
                    if (!input && Build.VERSION.SDK_INT >= 28) {
                        ParamDropdown(
                            label = "Usage（用途）",
                            selectedLabel = AAudio.usageName(params.usage),
                            options = usageOptions,
                            onSelect = { update(params.copy(usage = it)) },
                        )
                        ParamDropdown(
                            label = "ContentType（内容类型）",
                            selectedLabel = AAudio.contentTypeName(params.contentType),
                            options = contentTypeOptions,
                            onSelect = { update(params.copy(contentType = it)) },
                        )
                    }
                    ParamDropdown(
                        label = "BufferCapacityInFrames（缓冲容量）",
                        selectedLabel = if (params.bufferCapacity == 0) "自动（0）" else "${params.bufferCapacity}",
                        options = bufferOptions,
                        onSelect = { update(params.copy(bufferCapacity = it)) },
                    )
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
