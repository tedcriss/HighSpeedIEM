package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.NativeRelay
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val ToolbarShadowElevation = 6.dp

/**
 * Forward-control tab. Bottom layer: one card per output channel (count
 * follows the selected output device / its requested channel count). Top
 * layer: a floating toolbar holding the three controls as equal-level
 * transparent icon buttons — horizontal at the bottom in portrait, a
 * vertical floating toolbar lane in landscape. The realtime parameters open
 * as an in-page floating panel (not a popup window): popup tooltips resized
 * and repositioned themselves while their content scrolled.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ForwardPage(activity: MainActivity, session: RelaySession) {
    val outChannels = session.effectiveOutputChannels
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showRealtime by remember { mutableStateOf(false) }
    LaunchedEffect(session.forwarding) {
        if (!session.forwarding) showRealtime = false
    }
    Row(Modifier.fillMaxSize()) {
        if (landscape) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VerticalFloatingToolbar(
                    expanded = true,
                    expandedShadowElevation = ToolbarShadowElevation,
                    collapsedShadowElevation = ToolbarShadowElevation,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ForwardControlButtons(activity, session, showRealtime) {
                            showRealtime = !showRealtime
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, top = 4.dp, end = 16.dp,
                    bottom = if (landscape) 16.dp else 112.dp,
                ),
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
            if (showRealtime) {
                RealtimeParamsPanel(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (landscape) 16.dp else 104.dp,
                        )
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    onClose = { showRealtime = false },
                )
            }
            if (!landscape) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    expandedShadowElevation = ToolbarShadowElevation,
                    collapsedShadowElevation = ToolbarShadowElevation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForwardControlButtons(activity, session, showRealtime) {
                            showRealtime = !showRealtime
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three toolbar controls on one level: battery optimization, realtime
 * parameters and start/stop, each a transparent icon-only IconButton. The
 * start/stop icon keeps the primary highlight tint to stand out.
 */
@Composable
private fun ForwardControlButtons(
    activity: MainActivity,
    session: RelaySession,
    realtimeOpen: Boolean,
    onRealtimeToggle: () -> Unit,
) {
    BatteryIconButton(activity, session)
    RealtimeParamsIconButton(enabled = session.forwarding, open = realtimeOpen) {
        onRealtimeToggle()
    }
    StartStopIconButton(activity, session)
}

@Composable
private fun BatteryIconButton(activity: MainActivity, session: RelaySession) {
    val available = remember { activity.batterySettingsAvailable() }
    IconButton(
        onClick = { activity.launchBatterySettings() },
        enabled = available && !session.busy,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
    ) {
        Icon(Icons.Filled.Battery5Bar, contentDescription = "打开系统后台运行保护设置")
    }
}

@Composable
private fun RealtimeParamsIconButton(enabled: Boolean, open: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (open) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
        ),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = "查看实时流参数")
    }
}

@Composable
private fun StartStopIconButton(activity: MainActivity, session: RelaySession) {
    val running = session.forwarding
    val enabled = running ||
        (session.canForward && session.hasMixerRoutes && !session.busy)
    IconButton(
        onClick = { if (running) activity.stopForwarding() else activity.startForwarding() },
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(
            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (running) "停止转发" else "启动转发",
        )
    }
}

/**
 * In-page floating panel with the live stream parameters. Its bounds come
 * from the page layout, so scrolling the table can never resize or reposition
 * it the way a popup tooltip did. Polls the engine once per second while
 * composed; only changed value texts recompose.
 */
@Composable
private fun RealtimeParamsPanel(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val inputValues = remember { mutableStateMapOf<String, String>() }
    val outputValues = remember { mutableStateMapOf<String, String>() }
    var inputKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val inPairs = withContext(Dispatchers.IO) { pollStream(AAudio.DIRECTION_INPUT) }
            val outPairs = withContext(Dispatchers.IO) { pollStream(AAudio.DIRECTION_OUTPUT) }
            if (inputKeys.isEmpty() && inPairs.isNotEmpty()) inputKeys = inPairs.map { it.first }
            if (outputKeys.isEmpty() && outPairs.isNotEmpty()) outputKeys = outPairs.map { it.first }
            applyPoll(inputValues, inPairs)
            applyPoll(outputValues, outPairs)
            delay(1000)
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarqueeText(
                    "实时流参数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭实时流参数")
                }
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "输入流",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (inputKeys.isEmpty()) {
                    Text(
                        "无已打开的输入流",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    RealtimeTable(inputKeys, inputValues)
                }
                Text(
                    "输出流",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (outputKeys.isEmpty()) {
                    Text(
                        "无已打开的输出流",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    RealtimeTable(outputKeys, outputValues)
                }
            }
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
                label = "混音输入 · 已选 $selectedCount",
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

/** Writes only changed entries so unchanged value texts keep their composition. */
private fun applyPoll(target: SnapshotStateMap<String, String>, pairs: List<Pair<String, String>>) {
    for ((key, value) in pairs) {
        if (target[key] != value) target[key] = value
    }
}

/**
 * Fixed two-column table; each value cell recomposes on its own change only.
 * Cells are plain single-line ellipsized texts on purpose: marquee animations
 * for every row kept the main thread busy and stalled the UI while polling.
 */
@Composable
private fun RealtimeTable(keys: List<String>, values: SnapshotStateMap<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { key ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.45f).clearAndSetSemantics {},
                )
                RealtimeValue(key, values, Modifier.weight(0.55f))
            }
        }
    }
}

@Composable
private fun RealtimeValue(key: String, values: Map<String, String>, modifier: Modifier = Modifier) {
    val value = values[key].orEmpty()
    Text(
        value.ifEmpty { "（空）" },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$key，${value.ifEmpty { "空" }}"
        },
    )
}

private fun pollStream(direction: Int): List<Pair<String, String>> {
    val flat = try {
        NativeRelay.nativeStreamInfo(direction)
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    return flat.asList().chunked(2).map { (key, value) -> key to value }
}
