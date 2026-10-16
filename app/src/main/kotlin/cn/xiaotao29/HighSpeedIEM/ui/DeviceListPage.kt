package cn.xiaotao29.HighSpeedIEM.ui

import android.media.AudioDeviceInfo
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
internal fun DevicesPage(session: RelaySession, input: Boolean, menu: DeviceMenuState) {
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
        // Width-adaptive column count: 1 on narrow screens up to N on very
        // wide ones (each column at least 320dp), in both orientations.
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The page heading scrolls with the list, like the pre-rewrite app.
        item(key = "page-heading", span = { GridItemSpan(maxLineSpan) }) {
            PageHeading(
                title = if (input) "输入设备" else "输出设备",
                description = if (input) {
                    "选中一个设备作为转发输入源，卡片内可查看设备能力与开流参数。"
                } else {
                    "选中一个设备作为转发输出目标，选中状态跨 tab 页保留。"
                },
                status = (if (input) session.selectedInputId else session.selectedOutputId)
                    ?.let { "已选中 ID $it" } ?: "未选中",
            )
        }
        items(entries, key = { it.key }) { entry ->
            DeviceCard(session, input, entry.value, menu)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(session: RelaySession, input: Boolean, device: AudioDeviceInfo, menu: DeviceMenuState) {
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
    val cardFocus = remember { FocusRequester() }
    val active = menu.deviceId == device.id && menu.input == input
    val panel = menu.panel.takeIf { active }
    val restoreFocus = menu.restoreId == device.id && menu.restoreInput == input
    fun nextPanel() {
        if (locked) return
        menu.next(input, device.id)
    }
    LaunchedEffect(panel, restoreFocus, locked) {
        if (panel == null && restoreFocus && !locked) {
            cardFocus.requestFocus()
            menu.focusRestored()
        }
        if (locked && panel != null) menu.dismiss()
    }
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
            .focusRequester(cardFocus)
            .onMenuKey(::nextPanel)
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
                for (action in listOf(DevicePanel.Capabilities, DevicePanel.Test)) {
                    DeviceTooltipButton(
                        device = device,
                        action = action,
                        open = panel == action,
                        onOpen = { menu.open(input, device.id, action) },
                        onDismiss = menu::dismiss,
                        onMenu = ::nextPanel,
                    )
                }
                TextButton(
                    onClick = { menu.open(input, device.id, DevicePanel.Params) },
                    enabled = !locked,
                    modifier = Modifier.focusProperties { canFocus = false }.semantics {
                        contentDescription = "设置 ID ${device.id} 的开流参数"
                    },
                ) {
                    MarqueeText("设备参数", style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                }
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

/** Native tooltip with one focusable scroll region; touch buttons stay out of D-pad traversal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTooltipButton(
    device: AudioDeviceInfo,
    action: DevicePanel,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onMenu: () -> Unit,
) {
    // Each visibility change replaces the popup state immediately. This avoids
    // an outgoing popup animation retaining focus during a menu-key handoff.
    val state = key(open) { rememberTooltipState(initialIsVisible = open, isPersistent = true) }
    // TooltipBox may re-key its content when native placement changes sides.
    // Keep scrolling outside that content so a placement pass cannot reset it.
    val scrollState = key(open) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val capabilities = action == DevicePanel.Capabilities
    val entries = remember(device) { capabilityEntries(device) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxContentHeight = with(LocalDensity.current) {
        (windowHeight * 0.35f).toDp().coerceAtMost(320.dp)
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        state = state,
        focusable = true,
        enableUserInput = false,
        onDismissRequest = onDismiss,
        tooltip = {
            val popupFocus = remember { FocusRequester() }
            val rowHeight = with(LocalDensity.current) {
                MaterialTheme.typography.bodyMedium.lineHeight.toPx() + 8.dp.toPx()
            }
            LaunchedEffect(Unit) { popupFocus.requestFocus() }
            RichTooltip(
                modifier = Modifier.onMenuKey(onMenu),
                title = { MarqueeText("${if (capabilities) "设备能力" else "测试设备"} · ID ${device.id}") },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = maxContentHeight)
                            .focusRequester(popupFocus)
                            // ScrollState handles bounds, touch, wheel and accessibility.
                            // Read-only scroll regions need arrow-key mapping in Compose;
                            // its default key handler only supports page up/down.
                            .onPreviewKeyEvent { event ->
                                val direction = when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
                                    else -> 0
                                }
                                if (direction == 0) false else {
                                    if (event.type == KeyEventType.KeyDown) {
                                        scope.launch { scrollState.scrollBy(rowHeight * direction) }
                                    }
                                    true
                                }
                            }
                            .verticalScroll(scrollState)
                            .focusable(),
                    ) {
                        if (capabilities) {
                            InfoTable(entries, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                },
            )
        },
    ) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.focusProperties { canFocus = false }.semantics {
                contentDescription = if (capabilities) "查看 ID ${device.id} 的设备能力"
                    else "测试 ID ${device.id}（暂未实现）"
            },
        ) {
            MarqueeText(if (capabilities) "设备能力" else "测试设备", style = MaterialTheme.typography.labelLarge)
            Icon(if (capabilities) Icons.Outlined.Info else Icons.Outlined.GraphicEq,
                contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
