package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.DeviceSettings
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private data class ChannelPageState(val channels: List<Channel>, val enabledCount: Int)
private data class DeviceCardState(
    val selected: Boolean, val settings: DeviceSettings, val locked: Boolean,
    val selectionLocked: Boolean, val enabled: Boolean, val loading: Boolean,
)

// Subscribe in the scope that consumes mutable session fields. sessionState
// filters unchanged snapshots while preserving this requirement under strong
// skipping; an unused forwarded version parameter cannot make children current.

/**
 * Input tab: channel cards (Filled), capability details, per-device parameter
 * dropdowns, and a spectrum tooltip anchored to the selected channel.
 */
@Composable
fun InputPage(activity: MainActivity, session: MonitorSession, landscape: Boolean = false) {
    val page by sessionState(session) {
        ChannelPageState(session.inputs.values.toList(), session.inputs.keys.count { it !in session.disabled })
    }
    val channels = page.channels
    ChannelList(channels, landscape, Modifier.fillMaxSize(), tag = "input-channel-list",
        itemKey = { it.key }, eagerPortrait = true, heading = {
            PageHeading("输入通道", "选择通道查看频谱，勾选后可用于转发。",
                "${page.enabledCount} / ${channels.size} 已启用")
        },
    ) { _, channel, _ -> DeviceCard(activity, session, channel) }
}

/**
 * Output tab: selecting a card plays the ringtone; clicking it again cancels.
 */
@Composable
fun OutputPage(activity: MainActivity, session: MonitorSession, landscape: Boolean = false) {
    val page by sessionState(session) {
        ChannelPageState(session.outputs.values.toList(), session.outputs.keys.count { it !in session.disabled })
    }
    val channels = page.channels
    ChannelList(channels, landscape, Modifier.fillMaxSize(), tag = "output-channel-list",
        itemKey = { it.key }, eagerPortrait = true, heading = {
            PageHeading("输出通道", "选择通道自动播放铃声，再次点击可停止；勾选后可用于转发。",
                "${page.enabledCount} / ${channels.size} 已启用")
        },
    ) { _, channel, _ -> DeviceCard(activity, session, channel) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeviceCard(
    activity: MainActivity,
    session: MonitorSession,
    channel: Channel,
) {
    val state by sessionState(session) {
        DeviceCardState(
            (if (channel.input) session.selectedInput else session.selectedOutput) == channel.key,
            session.config(channel), session.forwardingLockedPublic,
            if (channel.input) session.forwardingLockedPublic else session.outputSelectionLockedPublic,
            channel.key !in session.disabled, session.loadingPublic,
        )
    }
    val selected = state.selected
    val cfg = state.settings
    val locked = state.locked
    val selectionLocked = state.selectionLocked
    val enabled = state.enabled
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "channel-selection-container",
    )
    val contentColor by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurface,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "channel-selection-content",
    )

    val cardShape = MaterialTheme.shapes.large
    val cardFocus = remember { FocusRequester() }
    var cardBounds by remember { mutableStateOf<IntRect?>(null) }
    SpectrumTooltip(session, channel, cardFocus, cardBounds) {
        Card(
            enabled = !selectionLocked,
            onClick = {
                activity.safe {
                    // Check the live session as well as the composed enabled state.
                    if (!(if (channel.input) session.forwardingLockedPublic else session.outputSelectionLockedPublic)) {
                        if (channel.input) {
                            session.selectInput(channel.key)
                            if (session.selectedInput != null) activity.previewPermissions(channel)
                            else session.updatePreview()
                        } else {
                            session.selectOutput(channel.key)
                            if (session.selectedOutput != null) {
                                if (activity.isBluetooth(channel)) activity.permissionTestFor(channel)
                                else session.ringtone()
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .onGloballyPositioned { cardBounds = it.boundsInWindow().roundToIntRect() }
                .focusRequester(cardFocus)
                .keyboardFocus(!selectionLocked, cardShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "ID ${channel.device.id}，通道 ${channel.index}，${channel.device.productName}，" +
                        if (channel.input) "查看实时频谱" else "播放或停止测试铃声"
                    stateDescription = if (selected) "测试中" else "未测试"
                    this.selected = selected
                },
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
                // A resource lock disables interaction, not channel visibility.
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor,
            ),
            shape = cardShape,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Icon(if (channel.input) Icons.Filled.Mic else Icons.Filled.Speaker,
                            contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                    Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "ID ${channel.device.id} · 通道 ${channel.index}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${deviceTypeLabel(channel)} · ${channel.device.productName}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { on -> activity.safe { session.enable(channel, on) } },
                        enabled = !locked,
                        modifier = Modifier.keyboardFocus(!locked).semantics {
                            contentDescription = "允许 ID ${channel.device.id} 通道 ${channel.index} 用于转发"
                        },
                    )
                }
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    DeviceCapabilityTooltip(channel, cardBounds, enabled = !state.loading,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "独占",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (cfg.exclusiveSupport == 1) MaterialTheme.colorScheme.onSurface
                            else ActionStyle.disabledContent,
                        )
                        Checkbox(
                            checked = cfg.exclusiveSupport == 1 && cfg.exclusive,
                            onCheckedChange = { on ->
                                activity.safe {
                                    session.updateSettings(channel) { it.copyOf(exclusive = on) }
                                    session.parameterChanged(channel)
                                }
                            },
                            enabled = cfg.exclusiveSupport == 1 && channel.index == 0 && !locked,
                            modifier = Modifier.keyboardFocus(cfg.exclusiveSupport == 1 && channel.index == 0 && !locked).semantics {
                                contentDescription = "ID ${channel.device.id} 通道 ${channel.index}，" + if (cfg.exclusiveSupport == 1)
                                    "请求设备独占，实际结果取决于系统；由通道 0 设置"
                                else "系统未提供可用独占模式"
                            },
                        )
                    }
                }
                ParameterRow(activity, session, channel, cfg, locked)
            }
        }
    }
}

