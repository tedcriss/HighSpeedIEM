package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Root composable. Standard layout: fixed small app bar over three navigation
 * destinations. Low-resolution screens and watch devices switch to the compact
 * swiped pager instead.
 *
 * Session mutations are tracked through [MonitorSession.version]; the value is
 * read in this scope so navigation and page content stay in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorApp(activity: MainActivity, session: MonitorSession) {
    // Read in this composition scope (not inside a nested lambda) so every
    // session mutation recomposes the tab selection along with the current page.
    @Suppress("UNUSED_VARIABLE") val version = session.version.collectAsState().value
    val notice = session.notices.collectAsState().value
    val snackbar = remember { SnackbarHostState() }
    var taskControlsHeight by remember { mutableStateOf(0.dp) }

    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val isCompact = remember(configuration, windowSize) {
        // Low-resolution devices: any screen dimension <= 600 physical pixels,
        // plus every watch-class device regardless of resolution.
        val uiMode = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val watchType = uiMode == android.content.res.Configuration.UI_MODE_TYPE_WATCH
        // Actual pixels avoid DP rounding classifying a 600px screen as 601px.
        watchType || windowSize.width in 1..600 || windowSize.height in 1..600
    }

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
                        selectedTab == Tab.TASK -> taskControlsHeight + 8.dp
                        else -> 8.dp
                    }
                    SnackbarHost(snackbar, Modifier.padding(bottom = noticeInset)) { data -> NoticeSnackbar(data) }
                },
                topBar = {
                    if (!isCompact) {
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
                    if (!isCompact) {
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
                Box(Modifier.padding(padding).fillMaxSize()) {
                    if (isCompact) {
                        CompactFlow(activity, session)
                    } else {
                        when (selectedTab) {
                            Tab.INPUT -> InputPage(activity, session)
                            Tab.OUTPUT -> OutputPage(activity, session)
                            Tab.TASK -> TaskPage(activity, session) { taskControlsHeight = it }
                        }
                    }
                    if (session.loadingPublic) LoadingOverlay(showHint = !isCompact)
                }
            }
        }
    }
}
