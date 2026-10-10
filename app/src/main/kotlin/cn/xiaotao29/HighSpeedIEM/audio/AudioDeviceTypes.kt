package cn.xiaotao29.HighSpeedIEM.audio

/**
 * AudioDeviceInfo types verified against the Android API 37 reference on 2026-10-09:
 * https://developer.android.com/reference/android/media/AudioDeviceInfo
 * Type 28 is documented in AOSP but hidden from the public SDK:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioDeviceInfo.java
 * Numeric IDs are stable across Android versions; this lookup needs no newer runtime APIs.
 */
internal object AudioDeviceTypes {
    private data class Definition(val name: String, val label: String)

    private val definitions = mapOf(
        0 to Definition("TYPE_UNKNOWN", "未知设备"),
        1 to Definition("TYPE_BUILTIN_EARPIECE", "听筒"),
        2 to Definition("TYPE_BUILTIN_SPEAKER", "内置扬声器"),
        3 to Definition("TYPE_WIRED_HEADSET", "有线耳麦"),
        4 to Definition("TYPE_WIRED_HEADPHONES", "有线耳机"),
        5 to Definition("TYPE_LINE_ANALOG", "模拟线路音频"),
        6 to Definition("TYPE_LINE_DIGITAL", "数字线路音频"),
        7 to Definition("TYPE_BLUETOOTH_SCO", "蓝牙通话（SCO）"),
        8 to Definition("TYPE_BLUETOOTH_A2DP", "蓝牙音频（A2DP）"),
        9 to Definition("TYPE_HDMI", "HDMI 音频"),
        10 to Definition("TYPE_HDMI_ARC", "HDMI ARC 音频回传"),
        11 to Definition("TYPE_USB_DEVICE", "USB 音频设备"),
        12 to Definition("TYPE_USB_ACCESSORY", "USB 附件音频"),
        // Before API 34, TYPE_DOCK also includes analog docks.
        13 to Definition("TYPE_DOCK", "扩展坞音频"),
        14 to Definition("TYPE_FM", "FM 音频发射"),
        15 to Definition("TYPE_BUILTIN_MIC", "内置麦克风"),
        16 to Definition("TYPE_FM_TUNER", "FM 调谐器"),
        17 to Definition("TYPE_TV_TUNER", "电视调谐器"),
        18 to Definition("TYPE_TELEPHONY", "电话通话音频"),
        19 to Definition("TYPE_AUX_LINE", "AUX 辅助线路音频"),
        20 to Definition("TYPE_IP", "IP 网络音频"),
        21 to Definition("TYPE_BUS", "音频总线"),
        22 to Definition("TYPE_USB_HEADSET", "USB 耳麦"),
        23 to Definition("TYPE_HEARING_AID", "助听器"),
        24 to Definition("TYPE_BUILTIN_SPEAKER_SAFE", "内置扬声器（安全模式）"),
        25 to Definition("TYPE_REMOTE_SUBMIX", "远程子混音"),
        26 to Definition("TYPE_BLE_HEADSET", "蓝牙 LE 耳机/耳麦"),
        27 to Definition("TYPE_BLE_SPEAKER", "蓝牙 LE 扬声器"),
        28 to Definition("TYPE_ECHO_REFERENCE", "回声消除参考"),
        29 to Definition("TYPE_HDMI_EARC", "HDMI eARC 增强音频回传"),
        30 to Definition("TYPE_BLE_BROADCAST", "蓝牙 LE 广播组"),
        31 to Definition("TYPE_DOCK_ANALOG", "模拟扩展坞音频"),
        32 to Definition("TYPE_MULTICHANNEL_GROUP", "多声道扬声器组"),
        33 to Definition("TYPE_BLE_HEARING_AID", "蓝牙 LE 助听器"),
        // The central is the peer when Android itself operates as an audio peripheral.
        // TYPE_BLE_CENTRAL covers LE Audio, A2DP and HFP, despite its BLE prefix.
        34 to Definition("TYPE_BLE_CENTRAL", "蓝牙音频中心设备"),
        35 to Definition("TYPE_BLE_CENTRAL_BROADCAST", "蓝牙 LE 广播中心设备"),
    )

    fun label(type: Int, runtimeName: String? = null): String =
        definitions[type]?.label ?: rawName(type, runtimeName)

    /** Preserve a future/vendor system constant, even when its translation is not yet known. */
    fun rawName(type: Int, runtimeName: String? = null): String =
        "${runtimeName ?: definitions[type]?.name ?: "TYPE_UNRECOGNIZED"}($type)"
}
