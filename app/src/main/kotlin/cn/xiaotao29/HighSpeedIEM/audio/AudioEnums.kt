package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioFormat

/**
 * AAudio constants mirrored from the NDK aaudio/AAudio.h header (values are
 * ABI-stable) plus readable enum-name mappings for the UI. UI names omit the
 * common AAudio prefix (e.g. PCM_I16); unknown values retain their number.
 */
object AAudio {
    const val FORMAT_UNSPECIFIED = 0
    const val FORMAT_PCM_I16 = 1
    const val FORMAT_PCM_FLOAT = 2
    const val FORMAT_PCM_I24_PACKED = 3
    const val FORMAT_PCM_I32 = 4

    const val DIRECTION_OUTPUT = 0
    const val DIRECTION_INPUT = 1

    const val PERFORMANCE_NONE = 10
    const val PERFORMANCE_POWER_SAVING = 11
    const val PERFORMANCE_LOW_LATENCY = 12

    const val PRESET_GENERIC = 1
    const val PRESET_CAMCORDER = 5
    const val PRESET_VOICE_RECOGNITION = 6
    const val PRESET_VOICE_COMMUNICATION = 7
    const val PRESET_UNPROCESSED = 9
    const val PRESET_VOICE_PERFORMANCE = 10
    const val PRESET_SYSTEM_ECHO_REFERENCE = 1997
    const val PRESET_SYSTEM_HOTWORD = 1999

    const val USAGE_MEDIA = 1
    const val USAGE_VOICE_COMMUNICATION = 2
    const val USAGE_VOICE_COMMUNICATION_SIGNALLING = 3
    const val USAGE_ALARM = 4
    const val USAGE_NOTIFICATION = 5
    const val USAGE_NOTIFICATION_RINGTONE = 6
    const val USAGE_NOTIFICATION_EVENT = 10
    const val USAGE_ASSISTANCE_ACCESSIBILITY = 11
    const val USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12
    const val USAGE_ASSISTANCE_SONIFICATION = 13
    const val USAGE_GAME = 14
    const val USAGE_ASSISTANT = 16

    const val CONTENT_SPEECH = 1
    const val CONTENT_MUSIC = 2
    const val CONTENT_MOVIE = 3
    const val CONTENT_SONIFICATION = 4

    val PERFORMANCE_MODES = listOf(PERFORMANCE_NONE, PERFORMANCE_POWER_SAVING, PERFORMANCE_LOW_LATENCY)
    val INPUT_PRESETS = listOf(
        PRESET_GENERIC, PRESET_CAMCORDER, PRESET_VOICE_RECOGNITION, PRESET_VOICE_COMMUNICATION,
        PRESET_UNPROCESSED, PRESET_VOICE_PERFORMANCE, PRESET_SYSTEM_ECHO_REFERENCE,
        PRESET_SYSTEM_HOTWORD,
    )
    val USAGES = listOf(
        USAGE_MEDIA, USAGE_VOICE_COMMUNICATION, USAGE_VOICE_COMMUNICATION_SIGNALLING, USAGE_ALARM,
        USAGE_NOTIFICATION, USAGE_NOTIFICATION_RINGTONE, USAGE_NOTIFICATION_EVENT,
        USAGE_ASSISTANCE_ACCESSIBILITY, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        USAGE_ASSISTANCE_SONIFICATION, USAGE_GAME, USAGE_ASSISTANT,
    )
    val CONTENT_TYPES = listOf(CONTENT_SPEECH, CONTENT_MUSIC, CONTENT_MOVIE, CONTENT_SONIFICATION)

    /** Powers of two in [0, 2^14] for the buffer capacity dropdown. */
    val BUFFER_CAPACITIES = listOf(0) + (0..14).map { 1 shl it }

    fun formatName(value: Int): String = when (value) {
        FORMAT_UNSPECIFIED -> "UNSPECIFIED"
        FORMAT_PCM_I16 -> "PCM_I16"
        FORMAT_PCM_FLOAT -> "PCM_FLOAT"
        FORMAT_PCM_I24_PACKED -> "PCM_I24_PACKED"
        FORMAT_PCM_I32 -> "PCM_I32"
        else -> "UNKNOWN($value)"
    }

    fun performanceName(value: Int): String = when (value) {
        PERFORMANCE_NONE -> "NONE"
        PERFORMANCE_POWER_SAVING -> "POWER_SAVING"
        PERFORMANCE_LOW_LATENCY -> "LOW_LATENCY"
        else -> "UNKNOWN($value)"
    }