/** Parameters editable on channel 0 only; sibling channels show inherited values. */
@Composable
private fun ParameterRow(
    activity: MainActivity,
    session: MonitorSession,
    channel: Channel,
    cfg: DeviceSettings,
    locked: Boolean,
) {
    val editable = channel.index == 0 && !locked
    val rates = remember(channel.device) { DeviceSettings.rates(channel.device) }
    val rateLabels = remember(rates) { rates.map { if (it == 0) "自动" else "$it Hz" } }
    val formats = remember(channel.device) { DeviceSettings.formats(channel.device) }
    val formatLabels = remember(formats) { formats.map { DeviceSettings.formatName(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            ParameterFields(stacked = maxWidth < 280.dp * fontScale) { fieldModifier ->
                OptionDropdown(
                    label = "采样率",
                    options = rateLabels,
                    selectedIndex = rates.indexOf(cfg.rate).coerceAtLeast(0),
                    enabled = editable,
                    hint = "ID ${channel.device.id} 通道 ${channel.index}，采样率；自动由系统选择，同一设备由通道 0 设置",
                    modifier = fieldModifier,
                ) { index ->
                    activity.safe {
                        session.updateSettings(channel) { it.copyOf(rate = rates[index]) }
                        session.parameterChanged(channel)
                    }
                }
                OptionDropdown(
                    label = "采样深度",
                    options = formatLabels,
                    selectedIndex = formats.indexOf(cfg.format).coerceAtLeast(0),
                    enabled = editable,
                    hint = "ID ${channel.device.id} 通道 ${channel.index}，采样深度；自动由系统选择，同一设备由通道 0 设置",
                    modifier = fieldModifier,
                ) { index ->
                    activity.safe {
                        session.updateSettings(channel) { it.copyOf(format = formats[index]) }
                        session.parameterChanged(channel)
                    }
                }
            }
        }
        if (channel.input) {
            OptionDropdown(
                label = "录音预设",
                options = remember { DeviceSettings.PRESET_LABELS.toList() },
                selectedIndex = DeviceSettings.PRESET_VALUES.indexOf(cfg.preset).coerceAtLeast(0),
                enabled = editable,
                hint = "ID ${channel.device.id} 通道 ${channel.index}，录音预设；自动优先系统实时返声预设，不支持时使用语音识别预设，同一设备由通道 0 设置",
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                activity.safe {
                    session.updateSettings(channel) { it.copyOf(preset = DeviceSettings.PRESET_VALUES[index]) }
                    session.parameterChanged(channel)
                }
            }
        }
    }
}

@Composable
private fun ParameterFields(stacked: Boolean, content: @Composable (Modifier) -> Unit) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content(Modifier.fillMaxWidth()) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content(Modifier.weight(1f)) }
    }
}

