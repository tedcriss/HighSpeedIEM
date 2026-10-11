package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.ui.HighSpeedIEMTheme
import cn.xiaotao29.HighSpeedIEM.ui.MonitorApp
import cn.xiaotao29.HighSpeedIEM.ui.OptionDropdown
import java.io.File

/** Actual native text viewport and framework setting observer. Temporary
 * component content and system settings are always restored to the phone UI. */
internal class AnimationScaleAudit(private val instrumentation: Instrumentation, private val s: MonitorSession) {
    fun run(): String {
        val ui = UiDevice.getInstance(instrumentation)
        val activity = s.listener as? MainActivity ?: error("phone activity missing")
        val original = ui.executeShellCommand("settings get global animator_duration_scale").trim()
        fun setScale(value: String) { ui.executeShellCommand("settings put global animator_duration_scale $value") }
        val artifactDir = File(activity.getExternalFilesDir(null), "animation-scale-qa").apply { mkdirs() }
        fun capture(name: String): Bitmap {
            val field = ui.findObject(By.descContains("动画缩放测试")) ?: error("actual anchor missing")
            val bounds = field.visibleBounds
            val file = File(artifactDir, "$name.png")
            check(ui.takeScreenshot(file)) { "screenshot failed" }
            val full = BitmapFactory.decodeFile(file.path) ?: error("screenshot decode failed")
            // Measure the actual native anchor; exclude its label/outline/icon.
            val inset = (bounds.width() * .1f).toInt()
            val result = Bitmap.createBitmap(full, bounds.left + inset,
                bounds.top + bounds.height() / 3, bounds.width() * 2 / 3, bounds.height() / 3)
            full.recycle()
            return result
        }
        fun difference(reference: Bitmap, name: String): Int {
            val next = capture(name)
            check(reference.width == next.width && reference.height == next.height)
            var changed = 0
            for (y in 0 until next.height) for (x in 0 until next.width) {
                if (reference.getPixel(x, y) != next.getPixel(x, y)) changed++
            }
            next.recycle()
            return changed
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 9000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            error(label)
        }
        try {
            setScale("0")
            instrumentation.runOnMainSync {
                activity.setContent {
                    HighSpeedIEMTheme {
                        Box(Modifier.padding(24.dp)) {
                            OptionDropdown(label = "动画缩放测试",
                                options = listOf("这是用于验证系统动画事件的很长的设备参数值，用来检查关闭动画与重新开启动画后的原生文本滚动。"),
                                selectedIndex = 0, enabled = true, hint = "动画缩放测试",
                                modifier = Modifier.width(180.dp), onSelected = {})
                        }
                    }
                }
            }
            val keyboard = KeyboardAudit(instrumentation, ui, activity) {}
            keyboard.seek("动画缩放测试")
            SystemClock.sleep(700)
            val reference = capture("disabled-original")
            try {
                setScale("1")
                waitFor("enable event starts focused overflow scrolling") { difference(reference, "enabled-scrolling") > 100 }
                setScale("0")
                waitFor("disable event resets native viewport") { difference(reference, "disabled-reset") == 0 }
                SystemClock.sleep(2000)
                check(difference(reference, "disabled-stable") == 0) { "disabled animation restarted" }
                setScale("1")
                waitFor("enable event resumes native marquee") { difference(reference, "enabled-again") > 100 }
            } finally { reference.recycle() }
            return "actual OptionDropdown focus/overflow screenshots; scale 0→1 scrolls; 1→0 resets/stops; re-enable resumes; phone UI/settings restored"
        } finally {
            if (original == "null") ui.executeShellCommand("settings delete global animator_duration_scale") else setScale(original)
            instrumentation.runOnMainSync { activity.setContent { MonitorApp(activity, s) } }
        }
    }
}
