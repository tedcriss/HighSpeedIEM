package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.R
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/**
 * App root. Portrait uses the official NavigationSuiteScaffold (bottom
 * navigation bar) around the page Scaffold. Landscape uses the app's narrow
 * navigation rail: brand icon and name on top, destinations centered with
 * labels under their icons — the suite rail types place labels beside the
 * icon and expose no brand header, so the rail stays hand-built there.
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

        // Landscape runs immersive: the system status bar is hidden there and
        // restored in portrait (activity recreation re-runs this effect).
        LaunchedEffect(portrait) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (portrait) {
                controller.show(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            }
        }

        // Errors surface verbatim in a snackbar; its text wraps (no marquee).
        LaunchedEffect(session.errorText) {
            val message = session.errorText ?: return@LaunchedEffect
            session.consumeError()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }

        val pageScaffold: @Composable (Modifier) -> Unit = { modifier ->
            Scaffold(
                modifier = modifier,
                // Portrait-only app title row; landscape carries the brand in
                // the rail header instead.
                topBar = {
                    if (portrait) {
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
                    }
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

        if (portrait) {
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
                navigationSuiteType = NavigationSuiteType.NavigationBar,
            ) {
                pageScaffold(Modifier)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                LandscapeNavigation(selected = tab) { tab = it }
                pageScaffold(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Landscape navigation rail: brand icon and app name on top, the three tab
 * destinations centered vertically with labels under their icons.
 */
@Composable
private fun LandscapeNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Box(Modifier.width(96.dp).fillMaxHeight()) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.icon_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.icon_background)),
                )
                MarqueeText(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavigationRailItem(
                    selected = selected == 0,
                    onClick = { onSelect(0) },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text("输入设备", maxLines = 1) },
                )
                NavigationRailItem(
                    selected = selected == 1,
                    onClick = { onSelect(1) },
                    icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    label = { Text("输出设备", maxLines = 1) },
                )
                NavigationRailItem(
                    selected = selected == 2,
                    onClick = { onSelect(2) },
                    icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                    label = { Text("转发控制", maxLines = 1) },
                )
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
