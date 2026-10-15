package cn.xiaotao29.HighSpeedIEM.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/** App root: three tabs, snackbar errors, and the blocking overlay. */
@Composable
fun RelayApp(activity: MainActivity, session: RelaySession) {
    HighSpeedIEMTheme {
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

        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState, Modifier.padding(bottom = 96.dp))
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                        label = { Text("输入设备", maxLines = 1) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                        label = { Text("输出设备", maxLines = 1) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                        label = { Text("转发控制", maxLines = 1) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                PageHeading(
                    title = when (tab) {
                        0 -> "输入设备"
                        1 -> "输出设备"
                        else -> "转发控制"
                    },
                    description = when (tab) {
                        0 -> "选中一个设备作为转发输入源，卡片内可查看设备能力与开流参数。"
                        1 -> "选中一个设备作为转发输出目标，选中状态跨 tab 页保留。"
                        else -> "按输出通道配置混音输入，启动后由前台服务后台常驻转发。"
                    },
                    status = when (tab) {
                        0 -> session.selectedInputId?.let { "已选中 ID $it" } ?: "未选中"
                        1 -> session.selectedOutputId?.let { "已选中 ID $it" } ?: "未选中"
                        else -> if (session.forwarding) "转发中" else "未转发"
                    },
                )
                Box(Modifier.fillMaxSize()) {
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
