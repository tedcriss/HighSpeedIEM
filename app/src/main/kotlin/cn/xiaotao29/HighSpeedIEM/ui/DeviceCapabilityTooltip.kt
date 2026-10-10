package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceCapabilityTooltip(channel: Channel, cardBounds: IntRect?, enabled: Boolean = true) {
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
            AdaptiveRichTooltip(
                layout = layout,
                title = "ID ${channel.device.id} · 通道 ${channel.index} · 设备能力",
                tag = "capability-tooltip",
                closeFocus = closeFocus,
                onClose = { state.dismiss() },
            ) {
                CapabilityTable(entries = channel.capabilities)
            }
            LaunchedEffect(Unit) {
                if (inputMode.inputMode == InputMode.Keyboard) closeFocus.requestFocus()
            }
        },
    ) {
        TextButton(
            enabled = enabled,
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.focusRequester(anchorFocus).keyboardFocus(enabled)
                .semantics { contentDescription = "查看 ID ${channel.device.id} 通道 ${channel.index} 的设备能力" },
        ) {
            Text("设备能力", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** A shared column width keeps every row aligned, including multiline values. */
@Composable
private fun CapabilityTable(entries: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            entries.forEach { (name, value) ->
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {})
                Text(value.ifEmpty { "（空）" }, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "$name，${value.ifEmpty { "空" }}" })
            }
        },
    ) { measurables, constraints ->
        val columnGap = 16.dp.roundToPx()
        val rowGap = 8.dp.roundToPx()
        val rows = measurables.chunked(2)
        val naturalLabelWidth = rows.maxOfOrNull { it[0].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val naturalValueWidth = rows.maxOfOrNull { it[1].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        // Reserve at least half of the available content width for values.
        val labelWidth = naturalLabelWidth.coerceAtMost((constraints.maxWidth - columnGap).coerceAtLeast(0) / 2)
        val width = constraints.constrainWidth(labelWidth + columnGap + naturalValueWidth)
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
