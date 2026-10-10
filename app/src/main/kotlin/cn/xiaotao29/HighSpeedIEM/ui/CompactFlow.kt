package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession

/** Three compact views onto the phone task table, with a separate page indicator. */
@Composable
fun CompactFlow(activity: MainActivity, session: MonitorSession) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val pagerState = rememberPagerState(initialPage = session.tab.coerceIn(0, 2), pageCount = { 3 })
    val round = LocalConfiguration.current.isScreenRound
    // These pages configure routes; entering from a phone releases previews/tests.
    LaunchedEffect(session) { activity.safe { session.switchTab(2) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Subscribe inside this content scope: its memoized lambda otherwise
        // retains non-snapshot session fields when the parent recomposes.
        @Suppress("UNUSED_VARIABLE") val contentVersion = session.version.collectAsState().value
        // Scaffold handles cutouts; round screens additionally keep text inside the circle.
        val sideInset = if (round) maxWidth * 0.14f else 0.dp
        val endInset = if (round) maxHeight * 0.14f else 8.dp
        val infoTopInset = if (round) maxHeight * 0.10f else 12.dp
        val row = session.model.compactRow
        val editable = !session.forwardingLockedPublic
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0, 1 -> {
                        val input = page == 0
                        val channels = (if (input) session.inputs else session.outputs)
                            .values.filter { it.key !in session.disabled }
                        CompactDeviceList(channels, if (input) row.input else row.output,
                            editable, sideInset, endInset, input) { key ->
                            activity.safe { session.setCompactRouteSide(input, key) }
                        }
                    }
                    else -> CompactStartPage(activity, session, sideInset, infoTopInset)
                }
            }
            CompactPageIndicator(pagerState.currentPage)
        }
    }
}

/** The only content on a selection page, including the dropdown's blank option. */
@Composable
private fun CompactDeviceList(
    channels: List<Channel>, selectedKey: String?, enabled: Boolean,
    sideInset: Dp, endInset: Dp, input: Boolean, onPick: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().selectableGroup()
            .semantics { contentDescription = if (input) "输入通道列表" else "输出通道列表" },
        contentPadding = PaddingValues(top = endInset, bottom = endInset),
    ) {
        items(listOf<Channel?>(null) + channels, key = { it?.key ?: "blank" }) { channel ->
            val current = selectedKey == channel?.key
            val colors = MaterialTheme.colorScheme
            val description = channel?.let { "ID ${it.device.id} · 通道 ${it.index} · ${it.typeLabel}" }
                ?: if (input) "空白，清除输入通道" else "空白，清除输出通道"
            ListItem(
                selected = current,
                onClick = { onPick(channel?.key) },
                enabled = enabled,
                content = {
                    Text(channel?.let { "ID ${it.device.id} · 通道 ${it.index}" } ?: " ",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                },
                supportingContent = channel?.let { { Text(it.typeLabel, style = MaterialTheme.typography.bodyMedium) } },
                colors = ListItemDefaults.colors(
                    containerColor = colors.surface,
                    contentColor = colors.onSurface,
                    supportingContentColor = colors.onSurfaceVariant,
                    selectedContainerColor = colors.primaryContainer,
                    selectedContentColor = colors.onPrimaryContainer,
                    selectedSupportingContentColor = colors.onPrimaryContainer,
                ),
                shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large,
                    selectedShape = MaterialTheme.shapes.large),
                modifier = Modifier.fillMaxWidth().padding(horizontal = sideInset)
                    .semantics {
                        contentDescription = description
                        if (!enabled) disabled()
                    },
            )
        }
    }
}

/** A small independent footer, always outside the page content. */
@Composable
internal fun CompactPageIndicator(currentPage: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().height(32.dp)
                .semantics { contentDescription = "页面指示器" },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val current = currentPage == index
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    .semantics {
                        contentDescription = "第 ${index + 1} 页"
                        selected = current
                    })
            }
        }
    }
}

/** Shared status/data fill the space above a medium-sized action. */
@Composable
private fun CompactStartPage(
    activity: MainActivity, session: MonitorSession,
    sideInset: Dp, infoTopInset: Dp,
) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val row = session.model.compactRow
    val running = session.runningPublic
    val count = session.model.validCount()
    val status = taskStatus(session)
    val inputStats = routeStats(session, row, true)
    val outputStats = routeStats(session, row, false)
    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .semantics { contentDescription = "转发信息" },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = sideInset + 12.dp)
                .padding(top = infoTopInset, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                StatusBadge(status)
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("输入" to inputStats, "输出" to outputStats).forEach { (label, stats) ->
                        if (stats.isNotEmpty()) {
                            Text("$label\n$stats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = sideInset + 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val buttonHeight = ButtonDefaults.MediumContainerHeight
            // Keep the label readable on the smallest round screens. The minimum
            // height remains medium; larger fonts may grow it by wrapping naturally.
            val showIcon = maxWidth >= 200.dp
            Button(
                onClick = { activity.safe { if (running) session.stop(null, false) else activity.startForwarding() } },
                enabled = !session.operationsBlockedPublic && (running || count > 0),
                shape = ButtonDefaults.shape,
                colors = ActionStyle.primaryColors(),
                elevation = ActionStyle.buttonElevation(),
                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = showIcon),
                modifier = Modifier.heightIn(min = buttonHeight),
            ) {
                if (showIcon) {
                    Icon(if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(buttonHeight)))
                }
                Text(if (running) "停止转发" else "启动 $count 个转发",
                    style = ButtonDefaults.textStyleFor(buttonHeight), textAlign = TextAlign.Center)
            }
        }
    }
}
