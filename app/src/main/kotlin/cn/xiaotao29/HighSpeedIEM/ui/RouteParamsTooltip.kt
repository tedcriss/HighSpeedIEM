package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.DeviceSettings
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import kotlinx.coroutines.launch

/** Pinned output buffer range in bursts; 0 keeps the adaptive latency tuner. */
internal val BUFFER_BURSTS_MAX = 32

private val LATENCY_POLICY_LABELS = listOf("0", "1", "2")
private val LATENCY_POLICY_NOTES = listOf(
    "3.1 baseline",
    "zero input guard / two output bursts",
    "minimum latency (default)",
)

/**
 * Optional forwarding parameters of one pair, edited before a start. Same
 * persistent-tooltip pattern as the route info entry; the body is a two-column
 * table of raw backend parameter names and native Material 3 controls.
 * Values live in the shared per-device settings and the session policy, so
 * every view of a channel (device pages, other pairs) stays consistent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteParamsTooltip(
    activity: MainActivity,
    session: MonitorSession,
    label: String,
    input: Channel,
    output: Channel,
    cardBounds: IntRect?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val anchorFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isVisible, enabled) {
        if (!enabled) state.dismiss()
        if (state.isVisible) wasVisible = true
        else if (wasVisible && enabled) {
            wasVisible = false
            if (inputMode.inputMode == InputMode.Keyboard) anchorFocus.requestFocus()
        }
    }
    val layout = rememberCardTooltipLayout(cardBounds, 480.dp)

    TooltipBox(
        positionProvider = layout.position,
        state = state,
        focusable = true,
        enableUserInput = false,
        onDismissRequest = { state.dismiss() },
        tooltip = {
            @Suppress("UNUSED_VARIABLE")
            val sessionVersion = session.version.collectAsState().value
            AdaptiveRichTooltip(
                layout = layout,
                title = "$label · 转发参数",
                tag = "route-params-tooltip",
                closeFocus = closeFocus,
                onClose = { state.dismiss() },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ParamsSection("输入 · ID ${input.device.id} · 通道 ${input.index}") {
                        EndpointParams(activity, session, input, enabled)
                    }
                    ParamsSection("输出 · ID ${output.device.id} · 通道 ${output.index}") {
                        EndpointParams(activity, session, output, enabled)
                    }
                    ParamsSection("转发") {
                        ParamsTable(listOf("latencyMode" to {
                            OptionDropdown(
                                label = null,
                                options = LATENCY_POLICY_LABELS,
                                selectedIndex = session.latencyPolicy,
                                enabled = enabled,
                                hint = "latencyMode，转发延迟策略，作用于下一次启动的全部转发对",
                                modifier = Modifier.fillMaxWidth(),
                                optionText = { option ->
                                    val note = LATENCY_POLICY_NOTES.getOrNull(option.toIntOrNull() ?: 0).orEmpty()
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(option, style = MaterialTheme.typography.bodyMedium)
                                        Text(note, style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Normal)
                                    }
                                },
                            ) { index ->
                                activity.safe { session.setLatencyPolicy(index) }
                            }
                        }))
                    }
                }
            }
            LaunchedEffect(Unit) {
                if (inputMode.inputMode == InputMode.Keyboard) closeFocus.requestFocus()
            }
        },
    ) {
        TextButton(
            enabled = enabled,
            onClick = { scope.launch { state.show() } },
            modifier = modifier.focusRequester(anchorFocus).keyboardFocus(enabled)
                .semantics { contentDescription = "设置${label}的转发参数" },
        ) {
            Text("转发参数", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** Table cells fill the measured value column; a plain fillMaxWidth would fight the custom Layout. */
private fun Modifier.fillMaxWidth(): Modifier = this.fillMaxWidth()

