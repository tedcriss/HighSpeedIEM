package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceCapabilityTooltip(channel: Channel) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val leftInset = insets.getLeft(density, LayoutDirection.Ltr)
    val rightInset = insets.getRight(density, LayoutDirection.Ltr)
    val topInset = insets.getTop(density)
    val bottomInset = insets.getBottom(density)
    val edgeMargin = with(density) { 8.dp.roundToPx() }
    val maxWidth = with(density) {
        ((windowSize.width - leftInset - rightInset).toDp() - 32.dp).coerceAtLeast(48.dp)
    }
    val maxContentHeight = with(density) {
        (windowSize.height - topInset - bottomInset).toDp() * 0.65f
    }
    val nativePosition = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below)
    // Keep the native anchor positioning while avoiding system bars and display cutouts.
    val positionProvider = remember(nativePosition, leftInset, rightInset, topInset, bottomInset, edgeMargin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val position = nativePosition.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
                val minX = leftInset + edgeMargin
                val minY = topInset + edgeMargin
                return IntOffset(
                    position.x.coerceIn(minX, maxOf(minX, windowSize.width - rightInset - edgeMargin - popupContentSize.width)),
                    position.y.coerceIn(minY, maxOf(minY, windowSize.height - bottomInset - edgeMargin - popupContentSize.height)),
                )
            }
        }
    }

    TooltipBox(
        positionProvider = positionProvider,
        state = state,
        focusable = true,
        onDismissRequest = { state.dismiss() },
        tooltip = {
            RichTooltip(
                caretShape = TooltipDefaults.caretShape(),
                maxWidth = maxWidth,
            ) {
                CapabilityTable(
                    entries = channel.capabilities,
                    modifier = Modifier
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
    ) {
        TextButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.semantics { contentDescription = "查看设备能力" },
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
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(value.ifEmpty { "（空）" }, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal)
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
