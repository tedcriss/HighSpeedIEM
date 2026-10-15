package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.R
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/**
 * App root. App-level navigation is owned by [NavigationSuiteScaffold]
 * (bottom navigation bar in portrait, wide navigation rail in landscape, per
 * the current Material 3 guidance); each destination hosts its own page
 * [Scaffold] for the portrait title bar, snackbar host and page content.
 */
@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RelayApp(activity: MainActivity, session: RelaySession) {
    HighSpeedIEMTheme {
        val portrait =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(session.pendingTab) {
            session.pendingTab?.let {
                tab = it
                session.pendingTab = null
            }
        }

        // Errors surface verbatim in a snackbar; its text wraps (no marquee).
        LaunchedEffect(session.errorText) {
            val message = session.errorText ?: return@LaunchedEffect
            session.consumeError()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }

        NavigationSuiteScaffold(
            navigationItems = {
                NavigationSuiteItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text("输入设备", maxLines = 1) },
                )
                NavigationSuiteItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    label = { Text("输出设备", maxLines = 1) },
                )
                NavigationSuiteItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                    label = { Text("转发控制", maxLines = 1) },
                )
            },
            navigationSuiteType = if (portrait) {
                NavigationSuiteType.NavigationBar
            } else {
                NavigationSuiteType.WideNavigationRailExpanded
            },
        ) {
            Scaffold(
                // App title row; the suite rail has no brand header slot, so
                // the bar carries the brand in both orientations.
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        title = {
                            Column {
                                MarqueeText(
                                    stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                MarqueeText(
                                    stringResource(R.string.developer_credit),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                },
                snackbarHost = {
                    SnackbarHost(snackbarHostState, Modifier.padding(bottom = 96.dp))
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                        },
                        label = "tab-content",
                        modifier = Modifier.fillMaxSize(),
                    ) { current ->
                        when (current) {
                            0 -> DevicesPage(session, input = true)
                            1 -> DevicesPage(session, input = false)
                            else -> ForwardPage(activity, session)
                        }
                    }
                    if (session.busy) BlockingOverlay()
                }
            }
        }
    }
}

/**
 * Modal scrim shown while the engine opens or closes streams. Swallows all
 * pointer input and takes focus so no control underneath can be operated.
 */
@Composable
private fun BlockingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) { /* consumes every pointer event */ }
            .focusable()
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
