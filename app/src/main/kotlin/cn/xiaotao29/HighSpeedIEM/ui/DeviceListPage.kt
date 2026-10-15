package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.audio.AAudio
import cn.xiaotao29.HighSpeedIEM.audio.AudioDeviceTypes
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import cn.xiaotao29.HighSpeedIEM.audio.listText
import kotlinx.coroutines.launch

/**
 * Input / output device tabs. One card per audio device (no per-channel
 * split). Portrait lists one column, landscape two.
 */
@Composable
fun DevicesPage(session: RelaySession, input: Boolean) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val devices = if (input) session.inputDevices else session.outputDevices
    val entries = devices.entries.sortedBy { it.key }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MarqueeText(
                if (input) "未发现输入设备" else "未发现输出设备",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (landscape) 2 else 1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            DeviceCard(session, input, entry.value)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(session: RelaySession, input: Boolean, device: AudioDeviceInfo) {
    val selected =
        (if (input) session.selectedInputId else session.selectedOutputId) == device.id
    val locked = session.operationsBlocked
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        label = "device-card-container",
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurface,
        label = "device-card-content",
    )
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    val maxChannels = device.channelCounts.maxOrNull() ?: 0
    Card(
        enabled = !locked,
        onClick = {
            if (input) session.toggleInputSelection(device.id)
            else session.toggleOutputSelection(device.id)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (selected || focused) Modifier.border(BorderStroke(2.dp, colors.primary), shape)
                else Modifier
            )
            .semantics {
                role = Role.Button
                contentDescription =
                    "ID ${device.id}，$maxChannels 通道，${AudioDeviceTypes.label(device.type)}，" +
                        device.productName + if (input) "，输入设备" else "，输出设备"
                stateDescription = if (selected) "已选中为转发设备" else "未选中"
                this.selected = selected
            },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = content,
            // A resource lock disables interaction without changing the look.
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Icon(
                        if (input) Icons.Filled.Mic else Icons.Filled.Speaker,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(20.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarqueeText(
                        "ID ${device.id} · 通道 $maxChannels",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    MarqueeText(
                        "${AudioDeviceTypes.label(device.type)} · ${device.productName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityTooltipButton(device)
                TestTooltipButton(device)
                ParamsDialogButton(session, input, device, enabled = !locked)
            }
        }
    }
}

private fun capabilityEntries(device: AudioDeviceInfo): List<Pair<String, String>> {
    val entries = mutableListOf(
        "getProductName" to device.productName.toString(),
        "getId" to device.id.toString(),
        "getType" to AudioDeviceTypes.rawName(device.type),
        "getChannelCounts" to listText(device.channelCounts),
        "getSampleRates" to listText(device.sampleRates),
        "getEncodings" to listText(device.encodings) { AAudio.encodingName(it) },
    )
    if (Build.VERSION.SDK_INT >= 28) {
        entries += "getAddress" to (device.address?.toString() ?: "null")
    }
    return entries
}

/** Capability key: raw AudioDeviceInfo data in a two-column tooltip table. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapabilityTooltipButton(device: AudioDeviceInfo) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val entries = remember(device) { capabilityEntries(device) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = state,
        focusable = true,
        tooltip = {
            RichTooltip(
                title = { MarqueeText("设备能力 · ID ${device.id}") },
                text = { InfoTable(entries, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) },
            )
        },
    ) {
        TextButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.semantics {
                contentDescription = "查看 ID ${device.id} 的设备能力"
            },
        ) {
            Text("设备能力", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** Test key: reserved, shows an empty tooltip for now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestTooltipButton(device: AudioDeviceInfo) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = state,
        focusable = true,
        tooltip = {
            RichTooltip(
                title = { MarqueeText("测试设备 · ID ${device.id}") },
                text = { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) },
            )
        },
    ) {
        TextButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.semantics {
                contentDescription = "测试 ID ${device.id}（暂未实现）"
            },
        ) {
            Text("测试设备", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(
                Icons.Outlined.GraphicEq, contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Params key: opens the full-screen stream parameter dialog. */
@Composable
private fun ParamsDialogButton(
    session: RelaySession,
    input: Boolean,
    device: AudioDeviceInfo,
    enabled: Boolean,
) {
    var show by remember(device.id) { mutableStateOf(false) }
    TextButton(
        onClick = { show = true },
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = "设置 ID ${device.id} 的开流参数"
        },
    ) {
        Text("设备参数", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
    }
    if (show) {
        StreamParamsDialog(session, input, device, onDismiss = { show = false })
    }
}
