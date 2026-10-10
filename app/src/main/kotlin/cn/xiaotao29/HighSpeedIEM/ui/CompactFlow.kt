package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun CompactFlow(activity: MainActivity, session: MonitorSession) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val pagerState = rememberPagerState(initialPage = session.tab.coerceIn(0, 2), pageCount = { 3 })
    val round = LocalConfiguration.current.isScreenRound
    val scope = rememberCoroutineScope()
    val pageMotion = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val pageFocus = remember { List(3) { FocusRequester() } }
    var enterPage by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    // These pages configure routes; entering from a phone releases previews/tests.
    LaunchedEffect(session) { activity.safe { session.switchTab(2) } }
    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val step = when (event.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> 0
        }
        if (step == 0) false else {
            if (event.type == KeyEventType.KeyDown && !session.loadingPublic) {
                val next = (pagerState.targetPage + step).coerceIn(0, 2)
                // Footer targets are always present. Move focus with the key,
                // not after the spring settles and later user input has arrived.
                pageFocus[next].requestFocus()
                scope.launch {
                    pagerState.animateScrollToPage(next,
                        animationSpec = pageMotion)
                }
            }
            true
        }
    }) {
        // Subscribe inside this content scope: its memoized lambda otherwise
        // retains non-snapshot session fields when the parent recomposes.
        @Suppress("UNUSED_VARIABLE") val contentVersion = session.version.collectAsState().value
        // Scaffold handles cutouts; round screens additionally keep text inside the circle.
        val sideInset = if (round) maxWidth * 0.14f else 0.dp
        val endInset = if (round) maxHeight * 0.14f else 8.dp
        val infoTopInset = if (round) maxHeight * 0.10f else 12.dp
        val pageIndicatorInset = if (round) maxHeight * 0.08f else 0.dp
        val row = session.model.compactRow
        val editable = !session.forwardingLockedPublic
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().passiveScroll()) { page ->
                when (page) {
                    0, 1 -> {
                        val input = page == 0
                        val channels = (if (input) session.inputs else session.outputs)
                            .values.filter { it.key !in session.disabled }
                        CompactDeviceList(channels, if (input) row.input else row.output,
                            editable && page == pagerState.currentPage, sideInset, endInset, input,
                            active = page == pagerState.currentPage,
                            onEnterReady = { if (page == pagerState.currentPage) enterPage = it },
                            onExit = { forward -> pageFocus[if (forward) 0 else 2].requestFocus() }) { key ->
                            activity.safe { session.setCompactRouteSide(input, key) }
                        }
                    }
                    else -> CompactStartPage(activity, session, sideInset, infoTopInset, pageFocus,
                        round = round,
                        active = page == pagerState.currentPage,
                        onEnterReady = { if (page == pagerState.currentPage) enterPage = it })
                }
            }
            CompactPageIndicator(pagerState.currentPage, pageFocus,
                bottomInset = pageIndicatorInset, enabled = !session.loadingPublic,
                onEnterPage = { last ->
                    enterPage?.invoke(last) ?: pageFocus[if (last) 2 else 0].requestFocus()
                }) { page ->
                scope.launch { pagerState.animateScrollToPage(page,
                    animationSpec = pageMotion) }
            }
        }
    }
}

