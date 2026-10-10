package cn.xiaotao29.HighSpeedIEM.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioDeviceTypesTest {
    @Test
    fun everyCompileSdkDeviceTypeHasAChineseLabel() {
        // Independent SDK inventory: fails if upgrading the SDK adds an untranslated type.
        AudioDeviceInfo::class.java.fields.filter { it.name.startsWith("TYPE_") }.forEach { field ->
            val label = AudioDeviceTypes.label(field.getInt(null))
            assertFalse("Missing translation for ${field.name}", label.startsWith("TYPE_"))
            assertFalse("Empty translation for ${field.name}", label.isBlank())
        }
    }

    @Test
    fun knownRawNamesMatchCompileSdkConstants() {
        AudioDeviceInfo::class.java.fields.filter { it.name.startsWith("TYPE_") }.forEach { field ->
            val type = field.getInt(null)
            assertEquals("${field.name}($type)", AudioDeviceTypes.rawName(type))
        }
    }

    @Test
    fun hiddenEchoReferenceHasBothLabelAndRawIdentity() {
        assertEquals("回声消除参考", AudioDeviceTypes.label(28))
        assertEquals("TYPE_ECHO_REFERENCE(28)", AudioDeviceTypes.rawName(28))
    }

    @Test
    fun unrecognizedTypePreservesRuntimeNameAndNumber() {
        assertEquals("TYPE_VENDOR_DEVICE(10000)", AudioDeviceTypes.label(10000, "TYPE_VENDOR_DEVICE"))
        assertEquals("TYPE_UNRECOGNIZED(10000)", AudioDeviceTypes.label(10000))
    }

    @Test
    fun knownTypesPreferTranslationOverRawRuntimeName() {
        assertEquals("内置麦克风", AudioDeviceTypes.label(15, "TYPE_BUILTIN_MIC"))
    }
}
