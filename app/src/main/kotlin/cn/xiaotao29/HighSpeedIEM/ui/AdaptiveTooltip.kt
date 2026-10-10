package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

/** The Scaffold's measured content area excludes both system and app navigation. */
internal val LocalTooltipViewport = staticCompositionLocalOf<IntRect?> { null }

/** Four anchor directions, ranked by how much of the visible card they cover. */
internal class CardTooltipPositionProvider(
    private val viewport: IntRect,
    private val card: IntRect?,
    private val gap: Int,
    private val preferSide: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val target = (card ?: anchorBounds).intersect(viewport)
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = target.left + (target.width - w) / 2
        val y = target.top + (target.height - h) / 2
        val start = IntOffset(target.left - gap - w, y)
        val end = IntOffset(target.right + gap, y)
        val sides = if (layoutDirection == LayoutDirection.Ltr) listOf(end, start) else listOf(start, end)
        val vertical = listOf(IntOffset(x, target.top - gap - h), IntOffset(x, target.bottom + gap))
        val maxX = maxOf(viewport.left, viewport.right - w)
        val maxY = maxOf(viewport.top, viewport.bottom - h)
        // When neither axis has enough room, a corner can expose more of the
        // card than an edge-centred popup. Keep ordinary anchor directions first.
        val corners = listOf(IntOffset(viewport.left, viewport.top), IntOffset(maxX, viewport.top),
            IntOffset(viewport.left, maxY), IntOffset(maxX, maxY))
        val candidates = (if (preferSide) sides + vertical else vertical + sides) + corners
        return candidates.map { offset ->
            IntOffset(
                offset.x.coerceIn(viewport.left, maxX),
                offset.y.coerceIn(viewport.top, maxY),
            )
        }.minBy { offset ->
            val overlap = target.intersect(IntRect(offset, popupContentSize))
            overlap.width.coerceAtLeast(0).toLong() * overlap.height.coerceAtLeast(0)
        }
    }
}

internal data class CardTooltipLayout(val position: PopupPositionProvider, val width: Dp, val height: Dp)

@Composable
internal fun rememberCardTooltipLayout(card: IntRect?, preferredWidth: Dp): CardTooltipLayout {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    val margin = with(density) { 8.dp.roundToPx() }
    val systemSafe = IntRect(
        insets.getLeft(density, LayoutDirection.Ltr), insets.getTop(density),
        window.width - insets.getRight(density, LayoutDirection.Ltr),
        window.height - insets.getBottom(density),
    )
    val content = LocalTooltipViewport.current?.intersect(systemSafe) ?: systemSafe
    val safe = IntRect(content.left + margin, content.top + margin,
        maxOf(content.left + margin, content.right - margin),
        maxOf(content.top + margin, content.bottom - margin))
    val landscape = window.width > window.height
    val position = remember(safe, card, margin, landscape) {
        CardTooltipPositionProvider(safe, card, margin, landscape)
    }
    val width = with(density) {
        val sideSpace = card?.let { maxOf(it.left - safe.left - margin, safe.right - it.right - margin) } ?: 0
        val available = when {
            !landscape -> safe.width
            sideSpace >= 240.dp.roundToPx() -> sideSpace
            // A single wide card can occupy nearly the entire short window.
            // A narrower, scrollable tooltip leaves at least half its width exposed.
            else -> minOf(safe.width, (card?.width ?: safe.width) / 2)
        }
        minOf(preferredWidth, available.coerceAtLeast(1).toDp())
    }
    val height = with(density) {
        val target = card?.intersect(safe)
        val verticalSpace = target?.let {
            maxOf(it.top - safe.top - margin, safe.bottom - it.bottom - margin)
        } ?: safe.height
        val available = when {
            landscape || target == null -> safe.height
            verticalSpace >= 160.dp.roundToPx() -> verticalSpace
            // Large text can make a portrait card fill almost the entire viewport.
            // Limit the other axis here, preserving readable width and card context.
            else -> minOf(safe.height, target.height / 2)
        }
        available.coerceAtLeast(1).toDp()
    }
    return CardTooltipLayout(position, width, height)
}

/** Native tooltip surface/transition; scroll the body while keeping dismissal reachable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TooltipScope.AdaptiveRichTooltip(
    layout: CardTooltipLayout,
    title: String,
    tag: String,
    closeFocus: FocusRequester,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    // The native caret assumes an above/below placement for a custom provider.
    // MD3's optional caret is omitted so a side popup never points at the wrong control.
    RichTooltip(maxWidth = layout.width,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(tag)) {
        // RichTooltip's text slot adds 16dp at both the top and bottom.
        Column(Modifier.heightIn(max = (layout.height - 32.dp).coerceAtLeast(48.dp))) {
            Column(Modifier.weight(1f, fill = false).keyboardScroll(scroll).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                content()
            }
            TextButton(onClick = onClose,
                modifier = Modifier.focusRequester(closeFocus).keyboardFocus(closeEnabled)) { Text("关闭") }
        }
    }
}
