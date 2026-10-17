package cn.xiaotao29.HighSpeedIEM.ui

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import cn.xiaotao29.HighSpeedIEM.audio.StreamParams

/**
 * Large inset dialog editing the AAudio open-stream request parameters of one
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
    onMenu: (() -> Unit)? = null,
    requestInitialFocus: Boolean = false,
) {
    val params = session.paramsFor(input, device.id)
    fun update(next: StreamParams) { session.setParams(input, device.id, next) }

    val channelOptions = listOf(0 to "自动（0）") +
        device.channelCounts.distinct().sorted().map { it to "$it" }
    val rateOptions = session.sampleRateOptions(input, device.id).map {
        it to when (it) { -1 -> "同步输出采样率"; 0 -> "自动（0）"; else -> "$it Hz" }
    }
    val formatOptions = listOf(AAudio.FORMAT_UNSPECIFIED to "${AAudio.formatName(AAudio.FORMAT_UNSPECIFIED)}（自动）") +
        device.encodings.toList().mapNotNull { encoding ->
            AAudio.formatFromEncoding(encoding)?.let { it to AAudio.formatName(it) }
        }.distinctBy { it.first }
    val performanceOptions = AAudio.PERFORMANCE_MODES.map { it to AAudio.performanceName(it) }
    val presetOptions = AAudio.INPUT_PRESETS.map { it to AAudio.presetName(it) }
    val usageOptions = AAudio.USAGES.map { it to AAudio.usageName(it) }
    val contentTypeOptions = AAudio.CONTENT_TYPES.map { it to AAudio.contentTypeName(it) }

    ParameterDialog(
        title = if (input) "输入设备参数 · ID ${device.id}" else "输出设备参数 · ID ${device.id}",
        onDismissRequest = onDismiss,
        onMenu = onMenu,
    ) {
        val initialFocus = remember { FocusRequester() }
        LaunchedEffect(requestInitialFocus) {
            if (requestInitialFocus) initialFocus.requestFocus()
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ParamDropdown(
                modifier = Modifier.weight(1f).focusRequester(initialFocus),
                onMenu = onMenu,
                label = "ChannelCount",
                selectedLabel = if (params.channelCount == 0) "自动（0）" else "${params.channelCount}",
                options = channelOptions,
                onSelect = { update(params.copy(channelCount = it)) },
            )
            ParamDropdown(
                onMenu = onMenu,
                modifier = Modifier.weight(1f),
                label = "SampleRate",
                selectedLabel = when {
                    params.sampleRateMode == cn.xiaotao29.HighSpeedIEM.audio.SampleRateMode.SYNC_OUTPUT -> "同步输出采样率"
                    rateOptions.isEmpty() -> "请选择输入设备 / 暂无可用采样率"
                    params.sampleRate == 0 -> "自动（0）"
                    else -> "${params.sampleRate} Hz"
                },
                options = rateOptions,
                onSelect = { update(params.copy(sampleRate = maxOf(0, it), sampleRateMode = when (it) {
                    -1 -> cn.xiaotao29.HighSpeedIEM.audio.SampleRateMode.SYNC_OUTPUT
                    0 -> cn.xiaotao29.HighSpeedIEM.audio.SampleRateMode.AUTO
                    else -> cn.xiaotao29.HighSpeedIEM.audio.SampleRateMode.EXPLICIT
                })) },
            )
        }
        ParamDropdown(
            onMenu = onMenu,
            label = "Format",
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
                "Exclusive",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        ParamDropdown(
            onMenu = onMenu,
            label = "PerformanceMode",
            selectedLabel = AAudio.performanceName(params.performanceMode),
            options = performanceOptions,
            onSelect = { update(params.copy(performanceMode = it)) },
        )
        if (input) {
            ParamDropdown(
                onMenu = onMenu,
                label = "InputPreset",
                selectedLabel = AAudio.presetName(params.inputPreset),
                options = presetOptions,
                onSelect = { update(params.copy(inputPreset = it)) },
            )
        }
        if (!input && Build.VERSION.SDK_INT >= 28) {
            ParamDropdown(
                onMenu = onMenu,
                label = "Usage",
                selectedLabel = AAudio.usageName(params.usage),
                options = usageOptions,
                onSelect = { update(params.copy(usage = it)) },
            )
            ParamDropdown(
                onMenu = onMenu,
                label = "ContentType",
                selectedLabel = AAudio.contentTypeName(params.contentType),
                options = contentTypeOptions,
                onSelect = { update(params.copy(contentType = it)) },
            )
        }
    }
}