@Composable
private fun ParamsSection(header: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(header, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

/** One endpoint's stream parameters: enums as native dropdowns, exclusive as a checkbox. */
@Composable
private fun EndpointParams(
    activity: MainActivity,
    session: MonitorSession,
    channel: Channel,
    enabled: Boolean,
) {
    val cfg = session.config(channel)
    // Sibling channels inherit channel 0's device settings, like the device pages.
    val editable = enabled && channel.index == 0
    val identity = "ID ${channel.device.id} 通道 ${channel.index}"
    val rates = remember(channel.device) { DeviceSettings.rates(channel.device) }
    val rateLabels = remember(rates) { rates.map { if (it == 0) "自动" else "$it Hz" } }
    val formats = remember(channel.device) { DeviceSettings.formats(channel.device) }
    val formatLabels = remember(formats) { formats.map { DeviceSettings.formatName(it) } }
    val entries = ArrayList<Pair<String, @Composable () -> Unit>>()
    entries += "rate" to {
        OptionDropdown(
            label = null,
            options = rateLabels,
            selectedIndex = rates.indexOf(cfg.rate).coerceAtLeast(0),
            enabled = editable,
            hint = "$identity，rate，采样率；自动由系统选择，同一设备由通道 0 设置",
            modifier = Modifier.fillMaxWidth(),
        ) { index ->
            activity.safe {
                session.updateSettings(channel) { it.copyOf(rate = rates[index]) }
                session.parameterChanged(channel)
            }
        }
    }
    entries += "format" to {
        OptionDropdown(
            label = null,
            options = formatLabels,
            selectedIndex = formats.indexOf(cfg.format).coerceAtLeast(0),
            enabled = editable,
            hint = "$identity，format，采样深度；自动由系统选择，同一设备由通道 0 设置",
            modifier = Modifier.fillMaxWidth(),
        ) { index ->
            activity.safe {
                session.updateSettings(channel) { it.copyOf(format = formats[index]) }
                session.parameterChanged(channel)
            }
        }
    }
    if (channel.input) {
        entries += "preset" to {
            OptionDropdown(
                label = null,
                options = DeviceSettings.PRESET_LABELS.toList(),
                selectedIndex = DeviceSettings.PRESET_VALUES.indexOf(cfg.preset).coerceAtLeast(0),
                enabled = editable,
                hint = "$identity，preset，录音预设；自动优先系统实时返声预设，不支持时使用语音识别预设，同一设备由通道 0 设置",
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                activity.safe {
                    session.updateSettings(channel) { it.copyOf(preset = DeviceSettings.PRESET_VALUES[index]) }
                    session.parameterChanged(channel)
                }
            }
        }
    }
    entries += "exclusive" to {
        Checkbox(
            checked = cfg.exclusiveSupport == 1 && cfg.exclusive,
            onCheckedChange = { on ->
                activity.safe {
                    session.updateSettings(channel) { it.copyOf(exclusive = on) }
                    session.parameterChanged(channel)
                }
            },
            enabled = editable && cfg.exclusiveSupport == 1,
            modifier = Modifier.keyboardFocus(editable && cfg.exclusiveSupport == 1).semantics {
                contentDescription = "$identity，exclusive，请求设备独占，实际结果取决于系统；同一设备由通道 0 设置"
            },
        )
    }
    if (!channel.input) {
        entries += "bufferBursts" to { BufferBurstsField(activity, session, channel, cfg, editable, identity) }
    }
    ParamsTable(entries)
}

/** Digits-only field clamped to the engine's pinned-buffer range; 0 keeps adaptive tuning. */
@Composable
private fun BufferBurstsField(
    activity: MainActivity,
    session: MonitorSession,
    channel: Channel,
    cfg: DeviceSettings,
    enabled: Boolean,
    identity: String,
) {
    var text by remember { mutableStateOf(cfg.buffer.toString()) }
    var focused by remember { mutableStateOf(false) }
    // Outside editing the field mirrors the committed value, including other views' edits.
    LaunchedEffect(focused, cfg.buffer) { if (!focused) text = cfg.buffer.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            val value = digits.toIntOrNull()
            // Show the clamped value immediately so display and backend agree.
            text = if (value == null) digits else value.coerceIn(0, BUFFER_BURSTS_MAX).toString()
            if (value == null) return@OutlinedTextField
            activity.safe {
                session.updateSettings(channel) {
                    it.copyOf(buffer = value.coerceIn(0, BUFFER_BURSTS_MAX))
                }
                session.parameterChanged(channel)
            }
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = "$identity，bufferBursts，输出缓冲突发数，0 为自适应，范围 0 至 $BUFFER_BURSTS_MAX"
            },
    )
}

/**
 * Two-column parameter table: raw names left, controls right. Same aligned
 * label column as the capability table, with the value column measured for
 * whatever native control each row hosts.
 */
@Composable
private fun ParamsTable(entries: List<Pair<String, @Composable () -> Unit>>) {
    Layout(
        content = {
            entries.forEach { (name, cell) ->
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {})
                cell()
            }
        },
    ) { measurables, constraints ->
        val columnGap = 16.dp.roundToPx()
        val rowGap = 12.dp.roundToPx()
        val rows = measurables.chunked(2)
        val naturalLabelWidth = rows.maxOfOrNull { it[0].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        // Reserve at least half of the available content width for controls.
        val half = if (constraints.hasBoundedWidth) (constraints.maxWidth - columnGap).coerceAtLeast(0) / 2
                   else Int.MAX_VALUE / 4
        val labelWidth = naturalLabelWidth.coerceAtMost(half)
        val naturalValueWidth = rows.maxOfOrNull { it[1].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.constrainWidth(constraints.maxWidth)
                    else constraints.constrainWidth(labelWidth + columnGap + naturalValueWidth)
        val valueWidth = (width - labelWidth - columnGap).coerceAtLeast(0)
        val cells = rows.map { row ->
            row[0].measure(Constraints(maxWidth = labelWidth)) to
                row[1].measure(Constraints(maxWidth = valueWidth))
        }
        val height = cells.sumOf { (label, value) -> maxOf(label.height, value.height) } +
            rowGap * (cells.size - 1).coerceAtLeast(0)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            cells.forEach { (label, value) ->
                label.placeRelative(0, y)
                value.placeRelative(labelWidth + columnGap, y)
                y += maxOf(label.height, value.height) + rowGap
            }
        }
    }
}
