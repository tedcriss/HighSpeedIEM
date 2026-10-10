package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import java.io.File

/** Verify rendered press feedback with real pointer events, not performClick. */
internal class MotionAudit(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val activity: MainActivity,
    private val session: MonitorSession,
) {
    fun run(): String {
        val directory = File(activity.getExternalFilesDir(null), "motion-qa").apply { mkdirs() }
        val checks = mutableListOf<String>()
        for (tab in 0..1) {
            instrumentation.runOnMainSync { session.switchTab(tab) }
            SystemClock.sleep(900)
            // Finish bounded scroll gestures before injecting a press; an ongoing
            // fling would consume ACTION_DOWN to stop scrolling instead of pressing a card.
            for (attempt in 0..10) {
                val list = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                if (list == null || !list.scroll(Direction.UP, 1f)) break
            }
            SystemClock.sleep(900)
            // A preceding keyboard audit may leave a focus ring. Enter touch mode
            // through the non-action heading before taking the idle reference frame.
            device.findObject(By.text(if (tab == 0) "输入通道" else "输出通道"))?.visibleBounds?.let {
                device.click(it.centerX(), it.centerY())
            }
            SystemClock.sleep(400)
            val channel = (if (tab == 0) session.inputs else session.outputs).values.first()
            val description = "ID ${channel.device.id}，通道 ${channel.index}，${channel.device.productName}，" +
                if (tab == 0) "查看实时频谱" else "播放或停止测试铃声"
            val bounds = cardBounds(description)
            // Use UI bounds and the title region; stay away from nested actions.
            val header = Rect(bounds.left + bounds.width() / 4, bounds.top + bounds.width() / 12,
                bounds.right - bounds.width() / 4, bounds.top + bounds.width() / 5)
            val before = capture(directory, "$tab-idle")
            val downTime = SystemClock.uptimeMillis()
            fun pointer(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    header.centerX().toFloat(), header.centerY().toFloat(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            lateinit var pressed: Bitmap
            try {
                pointer(MotionEvent.ACTION_DOWN)
                SystemClock.sleep(450)
                pressed = capture(directory, "$tab-pressed")
                check(session.selectedInput == null && session.selectedOutput == null) {
                    "press selected a channel before release"
                }
            } finally {
                pointer(MotionEvent.ACTION_CANCEL)
            }
            SystemClock.sleep(900)
            val cancelled = capture(directory, "$tab-cancelled")
            try {
                val changed = differences(before, pressed, header)
                check(changed > 1000) { "no visible native ripple on tab $tab: $changed changed pixels" }
                val residual = differences(before, cancelled, header)
                check(residual < 20) { "cancel left a pressed fill on tab $tab: $residual pixels" }
                // Ripple must stay inside the card's rounded Surface.
                val gap = Rect(bounds.left, (bounds.top - 8).coerceAtLeast(0), bounds.right, bounds.top)
                check(differences(before, pressed, gap) < 20) { "ripple escaped card bounds" }
                val corner = Rect(bounds.left, bounds.top, bounds.left + 8, bounds.top + 8)
                check(differences(before, pressed, corner) == 0) { "ripple escaped rounded corner" }
                check(session.selectedInput == null && session.selectedOutput == null && !session.testingPublic) {
                    "cancelled gesture started an audio test"
                }
                checks.add("tab=$tab ripple=$changed residual=$residual clipped; cancel kept audio idle")
            } finally {
                before.recycle()
                pressed.recycle()
                cancelled.recycle()
            }
        }
        instrumentation.runOnMainSync { session.switchTab(0) }
        SystemClock.sleep(700)
        val list = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
            ?: error("input scroll container missing")
        val firstCard = visibleCards().first { it.first.endsWith("查看实时频谱") }
        val firstDescription = firstCard.first
        val originalBounds = firstCard.second
        val viewport = list.visibleBounds
        device.swipe(viewport.centerX(), viewport.bottom - viewport.height() / 10,
            viewport.centerX(), viewport.top + viewport.height() / 10, 30)
        SystemClock.sleep(700)
        check(visibleCards().firstOrNull { it.first == firstDescription }?.second != originalBounds) {
            "input list did not scroll"
        }
        val visibleCard = visibleCards()
            .firstOrNull { it.first.endsWith("查看实时频谱") && it.second.height() > 100 }
            ?: error("scrolled input card missing")
        val savedDescription = visibleCard.first
        val savedBounds = visibleCard.second
        instrumentation.runOnMainSync { session.switchTab(1) }
        SystemClock.sleep(700)
        instrumentation.runOnMainSync { session.switchTab(0) }
        SystemClock.sleep(700)
        check(cardBounds(savedDescription) == savedBounds) {
            "tab navigation did not preserve input scroll position"
        }
        checks.add("native input scroll restored on tab return")
        device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.UP, 1f)
        return checks.joinToString("; ")
    }

    private fun visibleCards(): List<Pair<String, Rect>> =
        device.findObjects(By.descStartsWith("ID ")).mapNotNull { node ->
            try { node.contentDescription to node.visibleBounds }
            catch (_: androidx.test.uiautomator.StaleObjectException) { null }
        }

    private fun cardBounds(description: String): Rect {
        repeat(5) {
            try { device.findObject(By.desc(description))?.let { return it.visibleBounds } }
            catch (_: androidx.test.uiautomator.StaleObjectException) { /* Requery a lazy item's new node. */ }
            SystemClock.sleep(150)
        }
        error("channel card missing: $description")
    }

    private fun capture(directory: File, name: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun differences(first: Bitmap, second: Bitmap, area: Rect): Int {
        var count = 0
        for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(first.height)) {
            for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(first.width)) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) count++
            }
        }
        return count
    }
}
