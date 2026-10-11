package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession

private data class PreviewTooltipState(val active: Boolean, val token: Int, val locked: Boolean, val visible: Boolean)

/** The popup and its input preview have the same lifetime. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpectrumTooltip(
    session: MonitorSession,
    channel: Channel,
    anchorFocus: FocusRequester,
    cardBounds: IntRect?,
    content: @Composable () -> Unit,
) {
    if (!channel.input) {
        content()
        return
    }
    val preview by sessionState(session) {
        PreviewTooltipState(session.selectedInput == channel.key && session.visible && session.tab == 0 &&
            !session.runningPublic && !session.startingPublic,
            if (session.selectedInput == channel.key) session.inputSelectionToken else 0,
            session.forwardingLockedPublic, session.visible)
    }
    val active = preview.active
    val token = preview.token
    val state = rememberTooltipState(isPersistent = true)
    val closeFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var wasActive by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(active, preview.locked, preview.visible) {
        if (active) wasActive = true
        else if (wasActive && !preview.locked && preview.visible) {
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

    val layout = rememberCardTooltipLayout(cardBounds, 360.dp)
    TooltipBox(
        positionProvider = layout.position,
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
            AdaptiveRichTooltip(
                layout = layout,
                title = "ID ${channel.device.id} · 通道 ${channel.index}",
                tag = "spectrum-tooltip",
                closeFocus = closeFocus,
                closeEnabled = active,
                onClose = {
                    state.dismiss()
                    session.dismissInputPreview(channel.key, token)
                },
            ) {
                SpectrumContent(
                    session,
                    Modifier.width((layout.width - 32.dp).coerceAtLeast(16.dp)),
                    retainFrame = !active,
                )
            }
            LaunchedEffect(Unit) {
                if (inputMode.inputMode == InputMode.Keyboard) closeFocus.requestFocus()
            }
        },
        content = content,
    )
}
