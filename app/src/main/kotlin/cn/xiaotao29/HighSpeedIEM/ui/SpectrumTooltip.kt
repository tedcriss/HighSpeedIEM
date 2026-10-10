package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession

/** The popup and its input preview have the same lifetime. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpectrumTooltip(
    session: MonitorSession,
    channel: Channel,
    anchorFocus: FocusRequester,
    content: @Composable () -> Unit,
) {
    if (!channel.input) {
        content()
        return
    }
    @Suppress("UNUSED_VARIABLE") val version = session.version.collectAsState().value
    val active = session.selectedInput == channel.key && session.visible && session.tab == 0 &&
        !session.runningPublic && !session.startingPublic
    val token = session.inputSelectionToken
    val state = rememberTooltipState(isPersistent = true)
    val closeFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var wasActive by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(active, session.forwardingLockedPublic, session.visible) {
        if (active) wasActive = true
        else if (wasActive && !session.forwardingLockedPublic && session.visible) {
            wasActive = false
            if (inputMode.inputMode == InputMode.Keyboard) {
                androidx.compose.runtime.withFrameNanos { }
                anchorFocus.requestFocus()
            }
        }
    }

    DisposableEffect(active, token) {
        onDispose {
            if (active) session.dismissInputPreview(channel.key, token)
        }
    }
    LaunchedEffect(active, token) {
        if (active) {
            try {
                state.show()
            } finally {
                // Also handles replacement by another tooltip through the
                // native tooltip mutex, and removal of the anchor composition.
                session.dismissInputPreview(channel.key, token)
            }
        } else {
            state.dismiss()
        }
    }

    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    val left = insets.getLeft(density, LayoutDirection.Ltr)
    val right = insets.getRight(density, LayoutDirection.Ltr)
    val top = insets.getTop(density)
    val bottom = insets.getBottom(density)
    val margin = with(density) { 8.dp.roundToPx() }
    val width = with(density) {
        ((window.width - left - right).toDp() - 32.dp).coerceIn(48.dp, 360.dp)
    }
    val nativePosition = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above)
    val position = remember(nativePosition, left, right, top, bottom, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val offset = nativePosition.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
                return IntOffset(
                    offset.x.coerceIn(left + margin, maxOf(left + margin, windowSize.width - right - margin - popupContentSize.width)),
                    offset.y.coerceIn(top + margin, maxOf(top + margin, windowSize.height - bottom - margin - popupContentSize.height)),
                )
            }
        }
    }
    TooltipBox(
        positionProvider = position,
        state = state,
        focusable = true,
        enableUserInput = false,
        onDismissRequest = {
            state.dismiss()
            session.dismissInputPreview(channel.key, token)
        },
        tooltip = {
            // TooltipBox owns the content's lifetime through its native scale
            // and fade transition. Removing it on deselection makes the popup
            // measure as zero and relocate before the outgoing surface vanishes.
            RichTooltip(
                caretShape = TooltipDefaults.caretShape(),
                maxWidth = width,
                title = { Text("ID ${channel.device.id} · 通道 ${channel.index}") },
                action = {
                    TextButton(onClick = {
                        state.dismiss()
                        session.dismissInputPreview(channel.key, token)
                    }, modifier = Modifier.focusRequester(closeFocus).keyboardFocus(active)) { Text("关闭") }
                    LaunchedEffect(Unit) {
                        if (inputMode.inputMode == InputMode.Keyboard) closeFocus.requestFocus()
                    }
                },
            ) {
                SpectrumContent(
                    session,
                    Modifier.width((width - 32.dp).coerceAtLeast(16.dp)),
                    retainFrame = !active,
                )
            }
        },
        content = content,
    )
}
