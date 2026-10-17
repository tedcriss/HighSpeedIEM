package cn.xiaotao29.HighSpeedIEM.audio

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real native menu-key delivery across Activity, tooltip, dialog and dropdown windows. */
@RunWith(AndroidJUnit4::class)
class DeviceMenuNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: RelaySession
    private var deviceId = 0
    private var input = true

    @Before
    fun launch() {
        instrumentation.setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            session = (it.application as MainApplication).session
            session.stopForwarding(clearSelection = true)
        }
    }

    @After
    fun close() {
        instrumentation.setInTouchMode(true)
        scenario.onActivity {
            session.stopForwarding(clearSelection = true)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        scenario.close()
    }

    @Test
    fun inputCardsSkipChildButtonsAndMenuRestartsAfterEveryBack() {
        prepare(input = true, landscape = false)
        focusCard()
        key(KeyEvent.KEYCODE_TAB)
        await { nodes().any { it.isFocused && containsDevice(it) } && card()?.isFocused == false }
        focusCard()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { nodes().any { it.isFocused && containsDevice(it) } && card()?.isFocused == false }
        focusCard()
        scenario.onActivity { assertNull(session.selectedInputId) }
        // A focused but unselected card supports all three panels and wraps.
        showNext("设备能力 · ID $deviceId")
        showNext("测试设备 · ID $deviceId")
        showNext("输入设备参数 · ID $deviceId")
        showNext("设备能力 · ID $deviceId")
        backToCard()
        scenario.onActivity { assertNull(session.selectedInputId) }
        // Enter selects the card; the menu route is unchanged by selection.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity { assertEquals(deviceId, session.selectedInputId) }
        for (depth in 1..3) {
            showNext("设备能力 · ID $deviceId")
            if (depth >= 2) showNext("测试设备 · ID $deviceId")
            if (depth >= 3) showNext("输入设备参数 · ID $deviceId")
            backToCard()
        }
        showNext("设备能力 · ID $deviceId")
        backToCard()
    }

    @Test
    fun outputLandscapeScrollsAndMenuEscapesNestedDropdownAfterRotation() {
        prepare(input = false, landscape = true)
        focusCard()
        showNext("设备能力 · ID $deviceId")
        await { nodes().any { it.isFocused && it.isScrollable } }
        val initialY = typeY()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { typeY() < initialY }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await { typeY() == initialY }
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(100) }
        await {
            val viewport = Rect()
            nodes().first { it.isFocused }.getBoundsInScreen(viewport)
            nodes().any { node ->
                if (node.contentDescription?.toString()?.startsWith("getAddress，") != true) false
                else {
                    val lastRow = Rect()
                    node.getBoundsInScreen(lastRow)
                    !lastRow.isEmpty && viewport.contains(lastRow)
                }
            }
        }
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(100) }
        await { typeY() == initialY }
        showNext("测试设备 · ID $deviceId")
        showNext("输出设备参数 · ID $deviceId")
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        await { hasText("输出设备参数 · ID $deviceId") }
        await { nodes().any { it.isFocused } }
        // The first parameter is focused automatically. Its native dropdown
        // owns a separate window, which must also forward the menu shortcut.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { !hasText("输出设备参数 · ID $deviceId") && hasText("自动") }
        showNext("设备能力 · ID $deviceId")
        backToCard()
        scenario.onActivity { assertNull(session.selectedOutputId) }
    }

    @Test
    fun touchButtonsRemainAccessibleAndEscapeResetsMenu() {
        prepare(input = true, landscape = false)
        for (description in listOf(
            "查看 ID $deviceId 的设备能力",
            "测试 ID $deviceId（暂未实现）",
            "设置 ID $deviceId 的开流参数",
        )) {
            click(nodes().first { it.contentDescription?.toString() == description })
            await { nodes().none { it.contentDescription?.toString()?.startsWith("ID $deviceId，") == true } }
            key(KeyEvent.KEYCODE_BACK)
            await { card() != null }
        }
        focusCard()
        showNext("设备能力 · ID $deviceId")
        // Escape is a native popup dismissal too; a later menu press restarts.
        key(KeyEvent.KEYCODE_ESCAPE)
        await { card()?.isFocused == true }
        showNext("设备能力 · ID $deviceId")
        backToCard()
    }

    private fun prepare(input: Boolean, landscape: Boolean) {
        this.input = input
        scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            session.refresh()
            val devices = if (input) session.inputDevices else session.outputDevices
            deviceId = devices.values.first { device -> device.type == if (input)
                AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.id
            session.pendingTab = if (input) 0 else 1
        }
        await { instrumentation.targetContext.resources.configuration.orientation == if (landscape)
            Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        await { card() != null }
    }

    private fun card(): AccessibilityNodeInfo? {
        var node = nodes().firstOrNull {
            it.contentDescription?.toString()?.startsWith("ID $deviceId，") == true
        } ?: return null
        while (!node.isFocusable) node = node.parent ?: return null
        return node
    }

    private fun containsDevice(node: AccessibilityNodeInfo): Boolean {
        if (node.contentDescription?.toString()?.startsWith("ID ") == true) return true
        return (0 until node.childCount).any { node.getChild(it)?.let(::containsDevice) == true }
    }

    private fun focusCard() {
        instrumentation.setInTouchMode(false)
        assertTrue(card()!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await { card()?.isFocused == true }
    }

    private fun showNext(title: String) {
        key(KeyEvent.KEYCODE_MENU)
        await { hasText(title) && nodes().any { it.isFocused } }
    }

    private fun backToCard() {
        key(KeyEvent.KEYCODE_BACK)
        await { card()?.isFocused == true }
    }

    private fun typeY(): Int {
        val node = nodes().first { it.contentDescription?.toString()?.startsWith("getType，") == true }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.top
    }

    private fun hasText(text: String) = nodes().any { it.text?.toString() == text }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun click(start: AccessibilityNodeInfo) {
        var node = start
        while (!node.isClickable) node = node.parent ?: error("No click action")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val updated = condition()
        if (!updated) println(nodes().joinToString("\n") {
            "text=${it.text}, description=${it.contentDescription}, focused=${it.isFocused}"
        })
        assertTrue("Device menu/focus state did not update", updated)
    }
}
