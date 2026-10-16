package cn.xiaotao29.HighSpeedIEM

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import cn.xiaotao29.HighSpeedIEM.ui.RelayApp

/**
 * Single-activity Compose host. Wires runtime permissions, the battery
 * settings shortcut and foreground-service starts; all relay state lives in
 * [RelaySession].
 */
class MainActivity : ComponentActivity() {

    private val session: RelaySession by lazy { (application as MainApplication).session }
    private var permissionName = Manifest.permission.RECORD_AUDIO
    private var permissionAction: (() -> Unit)? = null
    private var startupPermissionsDone = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = permissionAction
            permissionAction = null
            if (granted) {
                safe(action)
            } else {
                val error = "PackageManager.PERMISSION_DENIED: [$permissionName]"
                if (session.busy) session.stopForwarding(error) else session.showError(error)
            }
            // Refresh lists: the mic permission gates some input device info.
            session.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is required for reliable status-bar hiding in landscape.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // enableEdgeToEdge's default scrim styles enforce a system contrast
            // layer behind the bars; the theme background extends under them
            // instead, so the scrim is disabled (see also styles.xml).
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // Draw into cutout areas on the short edges: the top edge in portrait
        // and the left/right edge in both landscape rotations. Content avoids
        // the cutout through insets, not through letterboxing. The attribute
        // only exists from API 28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        handleIntent(intent)
        setContent {
            RelayApp(activity = this, session = session)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!startupPermissionsDone) {
            startupPermissionsDone = true
            session.refresh()
            requestStartupPermissions()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getIntExtra(ForwardingService.EXTRA_TAB, -1) == ForwardingService.TAB_FORWARD) {
            session.pendingTab = ForwardingService.TAB_FORWARD
        }
    }

    /** Ask for microphone + notification access once per activity instance. */
    private fun requestStartupPermissions() {
        permission(Manifest.permission.RECORD_AUDIO) {
            if (Build.VERSION.SDK_INT >= 33) {
                permission(Manifest.permission.POST_NOTIFICATIONS) { }
            }
        }
    }

    // ---- invoked from Compose ----

    fun safe(block: (() -> Unit)?) {
        try {
            block?.invoke()
        } catch (e: Exception) {
            session.stopForwarding(e.toString())
        } catch (e: LinkageError) {
            session.stopForwarding(e.toString())
        }
    }

    fun permission(permission: String, action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        permissionName = permission
        permissionAction = action
        permissionLauncher.launch(permission)
    }

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> true

            else -> false
        }
    }

    fun startForwarding() {
        if (session.busy || session.forwarding) return
        val error = session.prepareStartError()
        if (error != null) {
            session.showError(error)
            return
        }
        permissionsForForward {
            safe {
                ContextCompat.startForegroundService(
                    this, Intent(this, ForwardingService::class.java)
                )
            }
        }
    }

    private fun permissionsForForward(action: () -> Unit) {
        val input = session.selectedInputId?.let { session.inputDevices[it] }
        val output = session.selectedOutputId?.let { session.outputDevices[it] }
        if (Build.VERSION.SDK_INT >= 31 && (isBluetooth(input) || isBluetooth(output)) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permission(Manifest.permission.BLUETOOTH_CONNECT) { permissionsForForward(action) }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permission(Manifest.permission.POST_NOTIFICATIONS) { permissionsForForward(action) }
            return
        }
        permission(Manifest.permission.RECORD_AUDIO, action)
    }

    fun stopForwarding() {
        session.stopForwarding(null, clearSelection = false)
    }

    fun batterySettingsAvailable(): Boolean = safeResolve {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .resolveActivity(packageManager) != null
    }

    fun launchBatterySettings() {
        safe { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun safeResolve(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: Exception) {
            false
        }
}
