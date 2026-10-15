package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.NativeRelay
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Forward-control tab. Bottom layer: one card per output channel (count
 * follows the selected output device / its requested channel count). Top
 * layer: battery optimization, realtime parameters and the start/stop key.
 */
@Composable
fun ForwardPage(activity: MainActivity, session: RelaySession) {
    val outChannels = session.effectiveOutputChannels
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (outChannels == 0) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MarqueeText(
                            "未选中输出设备，暂无转发通道",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(count = outChannels, key = { it }) { channel ->
                    ChannelMixCard(session, channel)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatteryButton(activity, session)
            RealtimeParamsButton()
            StartStopButton(activity, session)
        }
    }
}

@Composable
private fun ChannelMixCard(session: RelaySession, channel: Int) {
    val selectedCount = Integer.bitCount(session.mixerMask(channel))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    "$channel",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
            MixerDialogButton(
                session = session,
                channel = channel,
                label = if (selectedCount > 0) "混音输入 · 已选 $selectedCount" else "混音输入",
                enabled = !session.busy,
            )
            EffectsDialogButton(channel = channel, enabled = !session.busy)
        }
    }
}

@Composable
private fun MixerDialogButton(
    session: RelaySession,
    channel: Int,
    label: String,
    enabled: Boolean,
) {
    var show by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = { show = true },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "输出通道 $channel：$label" },
    ) {
        Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
        MarqueeText(label, style = MaterialTheme.typography.labelLarge)
    }
    if (show) {
        MixerDialog(session = session, outChannel = channel, onDismiss = { show = false })
    }
}

/** Basic dialog: two-column checkbox list of the selected input's channels. */
@Composable
private fun MixerDialog(session: RelaySession, outChannel: Int, onDismiss: () -> Unit) {
    val inputId = session.selectedInputId
    val device = inputId?.let { session.inputDevices[it] }
    val inputChannels = session.effectiveInputChannels
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", maxLines = 1) }
        },
        title = { MarqueeText("输出通道 $outChannel · 选择混音输入通道") },
        text = {
            if (device == null || inputChannels <= 0) {
                MarqueeText(
                    "请先在输入设备页选中一个输入设备",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val mask = session.mixerMask(outChannel)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    repeat(inputChannels) { channel ->
                        val checked = (mask and (1 shl channel)) != 0
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { session.toggleMixerChannel(outChannel, channel) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            MarqueeText(
                                "ID ${device.id} · 通道 $channel",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Reserved entry for per-channel input effects; content not implemented yet. */
@Composable
private fun EffectsDialogButton(channel: Int, enabled: Boolean) {
    var show by remember { mutableStateOf(false) }
    TextButton(
        onClick = { show = true },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "输出通道 $channel：输入音效（暂未实现）" },
    ) {
        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
        MarqueeText("输入音效", style = MaterialTheme.typography.labelLarge)
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("关闭", maxLines = 1) }
            },
            title = { MarqueeText("输出通道 $channel · 输入音效") },
            text = { Box(Modifier.fillMaxWidth().height(48.dp)) },
        )
    }
}

@Composable
private fun BatteryButton(activity: MainActivity, session: RelaySession) {
    val available = remember { activity.batterySettingsAvailable() }
    IconButton(
        onClick = { activity.launchBatterySettings() },
        enabled = available && !session.busy,
        colors = IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(Icons.Filled.Battery5Bar, contentDescription = "打开系统后台运行保护设置")
    }
}

/** Realtime stream parameters tooltip; polls the engine every 200 ms while open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealtimeParamsButton() {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = state,
        focusable = true,
        tooltip = {
            RichTooltip(
                title = { MarqueeText("实时流参数") },
                text = { RealtimeParamsContent(visible = state.isVisible) },
            )
        },
    ) {
        FilledTonalIconButton(
            onClick = {
                scope.launch {
                    if (state.isVisible) state.dismiss() else state.show()
                }
            },
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "查看实时流参数")
        }
    }
}

@Composable
private fun RealtimeParamsContent(visible: Boolean) {
    var inputInfo by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var outputInfo by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (isActive) {
            val inPairs = withContext(Dispatchers.IO) { pollStream(AAudio.DIRECTION_INPUT) }
            val outPairs = withContext(Dispatchers.IO) { pollStream(AAudio.DIRECTION_OUTPUT) }
            inputInfo = inPairs
            outputInfo = outPairs
            delay(200)
        }
    }
    Column(
        Modifier
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarqueeText("输入流", style = MaterialTheme.typography.titleSmall)
        if (inputInfo.isEmpty()) {
            MarqueeText(
                "无已打开的输入流",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            InfoTable(inputInfo)
        }
        MarqueeText("输出流", style = MaterialTheme.typography.titleSmall)
        if (outputInfo.isEmpty()) {
            MarqueeText(
                "无已打开的输出流",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            InfoTable(outputInfo)
        }
    }
}

private fun pollStream(direction: Int): List<Pair<String, String>> {
    val flat = try {
        NativeRelay.nativeStreamInfo(direction)
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    return flat.asList().chunked(2).map { (key, value) -> key to value }
}

/** Icon-only start/stop key; disabled until an input and an output are picked. */
@Composable
private fun StartStopButton(activity: MainActivity, session: RelaySession) {
    val running = session.forwarding
    IconButton(
        onClick = { if (running) activity.stopForwarding() else activity.startForwarding() },
        enabled = running || (session.canForward && !session.busy),
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(),
    ) {
        Icon(
            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (running) "停止转发" else "启动转发",
        )
    }
}