/**
 * Native exposed dropdown: an outlined text field anchor with the label
 * floating on the outline (the outline notch around the label is drawn by
 * the control itself), and a popup of selectable items. The current value is
 * highlighted with a filled background; long item text wraps, and an
 * over-long selected value scrolls inside the native single-line anchor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OptionDropdown(
    label: String?,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    selectedValue: AnnotatedString? = null,
    optionText: @Composable (String) -> Unit = {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    },
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchorFocus = remember { FocusRequester() }
    // Menu-only focus targets are created with the popup, not for every closed anchor.
    val inputMode = LocalInputModeManager.current
    var restoreFocus by remember { mutableStateOf(false) }
    fun dismiss() {
        expanded = false
        restoreFocus = inputMode.inputMode == InputMode.Keyboard
    }
    val value = selectedValue?.text ?: options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" }
    // Native presentation-only styling keeps the text, selection and scrolling unchanged.
    val valueStyling = remember(selectedValue) {
        selectedValue?.let { styled ->
            OutputTransformation {
                styled.spanStyles.forEach { range ->
                    addStyle(range.item, range.start, range.end)
                }
            }
        }
    }
    val textState = remember(value) {
        TextFieldState(initialText = value, initialSelection = TextRange.Zero)
    }
    val valueScroll = remember(value) { ScrollState(0) }
    var anchorFocused by remember { mutableStateOf(false) }
    val scrollSpeed = with(LocalDensity.current) { 30.dp.toPx() }
    // Scroll only the native text viewport; the outline, label and arrow stay fixed.
    // A changed value/width restarts at the beginning, and opening the menu pauses it.
    LaunchedEffect(value, valueScroll.maxValue, expanded, scrollSpeed, anchorFocused) {
        val motionScale = coroutineContext[MotionDurationScale]
        valueScroll.scrollTo(0)
        val overflow = valueScroll.maxValue
        // Only the user's focused value needs marquee motion. Unfocused/disabled
        // anchors stay readable at the start instead of animating the entire page.
        if (!anchorFocused || expanded || overflow <= 0 || overflow == Int.MAX_VALUE) return@LaunchedEffect
        val duration = (overflow / scrollSpeed * 1000).roundToInt().coerceAtLeast(500)
        // Prime the framework getter outside snapshotFlow's read-only snapshot.
        // Android Compose exposes its native animation-scale observer as state.
        val initialScale = motionScale?.scaleFactor ?: 1f
        androidx.compose.runtime.snapshotFlow { motionScale?.scaleFactor ?: initialScale }
            .collectLatest { scale ->
                valueScroll.scrollTo(0)
                if (scale == 0f) return@collectLatest
                while (isActive) {
                    delay(1500)
                    valueScroll.animateScrollTo(overflow, tween(duration, easing = LinearEasing))
                    delay(1500)
                    valueScroll.animateScrollTo(0, tween(duration, easing = LinearEasing))
                }
            }
    }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enabled, expanded, restoreFocus) {
        if (!enabled) {
            expanded = false
            restoreFocus = false
        } else if (!expanded && restoreFocus) {
            androidx.compose.runtime.withFrameNanos { }
            anchorFocus.requestFocus()
            restoreFocus = false
        } else if (!expanded && inputMode.inputMode == InputMode.Touch) {
            focusManager.clearFocus()
        }
    }
    // Hoisted out of the item loop: theme reads once per composition, not per
    // item, so opening the menu stays cheap.
    // Use the same rounded rectangle for ordinary and selected item fills.
    val itemShape = MenuDefaults.selectedItemShape
    val itemShapes = MenuDefaults.itemShapes(shape = itemShape, selectedShape = itemShape)
    val itemColors = MenuDefaults.selectableItemColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it && enabled },
        modifier = modifier,
    ) {
        OutlinedTextField(
            state = textState,
            readOnly = true,
            enabled = enabled,
            // Null label: tables and rows that name the parameter outside the field.
            label = label?.let { text -> { Text(text, style = MaterialTheme.typography.labelMedium) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodyMedium,
            outputTransformation = valueStyling,
            shape = MaterialTheme.shapes.small,
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                focusedBorderColor = if (expanded) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            lineLimits = TextFieldLineLimits.SingleLine,
            scrollState = valueScroll,
            modifier = Modifier
                .focusRequester(anchorFocus)
                .onFocusChanged { anchorFocused = it.isFocused }
                .keyboardFocus(enabled, MaterialTheme.shapes.small)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
                .semantics {
                    contentDescription = hint
                    stateDescription = (if (value.isBlank()) "未选择" else value) +
                        if (expanded) "，已展开" else "，已收起"
                },
        )
        ExposedDropdownMenu(expanded = expanded,
            matchAnchorWidth = false,
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).passiveScroll(),
            onDismissRequest = {
            dismiss()
        }) {
            val itemFocus = remember(options.size) { List(options.size) { FocusRequester() } }
            LaunchedEffect(expanded) {
                if (expanded && itemFocus.isNotEmpty()) {
                    androidx.compose.runtime.withFrameNanos { }
                    itemFocus[selectedIndex.coerceIn(itemFocus.indices)].requestFocus()
                }
            }
            options.forEachIndexed { index, option ->
                val chosen = index == selectedIndex
                SelectableDropdownMenuItem(
                    selected = chosen,
                    onClick = {
                        dismiss()
                        if (enabled) onSelected(index)
                    },
                    text = {
                        optionText(option)
                    },
                    shapes = itemShapes,
                    colors = itemColors,
                    modifier = Modifier.focusRequester(itemFocus[index])
                        .focusProperties {
                            next = itemFocus[(index + 1) % itemFocus.size]
                            previous = itemFocus[(index - 1 + itemFocus.size) % itemFocus.size]
                        }
                        .keyboardFocus().semantics {
                        if (option.isBlank() && label != null) contentDescription = "空白，清除$label"
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Presentation labels only; device identity and audio configuration stay unchanged. */
internal fun deviceTypeLabel(channel: Channel): String = channel.typeLabel

internal fun channelLabel(channel: Channel): String =
    "ID ${channel.device.id} · 通道 ${channel.index}\n${deviceTypeLabel(channel)} · ${channel.device.productName}"