/** The only content on a selection page, including the dropdown's blank option. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CompactDeviceList(
    channels: List<Channel>, selectedKey: String?, enabled: Boolean,
    sideInset: Dp, endInset: Dp, input: Boolean,
    active: Boolean,
    onEnterReady: (((Boolean) -> Unit)?) -> Unit,
    onExit: (Boolean) -> Unit, onPick: (String?) -> Unit,
) {
    val choices = listOf<Channel?>(null) + channels
    val identities = choices.map { it?.key }
    val listState = rememberLazyListState()
    val itemFocus = remember(identities) { List(choices.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusedIndex by remember { mutableIntStateOf(0) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    fun navigate(index: Int) {
        focusedIndex = index
        navigationJob?.cancel()
        navigationJob = scope.launch {
            listState.scrollToItem(index)
            androidx.compose.runtime.withFrameNanos { }
            itemFocus[index].requestFocus()
        }
    }
    DisposableEffect(identities, enabled, active) {
        onEnterReady(if (enabled) { last -> navigate(if (last) choices.lastIndex else 0) } else null)
        onDispose { onEnterReady(null) }
    }
    DisposableEffect(identities, enabled) {
        onDispose { navigationJob?.cancel() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().passiveScroll().selectableGroup()
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> 0
                }
                val next = focusedIndex + step
                if (!enabled || step == 0) false else {
                    if (event.type == KeyEventType.KeyDown) {
                        // Materialize the requested lazy item before focusing
                        // it, including entry from the fixed page controls.
                        if (next in choices.indices) navigate(next) else onExit(step > 0)
                    }
                    true
                }
            }
            .semantics { contentDescription = if (input) "输入通道列表" else "输出通道列表" },
        state = listState,
        contentPadding = PaddingValues(top = endInset, bottom = endInset),
    ) {
        itemsIndexed(choices, key = { _, channel -> channel?.key ?: "blank" }) { index, channel ->
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
                modifier = Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                ).fillMaxWidth().padding(horizontal = sideInset)
                    .focusRequester(itemFocus[index])
                    .onFocusChanged { if (it.isFocused) focusedIndex = index }
                    .keyboardFocus(enabled, MaterialTheme.shapes.large)
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun CompactPageIndicator(currentPage: Int, focus: List<FocusRequester>,
    bottomInset: Dp = 0.dp, enabled: Boolean = true,
    onEnterPage: (Boolean) -> Unit, onPage: (Int) -> Unit) {
    Surface(modifier = Modifier.padding(bottom = bottomInset), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).selectableGroup()
                .semantics { contentDescription = "页面指示器" },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val current = currentPage == index
                val indicatorWidth by animateDpAsState(if (current) 20.dp else 8.dp,
                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "compact-page-width")
                val indicatorColor by animateColorAsState(
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.motionScheme.fastEffectsSpec(), label = "compact-page-color")
                Box(Modifier.size(48.dp).focusRequester(focus[index])
                    .onPreviewKeyEvent { event ->
                        val last = index == 0 && event.key == Key.DirectionUp
                        val first = index == 2 && event.key == Key.DirectionDown
                        if (!enabled || (!last && !first)) false else {
                            if (event.type == KeyEventType.KeyDown) onEnterPage(last)
                            true
                        }
                    }
                    .keyboardFocus(enabled, shape = CircleShape)
                    .clip(CircleShape)
                    .selectable(selected = current, enabled = enabled, role = Role.Tab, onClick = { onPage(index) })
                    .semantics {
                        contentDescription = "第 ${index + 1} 页"
                        stateDescription = listOf("输入通道", "输出通道", "任务控制")[index]
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(indicatorWidth).height(8.dp).clip(CircleShape).background(indicatorColor))
                }
            }
        }
    }
}

/** Shared status/data fill the space above a medium-sized action. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CompactStartPage(
    activity: MainActivity, session: MonitorSession,
    sideInset: Dp, infoTopInset: Dp, footerFocus: List<FocusRequester>,
    round: Boolean,
    active: Boolean,
    onEnterReady: (((Boolean) -> Unit)?) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val sessionVersion = session.version.collectAsState().value
    val row = session.model.compactRow
    val running = session.runningPublic
    val count = session.model.validCount()
    val status = taskStatus(session)
    val inputStats = routeStats(session, row, true)
    val outputStats = routeStats(session, row, false)
    val infoScroll = rememberScrollState()
    val infoFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    val buttonEnabled = active && !session.operationsBlockedPublic && (running || count > 0)
    val infoFocusable = active && infoScroll.maxValue > 0 && infoScroll.maxValue != Int.MAX_VALUE
    DisposableEffect(buttonEnabled, infoFocusable, active) {
        onEnterReady(if (buttonEnabled || infoFocusable) { last ->
            (if (!last && infoFocusable) infoFocus else if (buttonEnabled) buttonFocus else infoFocus).requestFocus()
        } else null)
        onDispose { onEnterReady(null) }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .semantics { contentDescription = "转发信息" },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = sideInset + 12.dp)
                .padding(top = infoTopInset),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxSize()
                    .focusRequester(infoFocus)
                    .focusProperties {
                        previous = footerFocus.last()
                        next = if (buttonEnabled) buttonFocus else footerFocus.first()
                    }
                    .keyboardScroll(infoScroll, enabled = active && !session.loadingPublic)
                    .verticalScroll(infoScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusBadge(status)
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
            Modifier.fillMaxWidth().padding(horizontal = sideInset + 8.dp,
                vertical = if (round) 2.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val buttonHeight = ButtonDefaults.MediumContainerHeight
            // Keep the label readable on the smallest round screens. The minimum
            // height remains medium; larger fonts may grow it by wrapping naturally.
            val showIcon = maxWidth >= 200.dp
            Button(
                onClick = { activity.safe { if (running) session.stop(null, false) else activity.startForwarding() } },
                enabled = buttonEnabled,
                shapes = ButtonDefaults.shapesFor(buttonHeight),
                colors = ActionStyle.primaryColors(),
                elevation = ActionStyle.buttonElevation(),
                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = showIcon),
                modifier = Modifier.heightIn(min = buttonHeight)
                    .focusRequester(buttonFocus)
                    .focusProperties { previous = if (infoFocusable) infoFocus else footerFocus.last(); next = footerFocus.first() }
                    .keyboardFocus(buttonEnabled,
                        ButtonDefaults.shape, MaterialTheme.colorScheme.onPrimary),
            ) {
                if (showIcon) {
                    ForwardingIcon(running, Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(buttonHeight)))
                }
                Text(if (running) "停止转发" else "启动 $count 个转发",
                    style = ButtonDefaults.textStyleFor(buttonHeight), textAlign = TextAlign.Center,
                    modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()))
            }
        }
    }
}
