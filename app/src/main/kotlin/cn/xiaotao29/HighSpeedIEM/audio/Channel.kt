package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioDeviceInfo
import android.os.Build
/** One selectable channel row: a device plus one channel index. */
data class Channel(
    val device: AudioDeviceInfo,
    val index: Int,
    val total: Int,
    val input: Boolean,
    /** Probed channel count, kept for the "opened=" attribute line. */
    val probedChannels: Int = 0,
) {
    val key: String = (if (input) "i" else "o") + device.id + ":" + index
    val deviceKey: String = (if (input) "i" else "o") + device.id
    val name: String = device.productName.toString() + " · id=" + device.id + " · " + typeName + " · channel=" + index

    val typeName: String
        get() = AudioDeviceTypes.rawName(device.type, TYPE_NAMES[device.type])

    val typeLabel: String
        get() = AudioDeviceTypes.label(device.type, TYPE_NAMES[device.type])
    /** Ordered capability entries; keep the platform's original names and values. */
    val capabilities: List<Pair<String, String>>
        get() = buildList {
            add("id" to device.id.toString())
            add("type" to typeName)
            add("address" to if (Build.VERSION.SDK_INT >= 28) device.address else "")
            add("channelCounts" to device.channelCounts.contentToString())
            if (probedChannels > 0) add("opened" to probedChannels.toString())
            add("sampleRates" to device.sampleRates.contentToString())
            add("encodings" to device.encodings.contentToString())
        }

    companion object {
        /** Reflective AudioDeviceInfo.TYPE_* lookup, independent of compile-time SDK constants. */
        private val TYPE_NAMES: Map<Int, String> by lazy {
            val map = HashMap<Int, String>()
            for (field in AudioDeviceInfo::class.java.fields) {
                if (field.name.startsWith("TYPE_")) {
                    try {
                        map[field.getInt(null)] = field.name
                    } catch (ignored: IllegalAccessException) {
                    }
                }
            }
            map
        }
    }
}
