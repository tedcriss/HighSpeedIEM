package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.input.pointer.pointerInput

/** Only decorative pixels overlap; the action keeps one native click target. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun ForwardingIcon(running: Boolean, modifier: Modifier = Modifier) {
    Crossfade(running, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "forwarding-icon") { stopping ->
        Icon(if (stopping) Icons.Filled.Close else Icons.Filled.PlayArrow,
            contentDescription = null, modifier = modifier)
    }
}

/** A shared heading hierarchy for the three standard destinations. */
@Composable
fun PageHeading(title: String, description: String, status: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            StatusBadge(status)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared by the phone heading and compact information module. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun StatusBadge(status: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge) {
        Text(status, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Centered loading prompt; the hint line shows on the standard layout only.
 * The overlay absorbs touches so controls cannot be operated while probing.
 */
@Composable
fun LoadingOverlay(showHint: Boolean, blockInput: Boolean = true) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .pointerInput(blockInput) {
                if (!blockInput) return@pointerInput
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(24.dp),
            ) {
                CircularProgressIndicator(Modifier.size(48.dp))
                if (showHint) {
                    Text(
                        "正在加载输入输出通道…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/** MD3 snackbar with wrapping long text and a close action. */
@Composable
fun NoticeSnackbar(data: SnackbarData) {
    Snackbar(
        shape = MaterialTheme.shapes.medium,
        actionContentColor = MaterialTheme.colorScheme.inversePrimary,
        action = {
            data.visuals.actionLabel?.let { label ->
                androidx.compose.material3.TextButton(onClick = { data.performAction() },
                    modifier = Modifier.keyboardFocus(focusColor = MaterialTheme.colorScheme.inversePrimary),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                    Text(label)
                }
            }
        },
        modifier = Modifier.padding(12.dp),
    ) {
        Text(
            data.visuals.message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
