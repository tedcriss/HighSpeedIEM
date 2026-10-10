package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.Button
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.audio.RouteModel

// Container elevation is independent of forwarding, availability and interaction state.
private val TaskToolbarShadowElevation = 6.dp

private data class RouteCardState(
    val row: RouteModel.Row,
    val inputStats: String,
    val outputStats: String,
)

/**
 * One route list with a bottom toolbar in portrait or a reserved toolbar lane
 * in landscape. Native measurement keeps all route controls unobscured.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TaskPage(
    activity: MainActivity,
    session: MonitorSession,
    landscape: Boolean = false,
    onControlsHeightChanged: (Dp) -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val status = taskStatus(session)
    // Capture values in this subscribed scope. Lazy content and child cards
    // receive immutable snapshots instead of reading a mutable session later.
    val cards = session.model.rows.map { row ->
        RouteCardState(row, routeStats(session, row, true), routeStats(session, row, false))
    }
    val inputChannels = session.inputs.values.filter { it.key !in session.disabled }
    val outputChannels = session.outputs.values.filter { it.key !in session.disabled }
    val editable = !session.forwardingLockedPublic
    val density = LocalDensity.current
    var controlsHeightPx by remember { mutableIntStateOf(0) }
    val controlsHeight = with(density) { controlsHeightPx.toDp() }
    // Focus scrolling must use the unobscured viewport, while the floating
    // toolbar and the list's visible background retain their existing layout.
    val focusScroll = remember(controlsHeightPx, landscape) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                super.calculateScrollDistance(offset, size, (containerSize - if (landscape) 0 else controlsHeightPx).coerceAtLeast(1f))
        }
    }
    Row(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (landscape) {
            VerticalTaskControls(activity, session)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                ChannelList(cards, landscape,
                    tag = "task-route-list",
                    modifier = Modifier.fillMaxSize(),
                    padding = PaddingValues(
                        start = 16.dp, top = 16.dp, end = 16.dp,
                        bottom = if (landscape) 16.dp else (controlsHeight - 12.dp).coerceAtLeast(0.dp),
                    ),
                    itemKey = { it.row.id },
                    animateItems = true,
                    heading = { PageHeading("音频转发", "配对已启用的输入与输出通道，即可开始转发。", status) },
                ) { index, card, animation ->
                    RouteCard(card, index, inputChannels, outputChannels, editable, animation) { input, value ->
                        activity.safe { session.setRouteSide(card.row, input, value) }
                    }
                }
            }
            // Scaffold already keeps this page above the NavigationBar. Only this
            // wrap-content toolbar is drawn above the list, visible around its sides.
            if (!landscape) HorizontalFloatingToolbar(
                expanded = true,
                expandedShadowElevation = TaskToolbarShadowElevation,
                collapsedShadowElevation = TaskToolbarShadowElevation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .onSizeChanged {
                        // Include the outer top/bottom gap in list and notice clearance.
                        controlsHeightPx = it.height + with(density) { 24.dp.roundToPx() }
                        onControlsHeightChanged(with(density) { controlsHeightPx.toDp() })
                    }
                    .testTag("task-forwarding-toolbar"),
            ) {
                SplitStartButton(activity, session)
            }
        }
    }
}

/** One forwarding row: input dropdown, output dropdown, live stream stats. */
@Composable
private fun RouteCard(
    state: RouteCardState,
    index: Int,
    inputChannels: List<Channel>,
    outputChannels: List<Channel>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Boolean, String?) -> Unit,
) {
    val row = state.row
    val largeText = LocalDensity.current.fontScale > 1.2f
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("task-route-$index"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (row.blank()) "添加转发" else "转发 ${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
            if (largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RouteSide(row.input, inputChannels, state.inputStats, index, true, editable,
                        Modifier.fillMaxWidth(), onPick)
                    RouteSide(row.output, outputChannels, state.outputStats, index, false, editable,
                        Modifier.fillMaxWidth(), onPick)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RouteSide(row.input, inputChannels, state.inputStats, index, true, editable,
                        Modifier.weight(1f), onPick)
                    RouteSide(row.output, outputChannels, state.outputStats, index, false, editable,
                        Modifier.weight(1f), onPick)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun RouteSide(
    current: String?,
    channels: List<Channel>,
    stats: String,
    index: Int,
    input: Boolean,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Boolean, String?) -> Unit,
) {
    val keys = listOf<String?>(null) + channels.map { it.key }
    val labels = listOf(" ") + channels.map(::channelLabel)
    val selected = keys.indexOf(current).coerceAtLeast(0)
    var lastStats by remember { mutableStateOf(stats) }
    SideEffect { if (stats.isNotEmpty()) lastStats = stats }

    Column(modifier) {
        OptionDropdown(
            label = if (input) "输入通道" else "输出通道",
            options = labels,
            selectedIndex = selected,
            enabled = editable,
            hint = (if (input) "输入" else "输出") + "转发通道，第 " + (index + 1) + " 行；选择空白可清除",
            // Keep channel identity visible in the narrow, native anchor.
            // The menu retains the full device name and type.
            selectedValue = channels.firstOrNull { it.key == current }?.let { channel ->
                buildAnnotatedString {
                    append("ID ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(channel.device.id.toString())
                    }
                    append(" · 通道 ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(channel.index.toString())
                    }
                }
            },
            optionText = { option ->
                val lines = option.split('\n', limit = 2)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lines[0], style = MaterialTheme.typography.bodyMedium)
                    if (lines.size > 1) {
                        Text(lines[1], style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Normal)
                    }
                }
            },
        ) { position ->
            if (position in keys.indices) onPick(input, keys[position])
        }
        // Animate the appearance of stats, never the continuously refreshed text.
        AnimatedVisibility(
            visible = stats.isNotEmpty(),
            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            label = "route-stats",
        ) {
            Text(if (stats.isNotEmpty()) stats else lastStats, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

internal fun taskStatus(session: MonitorSession): String =
    if (session.runningPublic) "正在转发" else if (session.model.validCount() > 0) "已就绪" else "待配置"

internal fun routeStats(session: MonitorSession, row: RouteModel.Row, input: Boolean): String {
    val channel = if (input) session.inputs[row.input] else session.outputs[row.output]
    return if (session.runningPublic && row.complete() && channel != null)
        session.actualStats[channel.deviceKey].orEmpty() else ""
}

/**
 * Battery settings entry + start/stop forwarding as one split button. Its
 * containing toolbar owns placement and clearance. Both halves use the native
 * filled split-button defaults; only the enabled color roles differ.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SplitStartButton(
    activity: MainActivity,
    session: MonitorSession,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val running = session.runningPublic
    val blocked = session.operationsBlockedPublic
    val count = session.model.validCount()
    val batteryEnabled = !blocked && activity.openBatterySettings()
    val startEnabled = !blocked && (running || count > 0)
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = { activity.safe { activity.launchBatterySettings() } },
                enabled = batteryEnabled,
                modifier = Modifier.keyboardFocus(batteryEnabled, ButtonDefaults.shape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Filled.Battery5Bar, contentDescription = "打开系统后台运行保护设置",
                    modifier = Modifier.size(20.dp))
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                onClick = { activity.safe { if (running) session.stop(null, false) else activity.startForwarding() } },
                enabled = startEnabled,
                modifier = Modifier.keyboardFocus(startEnabled, ButtonDefaults.shape,
                    MaterialTheme.colorScheme.onPrimary),
            ) {
                ForwardingIcon(running, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (running) "停止转发" else "启动 $count 个转发",
                    modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
                )
            }
        },
    )
}

/** A full-height reserved lane keeps the toolbar completely outside the route viewport. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun VerticalTaskControls(activity: MainActivity, session: MonitorSession) {
    @Suppress("UNUSED_VARIABLE") val version = session.version.collectAsState().value
    val running = session.runningPublic
    val blocked = session.operationsBlockedPublic
    val count = session.model.validCount()
    val batteryEnabled = !blocked && activity.openBatterySettings()
    val startEnabled = !blocked && (running || count > 0)
    val startLabel = if (running) "停止转发" else "启动 $count 个转发"
    BoxWithConstraints(Modifier.fillMaxHeight().testTag("task-toolbar-lane")) {
        val availableHeight = (maxHeight - 24.dp).coerceAtLeast(48.dp)
        VerticalFloatingToolbar(
            expanded = true,
            expandedShadowElevation = TaskToolbarShadowElevation,
            collapsedShadowElevation = TaskToolbarShadowElevation,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp)
                .heightIn(max = availableHeight).verticalScroll(rememberScrollState())
                .testTag("task-forwarding-toolbar"),
        ) {
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val batteryShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp,
                    bottomStart = 4.dp, bottomEnd = 4.dp)
                val startShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp,
                    bottomStart = 24.dp, bottomEnd = 24.dp)
                Button(
                    onClick = { activity.safe { activity.launchBatterySettings() } },
                    enabled = batteryEnabled,
                    shape = batteryShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth().keyboardFocus(batteryEnabled, batteryShape)
                        .semantics { contentDescription = "打开系统后台运行保护设置" },
                ) { Icon(Icons.Filled.Battery5Bar, null, Modifier.size(20.dp)) }
                Button(
                    onClick = { activity.safe { if (running) session.stop(null, false) else activity.startForwarding() } },
                    enabled = startEnabled,
                    shape = startShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth().keyboardFocus(startEnabled, startShape,
                        MaterialTheme.colorScheme.onPrimary)
                        .semantics { contentDescription = startLabel },
                ) {
                    ForwardingIcon(running, Modifier.size(20.dp))
                }
            }
        }
    }
}
