package cn.xiaotao29.HighSpeedIEM.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.dp

private val LocalReaderHighlights = staticCompositionLocalOf { false }

/** One system listener per UI root, including the standalone compact debug host. */
@Composable
internal fun KeyboardFocusEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var readerHighlights by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { readerHighlights = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    CompositionLocalProvider(LocalReaderHighlights provides readerHighlights, content = content)
}

/** Decorates the control's existing focus target; never adds a second focusable node.
 * Intercept arrows before read-only text fields use them to move their caret.
 * Only this target (not a focused descendant) receives a ring. */
@Composable
internal fun Modifier.keyboardFocus(
    enabled: Boolean = true,
    shape: Shape? = null,
    focusColor: Color? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val manager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val readerHighlights = LocalReaderHighlights.current
    val outline = shape ?: MaterialTheme.shapes.small
    return this.onFocusChanged { focused = it.isFocused }
        .focusProperties { if (!enabled) canFocus = false }
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Previous
                Key.DirectionDown -> FocusDirection.Next
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            if (!focused || direction == null || !enabled) false
            else {
                if (event.type == KeyEventType.KeyDown) manager.moveFocus(direction)
                true
            }
        }
        // TalkBack supplies the system accessibility-focus indicator. Do not
        // draw a second competing ring around its current keyboard target.
        .then(if (focused && enabled && windowFocused && !readerHighlights && inputMode.inputMode == InputMode.Keyboard) Modifier.border(3.dp,
            focusColor ?: MaterialTheme.colorScheme.primary, outline) else Modifier)
}

/** Scroll containers keep their scroll semantics but leave keyboard focus to their actions. */
internal fun Modifier.passiveScroll(): Modifier = focusProperties { canFocus = false }

/** Overflowing information is itself a scroll action. Up/down reads it;
 * at either edge traversal continues. Left/right always leaves the region. */
@Composable
internal fun Modifier.keyboardScroll(state: ScrollState, enabled: Boolean = true): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val manager = LocalFocusManager.current
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    return this.onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (focused && enabled && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                if (event.type == KeyEventType.KeyDown) manager.moveFocus(
                    if (event.key == Key.DirectionLeft) FocusDirection.Previous else FocusDirection.Next)
                return@onPreviewKeyEvent true
            }
            val delta = when (event.key) {
                Key.DirectionUp -> if (state.canScrollBackward) -step else 0f
                Key.DirectionDown -> if (state.canScrollForward) step else 0f
                else -> 0f
            }
            if (!focused || !enabled || delta == 0f) false else {
                if (event.type == KeyEventType.KeyDown) scope.launch { state.animateScrollBy(delta) }
                true
            }
        }
        .keyboardFocus(enabled && state.maxValue > 0 && state.maxValue != Int.MAX_VALUE)
        // Unlike buttons/text fields, a read-only scroll region has no click
        // focus target. Give the scroll action exactly one target of its own.
        .focusable(enabled && state.maxValue > 0 && state.maxValue != Int.MAX_VALUE)
        .passiveScroll()
}
