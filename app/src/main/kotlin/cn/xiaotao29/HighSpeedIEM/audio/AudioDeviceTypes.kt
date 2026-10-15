package cn.xiaotao29.HighSpeedIEM.audio

/**
 * AudioDeviceInfo.TYPE_* numeric IDs are ABI-stable across Android versions,
 * so this table maps them without needing newer runtime APIs. Unknown or
 * future vendor types fall back to their raw constant name.
 */
object AudioDeviceTypes {
    private val definitions = mapOf(
        0 to ("TYPE_UNKNOWN" to "未知设备"),
        1 to ("TYPE_BUILTIN_EARPIECE" to "听筒"),
        2 to ("TYPE_BUILTIN_SPEAKER" to "内置扬声器"),
        3 to ("TYPE_WIRED_HEADSET" to "有线耳麦"),
        4 to ("TYPE_WIRED_HEADPHONES" to "有线耳机"),
        5 to ("TYPE_LINE_ANALOG" to "模拟线路音频"),
        6 to ("TYPE_LINE_DIGITAL" to "数字线路音频"),
        7 to ("TYPE_BLUETOOTH_SCO" to "蓝牙通话（SCO）"),
        8 to ("TYPE_BLUETOOTH_A2DP" to "蓝牙音频（A2DP）"),
        9 to ("TYPE_HDMI" to "HDMI 音频"),
        10 to ("TYPE_HDMI_ARC" to "HDMI ARC 音频回传"),
        11 to ("TYPE_USB_DEVICE" to "USB 音频设备"),
        12 to ("TYPE_USB_ACCESSORY" to "USB 附件音频"),
        // Before API 34, TYPE_DOCK also includes analog docks.
        13 to ("TYPE_DOCK" to "扩展坞音频"),
        14 to ("TYPE_FM" to "FM 音频发射"),
        15 to ("TYPE_BUILTIN_MIC" to "内置麦克风"),
        16 to ("TYPE_FM_TUNER" to "FM 调谐器"),
        17 to ("TYPE_TV_TUNER" to "电视调谐器"),
        18 to ("TYPE_TELEPHONY" to "电话通话音频"),
        19 to ("TYPE_AUX_LINE" to "AUX 辅助线路音频"),
        20 to ("TYPE_IP" to "IP 网络音频"),
        21 to ("TYPE_BUS" to "音频总线"),
        22 to ("TYPE_USB_HEADSET" to "USB 耳麦"),
        23 to ("TYPE_HEARING_AID" to "助听器"),
        24 to ("TYPE_BUILTIN_SPEAKER_SAFE" to "内置扬声器（安全模式）"),
        25 to ("TYPE_REMOTE_SUBMIX" to "远程子混音"),
        26 to ("TYPE_BLE_HEADSET" to "蓝牙 LE 耳机/耳麦"),
        27 to ("TYPE_BLE_SPEAKER" to "蓝牙 LE 扬声器"),
        28 to ("TYPE_ECHO_REFERENCE" to "回声消除参考"),
        29 to ("TYPE_HDMI_EARC" to "HDMI eARC 增强音频回传"),
        30 to ("TYPE_BLE_BROADCAST" to "蓝牙 LE 广播组"),
        31 to ("TYPE_DOCK_ANALOG" to "模拟扩展坞音频"),
        32 to ("TYPE_MULTICHANNEL_GROUP" to "多声道扬声器组"),
        33 to ("TYPE_BLE_HEARING_AID" to "蓝牙 LE 助听器"),
        // The central is the peer when Android itself acts as an audio peripheral.
        34 to ("TYPE_BLE_CENTRAL" to "蓝牙音频中心设备"),
        35 to ("TYPE_BLE_CENTRAL_BROADCAST" to "蓝牙 LE 广播中心设备"),
    )

    /** Human-readable Chinese label for subtitles. */
    fun label(type: Int): String = definitions[type]?.second ?: rawName(type)

    /** Raw enum constant name for the capability view (no friendly mapping). */
    fun rawName(type: Int): String = definitions[type]?.first ?: "TYPE_UNRECOGNIZED($type)"
}
