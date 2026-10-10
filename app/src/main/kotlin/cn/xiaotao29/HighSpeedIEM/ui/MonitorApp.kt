package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.R
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    INPUT("输入设置", Icons.Outlined.Mic, Icons.Filled.Mic),
    OUTPUT("输出设置", Icons.Outlined.Speaker, Icons.Filled.Speaker),
    TASK("任务控制", Icons.Filled.PlayArrow, Icons.Filled.PlayArrow),
}

/**
 * Three window layouts share one session: portrait app bar/navigation bar,
 * landscape rail/content, and a compact pager for low-resolution/watch windows.
 *
 * Session mutations are tracked through [MonitorSession.version]; the value is
 * read in this scope so navigation and page content stay in sync.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MonitorApp(activity: MainActivity, session: MonitorSession) {
    // Read in this composition scope (not inside a nested lambda) so every
    // session mutation recomposes the tab selection along with the current page.
    @Suppress("UNUSED_VARIABLE") val version = session.version.collectAsState().value
    val notice = session.notices.collectAsState().value
    val snackbar = remember { SnackbarHostState() }
    val pageState = rememberSaveableStateHolder()
    var taskControlsHeight by remember { mutableStateOf(0.dp) }

    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val mode = appLayoutMode(windowSize.width, windowSize.height, configuration.uiMode)
    val isCompact = mode == AppLayoutMode.Compact
    val landscape = mode == AppLayoutMode.Landscape

    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(text, actionLabel = "关闭", duration = SnackbarDuration.Long)
        session.consumeNotice()
    }

    val tabs = Tab.entries
    val selectedTab = tabs.getOrElse(session.tab) { Tab.INPUT }

    HighSpeedIEMTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                // Only the task page has floating action controls.
                snackbarHost = {
                    val noticeInset = when {
                        isCompact -> 96.dp
                        !landscape && selectedTab == Tab.TASK -> taskControlsHeight + 8.dp
                        else -> 8.dp
                    }
                    SnackbarHost(snackbar, Modifier.padding(bottom = noticeInset)) { data -> NoticeSnackbar(data) }
                },
                topBar = {
                    if (mode == AppLayoutMode.Portrait) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface),
                            title = {
                                Column {
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        stringResource(R.string.developer_credit),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                },
                bottomBar = {
                    if (mode == AppLayoutMode.Portrait) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = tab == selectedTab,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    onClick = {
                                        if (tab != selectedTab) {
                                            activity.safe { session.switchTab(tabs.indexOf(tab)) }
                                        }
                                    },
                                    enabled = !session.loadingPublic,
                                    modifier = Modifier.keyboardFocus(!session.loadingPublic, MaterialTheme.shapes.large),
                                    icon = {
                                        Icon(
                                            if (tab == selectedTab) tab.selectedIcon else tab.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // The loading prompt is a full-size overlay centred on the page,
                // in front of the tab content.
                Row(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                    .semantics { testTagsAsResourceId = true }.testTag("layout-${mode.name.lowercase()}")) {
                    if (landscape) {
                        LandscapeNavigation(selectedTab, !session.loadingPublic) { tab ->
                            if (tab != selectedTab) activity.safe { session.switchTab(tabs.indexOf(tab)) }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (isCompact) {
                            pageState.SaveableStateProvider("compact") { CompactFlow(activity, session) }
                        } else {
                            // Dispose the old destination immediately: its tooltips,
                            // focus targets and audio cleanup must not survive a fade.
                            // Only the incoming page animates; native saved scroll
                            // state survives navigation without retaining previews.
                            key(selectedTab) {
                                val entering = remember {
                                    MutableTransitionState(false).apply { targetState = true }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visibleState = entering,
                                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                                    exit = ExitTransition.None,
                                    modifier = Modifier.fillMaxSize(),
                                    label = "destination",
                                ) {
                                    pageState.SaveableStateProvider(selectedTab.name) {
                                        when (selectedTab) {
                                            Tab.INPUT -> InputPage(activity, session, landscape)
                                            Tab.OUTPUT -> OutputPage(activity, session, landscape)
                                            Tab.TASK -> TaskPage(activity, session, landscape = landscape) { taskControlsHeight = it }
                                        }
                                    }
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = session.loadingPublic,
                            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                            label = "channel-loading",
                        ) {
                            LoadingOverlay(showHint = !isCompact, blockInput = session.loadingPublic)
                        }
                    }
                }
            }
        }
    }
}

/** NavigationRail has no expansion action; destinations remain a compact vertical group. */
@Composable
private fun LandscapeNavigation(selected: Tab, enabled: Boolean, onSelect: (Tab) -> Unit) {
    val density = LocalDensity.current
    var brandHeightPx by remember { mutableIntStateOf(0) }
    val brandClearance = with(density) { brandHeightPx.toDp() } + 8.dp
    NavigationRail(
        modifier = Modifier.fillMaxHeight().testTag("landscape-navigation"),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        // Overlay independently positioned brand and destinations so the header
        // never displaces the centre of the navigation group.
        BoxWithConstraints(Modifier.width(96.dp).fillMaxHeight()) {
            Column(Modifier.align(Alignment.TopCenter).onSizeChanged { brandHeightPx = it.height }
                .padding(top = 12.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.icon_foreground), contentDescription = null,
                    modifier = Modifier.size(40.dp)
                        .clip(CircleShape).background(colorResource(R.color.icon_background)))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center)
            }
            Column(Modifier.align(Alignment.Center)
                .heightIn(max = (maxHeight - brandClearance * 2).coerceAtLeast(48.dp))
                .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = selected == tab,
                        onClick = { onSelect(tab) },
                        enabled = enabled,
                        modifier = Modifier.keyboardFocus(enabled, MaterialTheme.shapes.large),
                        icon = { Icon(if (selected == tab) tab.selectedIcon else tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}
