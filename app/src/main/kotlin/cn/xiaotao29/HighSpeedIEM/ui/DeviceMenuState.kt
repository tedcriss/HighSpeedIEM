package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

internal enum class DevicePanel { Capabilities, Test, Params }

/** One device action window at a time, owned above the adaptive navigation branches. */
@Stable
internal class DeviceMenuState {
    var deviceId by mutableStateOf<Int?>(null)
        private set
    var input by mutableStateOf(true)
        private set
    var panel by mutableStateOf<DevicePanel?>(null)
        private set
    var remote by mutableStateOf(false)
        private set
    var restoreId by mutableStateOf<Int?>(null)
        private set
    var restoreInput by mutableStateOf(true)
        private set

    fun open(input: Boolean, id: Int, panel: DevicePanel, remote: Boolean = false) {
        this.input = input
        deviceId = id
        this.panel = panel
        this.remote = remote
        restoreId = null
    }

    fun next(input: Boolean, id: Int) {
        val current = panel.takeIf { this.input == input && deviceId == id }
        open(input, id, when (current) {
            null, DevicePanel.Params -> DevicePanel.Capabilities
            DevicePanel.Capabilities -> DevicePanel.Test
            DevicePanel.Test -> DevicePanel.Params
        }, remote = true)
    }

    fun dismiss() {
        restoreId = deviceId.takeIf { remote }
        restoreInput = input
        deviceId = null
        panel = null
        remote = false
    }

    fun focusRestored() { restoreId = null }

    companion object {
        val Saver = listSaver<DeviceMenuState, Any>(
            save = { listOf(it.deviceId ?: -1, it.input, it.panel?.ordinal ?: -1,
                it.remote, it.restoreId ?: -1, it.restoreInput) },
            restore = { saved -> DeviceMenuState().apply {
                deviceId = (saved[0] as Int).takeIf { it >= 0 }
                input = saved[1] as Boolean
                panel = DevicePanel.entries.getOrNull(saved[2] as Int)
                remote = saved[3] as Boolean
                restoreId = (saved[4] as Int).takeIf { it >= 0 }
                restoreInput = saved[5] as Boolean
            } },
        )
    }
}
