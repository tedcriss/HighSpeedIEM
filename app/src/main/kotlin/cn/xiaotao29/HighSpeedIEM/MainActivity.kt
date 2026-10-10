package cn.xiaotao29.HighSpeedIEM

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cn.xiaotao29.HighSpeedIEM.audio.Channel
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import cn.xiaotao29.HighSpeedIEM.ui.MonitorApp

/**
 * Single-activity Compose host. All UI state flows from [MonitorSession];
 * this class only wires permissions, lifecycle and service starts.
 */
class MainActivity : ComponentActivity(), MonitorSession.Listener {

    private val session by lazy { (application as MonitorApplication).session() }
    private var permissionAction: (() -> Unit)? = null
    private var permissionTestKey: String? = null
    private var discoveryAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = permissionAction
            val channel = permissionTestKey
            permissionAction = null
            permissionTestKey = null
            if (granted && action != null) safe(action)
            else if (!granted) {
                val error = "PackageManager.PERMISSION_DENIED: [android.permission.$permissionName]"
                if (channel != null) session.channelError(channel, error)
                else if (session.startingPublic) session.stop(error, false)
                else session.errorNotice(error)
            }
            session.refresh()
        }

    private val discoveryLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val done = discoveryAction
            discoveryAction = null
            session.permissionWaitingPublic = false
            done?.invoke() ?: session.refresh()
        }

    private var permissionName: String = Manifest.permission.RECORD_AUDIO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.listener = this
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        if (intent?.getBooleanExtra("task_tab", false) == true) {
            session.clearSelectedInput()
            session.tab = 2
        }
        setContent {
            MonitorApp(activity = this, session = session)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("task_tab", false)) session.switchTab(2)
    }

    override fun onResume() {
        super.onResume()
        session.onVisible(true)
        session.listener = this
        session.refresh()
        session.updatePreview()
        session.consumePendingMessages()?.let { session.errorNotice(it) }
    }

    override fun onPause() {
        session.onVisible(false)
        session.stopOutputTest()
        session.updatePreview()
        super.onPause()
    }

    override fun onDestroy() {
        session.listener = null
        if (isFinishing && session.service == null && !session.runningPublic &&
            !session.operationsBlockedPublic) {
            session.stop(null, true)
        }
        super.onDestroy()
    }

    // ---- MonitorSession.Listener ----

    override fun onSessionChanged() { /* Compose reads session state directly */ }

    override fun onSpectrum(bins: FloatArray) { /* routed through the session state flow in ui */ }

    override fun onMessage(text: String) = session.errorNotice(text)

    override fun onFloatingMessage(text: String) = session.floatingNotice(text)

    override fun onDiscoveryPermission(done: () -> Unit) {
        discoveryAction = done
        discoveryLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ---- helpers invoked from Compose ----

    fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            handleFailure(e.toString())
        } catch (e: LinkageError) {
            handleFailure(e.toString())
        }
    }

    private fun handleFailure(error: String) {
        val channel = permissionTestKey
        permissionTestKey = null
        permissionAction = null
        if (channel != null) session.channelError(channel, error)
        else session.stop(error, false)
    }

    fun isBluetooth(channel: Channel): Boolean {
        val t = channel.device.type
        return t == 7 || t == 8 || t == 26 || t == 27 || t == 30
    }

    fun permission(permission: String, action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        permissionName = permission
        permissionAction = action
        permissionLauncher.launch(permission)
    }

    fun previewPermissions(channel: Channel) {
        permissionTestKey = channel.key
        if (Build.VERSION.SDK_INT >= 31 && isBluetooth(channel) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            permission(Manifest.permission.BLUETOOTH_CONNECT) { previewPermissions(channel) }
            return
        }
        permission(Manifest.permission.RECORD_AUDIO) {
            permissionTestKey = null
            session.resetPreview()
            session.updatePreview()
        }
    }

    fun permissionsForRoute(action: () -> Unit) {
        for (row in session.model.rows) {
            if (!row.complete()) continue
            for (channel in listOfNotNull(session.inputs[row.input], session.outputs[row.output])) {
                if (Build.VERSION.SDK_INT >= 31 && isBluetooth(channel) &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED) {
                    permission(Manifest.permission.BLUETOOTH_CONNECT) { permissionsForRoute(action) }
                    return
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            permission(Manifest.permission.POST_NOTIFICATIONS) { permissionsForRoute(action) }
            return
        }
        permission(Manifest.permission.RECORD_AUDIO, action)
    }

    fun startForwarding() {
        if (session.runningPublic || session.startingPublic || session.loadingPublic ||
            session.previewOpeningPublic || session.busyPublic) return
        session.model.prune(session.enabledChannels(session.inputs), session.enabledChannels(session.outputs))
        if (session.model.validCount() == 0) {
            session.errorNotice("请先选择至少一对有效输入与输出通道")
            return
        }
        session.prepareForwarding {
            permissionsForRoute {
                if (session.startingPublic && !session.loadingPublic) {
                    startForegroundService(Intent(this, ForwardingService::class.java))
                }
            }
        }
    }

    fun permissionTestFor(channel: cn.xiaotao29.HighSpeedIEM.audio.Channel) {
        val selectionToken = session.outputSelectionToken
        permissionTestKey = channel.key
        permission(Manifest.permission.BLUETOOTH_CONNECT) {
            permissionTestKey = null
            if (session.selectedOutput == channel.key && session.outputSelectionToken == selectionToken) {
                session.ringtone()
            }
        }
    }

    fun openBatterySettings(): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return intent.resolveActivity(packageManager) != null
    }

    fun launchBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