    fun presetName(value: Int): String = when (value) {
        PRESET_GENERIC -> "GENERIC"
        PRESET_CAMCORDER -> "CAMCORDER"
        PRESET_VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        PRESET_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        PRESET_UNPROCESSED -> "UNPROCESSED"
        PRESET_VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
        PRESET_SYSTEM_ECHO_REFERENCE -> "SYSTEM_ECHO_REFERENCE"
        PRESET_SYSTEM_HOTWORD -> "SYSTEM_HOTWORD"
        else -> "UNKNOWN($value)"
    }

    fun usageName(value: Int): String = when (value) {
        USAGE_MEDIA -> "MEDIA"
        USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_COMMUNICATION_SIGNALLING"
        USAGE_ALARM -> "ALARM"
        USAGE_NOTIFICATION -> "NOTIFICATION"
        USAGE_NOTIFICATION_RINGTONE -> "NOTIFICATION_RINGTONE"
        USAGE_NOTIFICATION_EVENT -> "NOTIFICATION_EVENT"
        USAGE_ASSISTANCE_ACCESSIBILITY -> "ASSISTANCE_ACCESSIBILITY"
        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "ASSISTANCE_NAVIGATION_GUIDANCE"
        USAGE_ASSISTANCE_SONIFICATION -> "ASSISTANCE_SONIFICATION"
        USAGE_GAME -> "GAME"
        USAGE_ASSISTANT -> "ASSISTANT"
        else -> "UNKNOWN($value)"
    }

    fun contentTypeName(value: Int): String = when (value) {
        CONTENT_SPEECH -> "SPEECH"
        CONTENT_MUSIC -> "MUSIC"
        CONTENT_MOVIE -> "MOVIE"
        CONTENT_SONIFICATION -> "SONIFICATION"
        else -> "UNKNOWN($value)"
    }

    /**
     * Map an [AudioFormat] ENCODING_* constant reported by AudioDeviceInfo to
     * the matching AAudio format; null for encodings the relay cannot carry.
     */
    fun formatFromEncoding(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> FORMAT_PCM_I16
        AudioFormat.ENCODING_PCM_FLOAT -> FORMAT_PCM_FLOAT
        21 -> FORMAT_PCM_I24_PACKED   // AudioFormat.ENCODING_PCM_24BIT_PACKED (API 31)
        22 -> FORMAT_PCM_I32          // AudioFormat.ENCODING_PCM_32BIT (API 31)
        else -> null
    }

    /** Readable name for an AudioFormat ENCODING_* constant (capability view). */
    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_INVALID -> "ENCODING_INVALID"
        AudioFormat.ENCODING_DEFAULT -> "ENCODING_DEFAULT"
        AudioFormat.ENCODING_PCM_16BIT -> "ENCODING_PCM_16BIT"
        AudioFormat.ENCODING_PCM_8BIT -> "ENCODING_PCM_8BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "ENCODING_PCM_FLOAT"
        21 -> "ENCODING_PCM_24BIT_PACKED"
        22 -> "ENCODING_PCM_32BIT"
        AudioFormat.ENCODING_AC3 -> "ENCODING_AC3"
        AudioFormat.ENCODING_E_AC3 -> "ENCODING_E_AC3"
        AudioFormat.ENCODING_IEC61937 -> "ENCODING_IEC61937"
        AudioFormat.ENCODING_DSD -> "ENCODING_DSD"
        AudioFormat.ENCODING_MP3 -> "ENCODING_MP3"
        AudioFormat.ENCODING_AAC_LC -> "ENCODING_AAC_LC"
        AudioFormat.ENCODING_AAC_HE_V1 -> "ENCODING_AAC_HE_V1"
        AudioFormat.ENCODING_AAC_HE_V2 -> "ENCODING_AAC_HE_V2"
        AudioFormat.ENCODING_OPUS -> "ENCODING_OPUS"
        else -> "ENCODING_UNKNOWN($encoding)"
    }
}

/** Formats an array as "[a, b, c]" using the given enum-name mapper. */
fun listText(values: IntArray, name: (Int) -> String = { it.toString() }): String =
    if (values.isEmpty()) "[]" else values.joinToString(prefix = "[", postfix = "]") { name(it) }
