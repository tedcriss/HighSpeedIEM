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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
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
        // Keep dialog state outside the portrait/landscape navigation branches.
        var mixingOutput by rememberSaveable { mutableStateOf<Int?>(null) }
        val deviceMenu = rememberSaveable(saver = DeviceMenuState.Saver) { DeviceMenuState() }
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(session.pendingTab) {
            session.pendingTab?.let {
                tab = it
                session.pendingTab = null
            }
        }

        // Landscape runs immersive: the system status bar is hidden there and
        // restored in portrait. Re-asserted on every resume so dialog windows
        // and background/foreground transitions cannot leave stale bar state.
        LaunchedEffect(portrait) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
        }

        // Retry notices include the raw failure; keep only the newest during a
        // retry loop. Other errors remain queued and take priority over telemetry.
        LaunchedEffect(session, activity) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow {
                    val restart = session.restartNotice
                    val error = session.pendingError
                    when {
                        restart != null -> Triple(restart, null, null)
                        error != null -> Triple(null, error, null)
                        else -> Triple(null, null, session.pendingStatus)
                    }
                }.collectLatest { (restart, error, status) ->
                    if (restart != null) {
                        snackbarHostState.showSnackbar("自动重启中\n${restart.text}", actionLabel = "关闭", duration = SnackbarDuration.Long)
                        session.consumeRestartNotice(restart.id)
                    } else if (error != null) {
                        snackbarHostState.showSnackbar(error.text, actionLabel = "关闭", duration = SnackbarDuration.Long)
                        session.consumeError(error.id)
                    } else if (status != null) {
                        snackbarHostState.showSnackbar(status.text, duration = SnackbarDuration.Short)
                        session.consumeStatus(status.sequence)
                    }
                }
            }
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
                            0 -> DevicesPage(session, input = true, menu = deviceMenu)
                            1 -> DevicesPage(session, input = false, menu = deviceMenu)
                            else -> ForwardPage(activity, session, onEditMixer = { mixingOutput = it })
                        }
                    }
                    if (session.busy) BlockingOverlay()
                }
            }
        }

        // Cutout and curved-edge avoidance is consumed once, above both
        // navigation branches: the background paints edge to edge first, then
        // the padding moves the suite/rail and the page content into the safe
        // area for whichever edge the cutout rotates to (top in portrait,
        // left or right in the two landscape rotations). Consuming here also
        // stops the inner scaffolds from adding the same margins a second
        // time; system bars stay handled by the scaffolds themselves.
        val cutoutSafeRoot = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(cutoutWaterfallInsets())

        if (portrait) {
            NavigationSuiteScaffold(
                modifier = cutoutSafeRoot,
                navigationItems = {
                    NavigationSuiteItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                        label = { MarqueeText("输入设备") },
                    )
                    NavigationSuiteItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                        label = { MarqueeText("输出设备") },
                    )
                    NavigationSuiteItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                        label = { MarqueeText("转发控制") },
                    )
                },
                navigationSuiteType = NavigationSuiteType.NavigationBar,
            ) {
                pageScaffold(Modifier)
            }
        } else {
            Row(cutoutSafeRoot) {
                LandscapeNavigation(selected = tab) { tab = it }
                pageScaffold(Modifier.weight(1f))
            }
        }
        mixingOutput?.let { channel ->
            MixerDialog(session, channel, onDismiss = { mixingOutput = null })
        }
        val device = deviceMenu.deviceId?.let {
            (if (deviceMenu.input) session.inputDevices else session.outputDevices)[it]
        }
        LaunchedEffect(device, session.operationsBlocked) {
            if (deviceMenu.panel != null && (device == null || session.operationsBlocked)) deviceMenu.dismiss()
        }
        val infoPanel = deviceMenu.panel?.takeIf { it != DevicePanel.Params }
        if (device != null && infoPanel != null) {
            key(device.id, deviceMenu.input, infoPanel) {
                DeviceInfoDialog(
                    device, infoPanel,
                    onDismiss = deviceMenu::dismiss,
                    onMenu = { deviceMenu.next(deviceMenu.input, device.id) },
                )
            }
        }
        if (deviceMenu.panel == DevicePanel.Params && device != null) {
            StreamParamsDialog(
                session, deviceMenu.input, device,
                onDismiss = deviceMenu::dismiss,
                onMenu = { deviceMenu.next(deviceMenu.input, device.id) },
                requestInitialFocus = deviceMenu.remote,
            )
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
        // The landscape Row in RelayApp consumes the cutout/waterfall insets
        // for the whole screen and the page Scaffold consumes the system bars,
        // so the rail must not add either a second time.
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
                    label = { MarqueeText("输入设备") },
                )
                NavigationRailItem(
                    selected = selected == 1,
                    onClick = { onSelect(1) },
                    icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    label = { MarqueeText("输出设备") },
                )
                NavigationRailItem(
                    selected = selected == 2,
                    onClick = { onSelect(2) },
                    icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                    label = { MarqueeText("转发控制") },
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
