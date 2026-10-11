package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.audio.NativeAudio
import kotlinx.coroutines.launch

/**
 * Actual running parameters of one forwarding pair, shown on demand. The same
 * persistent-tooltip pattern as the device capability entry: a native TooltipBox
 * anchored to the measured route card, keyboard focus restored to the entry on
 * dismiss, and automatic dismissal once forwarding stops or an error closes it.
 * Counters are sampled once per open; only event-driven updates arrive after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteInfoTooltip(
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
            val snapshots by session.endpointSnapshots.collectAsState()
            // One explicit counter read per open; the body stays still afterwards
            // except for event-driven actual-parameter publications.
            LaunchedEffect(Unit) { activity.safe { session.sampleStatsOnce() } }
            AdaptiveRichTooltip(
                layout = layout,
                title = "$label · 运行信息",
                tag = "route-info-tooltip",
                closeFocus = closeFocus,
                onClose = { state.dismiss() },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RouteEndpointInfo("输入 · ID ${input.device.id} · 通道 ${input.index}",
                        snapshots[input.deviceKey])
                    RouteEndpointInfo("输出 · ID ${output.device.id} · 通道 ${output.index}",
                        snapshots[output.deviceKey])
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
                .semantics { contentDescription = "查看${label}的运行信息" },
        ) {
            Text("转发信息", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** One endpoint section: identity heading plus its actual parameter table. */
@Composable
private fun RouteEndpointInfo(header: String, snapshot: NativeAudio.EndpointSnapshot?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(header, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        if (snapshot == null) {
            Text("正在读取运行参数…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CapabilityTable(statsEntries(snapshot))
        }
    }
}

/**
 * Renders the engine's diagnostic lines as name/value rows. `key=value` tokens
 * keep their raw engine names; the descriptive first config line and the
 * `hardware:` line receive fixed labels so every row fits the table.
 */
internal fun statsEntries(snapshot: NativeAudio.EndpointSnapshot): List<Pair<String, String>> {
    val entries = ArrayList<Pair<String, String>>()
    for (part in listOf(snapshot.config, snapshot.settings, snapshot.telemetry)) {
        for (line in part.split('\n')) {
            if (line.isBlank()) continue
            if (line.startsWith("hardware:")) {
                entries += "硬件" to line.removePrefix("hardware:").trim()
                continue
            }
            val loose = ArrayList<String>()
            for (token in line.split(" · ")) {
                val eq = token.indexOf('=')
                if (eq > 0) entries += token.substring(0, eq).trim() to token.substring(eq + 1).trim()
                else loose += token
            }
            if (loose.isNotEmpty()) entries += "流格式" to loose.joinToString(" · ")
        }
    }
    return entries
}
