package cn.xiaotao29.HighSpeedIEM.test

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import cn.xiaotao29.HighSpeedIEM.ui.MonitorApp
import cn.xiaotao29.HighSpeedIEM.ui.CompactFlow
import cn.xiaotao29.HighSpeedIEM.ui.HighSpeedIEMTheme

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MonitorApplication
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import cn.xiaotao29.HighSpeedIEM.audio.NativeAudio
import cn.xiaotao29.HighSpeedIEM.audio.RouteModel
import cn.xiaotao29.HighSpeedIEM.service.ForwardingService
import java.io.File
import android.media.AudioManager
import android.media.RingtoneManager
import android.provider.Settings
import android.provider.MediaStore
import android.content.ContentValues
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unified phone-platform test entry point. Drives the real app (UI and audio
 * engine) and reports one PASS/FAIL line per scenario through the
 * instrumentation stream, so a single adb command runs the whole suite:
 *
 *   adb shell am instrument -w \
 *     -e scenarios all cn.xiaotao29.HighSpeedIEM.test/.UnifiedTestInstrumentation
 *
 * Scenarios accept a comma-separated subset in -e scenarios. The runner must
 * pre-grant RECORD_AUDIO and POST_NOTIFICATIONS (see PROJECT.md) because
 * system permission dialogs cannot be answered from instrumentation.
 */
class UnifiedTestInstrumentation : Instrumentation() {

    private lateinit var device: UiDevice
    private val done = java.util.concurrent.CountDownLatch(1)
    private var scenarioArgument: String? = null
    private var coldStartLoadingSeen = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenarioArgument = arguments?.getString("scenarios")
        start()
    }

    override fun onStart() {
        device = UiDevice.getInstance(this)
        Thread {
            // Recording and device probing require a visible activity: without a
            // resumed foreground activity the audio policy denies capture and the
            // session intentionally waits (visible=false). Launch it ourselves.
            dismissSystemDialogs()
            if (!ensureForeground()) {
                publish("=== SUMMARY: pass=0 fail=1 failed=activity-launch")
                done.countDown()
                finish(-1, Bundle())
                return@Thread
            }
            val requested = scenarioArgument
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val scenarios = linkedMapOf<String, () -> Pair<Boolean, String>>(
                "native-selftest" to ::scenarioNativeSelfTest,
                "launch-tabs" to ::scenarioLaunchTabs,
                "device-validity" to ::scenarioDeviceValidity,
                "selection-preview" to ::scenarioSelectionPreview,
                "spectrum-tooltip" to ::scenarioSpectrumTooltip,
                "ringtone-test" to ::scenarioRingtone,
                "ringtone-native-drain" to ::scenarioRingtoneNativeDrain,
                "route-rules" to ::scenarioRouteRules,
                "task-route-editing" to ::scenarioTaskRouteEditing,
                "task-toolbar" to ::scenarioTaskToolbar,
                "forward-start-stop" to ::scenarioForwardStartStop,
                "error-stop" to ::scenarioErrorStop,
                "rotation" to ::scenarioRotation,
                "compact-mode" to ::scenarioCompactMode,
                "compact-routes" to ::scenarioCompactRoutes,
                "compact-binding-phone" to ::scenarioCompactBindingPhone,
                "keyboard-phone" to ::scenarioKeyboardPhone,
                "keyboard-compact-phone" to ::scenarioKeyboardCompactPhone,
                "keyboard-overflow-phone" to ::scenarioKeyboardOverflowPhone,
                "keyboard-compact-small-phone" to ::scenarioKeyboardCompactSmallPhone,
            )
            val names = if (requested.isEmpty() || requested.contains("all")) {
                scenarios.keys.toList()
            } else {
                requested
            }
            var passed = 0
            val failed = ArrayList<String>()
            for (name in names) {
                val scenario = scenarios[name]
                val result = try {
                    if (scenario == null) false to "unknown scenario" else scenario()
                } catch (t: Throwable) {
                    false to (t.toString() + " @" + t.stackTrace.take(3).joinToString(" <- "))
                }
                publish("=== SCENARIO $name: ${if (result.first) "PASS" else "FAIL"} - ${result.second}")
                if (result.first) passed++ else failed.add(name)
            }
            publish("=== SUMMARY: pass=$passed fail=${failed.size}" +
                (if (failed.isEmpty()) "" else " failed=" + failed.joinToString(",")))
            done.countDown()
            finish(if (failed.isEmpty()) Activity.RESULT_OK else -1, Bundle())
        }.start()
    }

    override fun onDestroy() {
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        super.onDestroy()
    }

    private fun publish(line: String) {
        val bundle = Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") }
        sendStatus(0, bundle)
        android.util.Log.i("UnifiedTest", line)
    }

    // ---- helpers ----

    private fun session(): MonitorSession =
        (targetContext.applicationContext as MonitorApplication).session()

    private fun ensureForeground(): Boolean {
        val s = session()
        if (s.visible) return true
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName(targetContext, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Watch for the cold-start loading overlay while the activity is
            // being launched; the overlay can be gone by the time the launch
            // call returns.
            val overlaySeen = java.util.concurrent.atomic.AtomicBoolean(false)
            val watcher = Thread {
                val deadline = SystemClock.uptimeMillis() + 25000
                while (SystemClock.uptimeMillis() < deadline && !overlaySeen.get()) {
                    try {
                        if (dumpTextsGuarded(6000).any { it.contains("正在加载输入输出通道…") }) {
                            overlaySeen.set(true)
                        }
                    } catch (e: Exception) {
                        // retry until the deadline
                    }
                }
            }
            watcher.isDaemon = true
            watcher.start()
            startActivitySync(intent)
            watcher.join(26000)
            coldStartLoadingSeen = overlaySeen.get()
            waitUntil(15000) { s.visible }
        } catch (e: Exception) {
            publish("=== activity launch failed: $e")
            false
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    private fun dumpTexts(): List<String> {
        val file = File(targetContext.cacheDir, "ui.xml")
        device.dumpWindowHierarchy(file)
        val xml = try {
            file.readText()
        } catch (e: Exception) {
            ""
        }
        return Regex("(?:text|content-desc)=\"([^\"]+)\"")
            .findAll(xml).map { it.groupValues[1] }.toList()
    }

    /** Dump with a hard time budget; returns empty when the dump stalls. */
    private fun dumpTextsGuarded(timeoutMs: Long = 8000): List<String> {
        var result: List<String>? = null
        val worker = Thread {
            result = try {
                dumpTexts()
            } catch (e: Exception) {
                emptyList()
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        return result ?: emptyList()
    }

    private fun hasText(fragment: String): Boolean = dumpTextsGuarded().any { it.contains(fragment) }

    /** Native selection can belong to the same-bounds selectable parent of
     * the descriptive node exported by Compose accessibility. */
    private fun actionSelected(node: androidx.test.uiautomator.UiObject2?): Boolean {
        val bounds = node?.visibleBounds ?: return false
        var item = node
        while (item != null && item.visibleBounds == bounds) {
            if (item.isSelected || item.isChecked) return true
            item = item.parent
        }
        return false
    }

    private fun clickText(fragment: String): Boolean {
        repeat(3) {
            val node = device.findObject(By.text(fragment)) ?: device.findObject(By.textContains(fragment))
                ?: device.findObject(By.desc(fragment)) ?: device.findObject(By.descContains(fragment))
            if (node != null) {
                try {
                    node.click()
                    device.waitForIdle(1000)
                    return true
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    // Compose can replace a node while a notice enters/leaves.
                }
            }
            SystemClock.sleep(100)
        }
        return false
    }

    /** Closes system crash/ANR dialogs that would otherwise steal window focus. */
    private fun dismissSystemDialogs() {
        for (label in listOf("Close app", "关闭应用", "APP CLOSE", "Wait", "等待")) {
            val node = device.findObject(By.text(label)) ?: continue
            node.click()
            device.waitForIdle(1500)
            SystemClock.sleep(500)
        }
    }

    /** Compact one-line state summary used in failure messages. */
    private fun diagnostics(s: MonitorSession): String =
        "status=[" + s.status + "] notice=[" + (s.notices.value ?: "") + "] tab=" + s.tab +
            " running=" + s.runningPublic + " testing=" + s.testingPublic +
            " loading=" + s.loadingPublic + " previewOpening=" + s.previewOpeningPublic +
            " previewKey=" + s.previewKeyPublic + " bins=" + s.spectrumBinsPublic +
            " selIn=" + s.selectedInputPublic + " selOut=" + s.selectedOutputPublic +
            " visible=" + s.visible

    private fun awaitDiscovery(s: MonitorSession): Boolean =
        waitUntil(30000) { !s.loadingPublic && (s.inputs.isNotEmpty() || s.outputs.isNotEmpty()) }

    /**
     * Brings the app activity back to the foreground after config changes and
     * waits until its window actually shows app content again.
     */
    private fun bringToFront() {
        for (attempt in 0 until 8) {
            try {
                device.executeShellCommand("am start -n cn.xiaotao29.HighSpeedIEM/.MainActivity")
            } catch (e: Exception) {
                publish("=== bringToFront start failed: $e")
            }
            SystemClock.sleep(1200)
            val texts = dumpTextsGuarded(4000)
            if (texts.any { it.contains("高速耳返") } ||
                texts.any { it == "输入" || it == "输出" } ||
                texts.any { it.contains("sdk_gphone") } ||
                texts.any { it == "页面指示器" }
            ) {
                return
            }
        }
        publish("=== bringToFront: app window not confirmed")
    }

    /** Dismisses a visible snackbar; it overlays the bottom of the page. */
    private fun dismissSnackbar() {
        repeat(3) {
            val close = device.findObject(By.text("关闭")) ?: return
            try {
                close.click()
                SystemClock.sleep(700)
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // Both the first lookup and any retry may race the exit animation.
            }
        }
    }

    private fun firstEnabledInput(s: MonitorSession) =
        s.inputs.values.firstOrNull { it.key !in s.disabled }

    private fun firstEnabledOutput(s: MonitorSession) =
        s.outputs.values.firstOrNull { it.key !in s.disabled }

    private fun setPair(s: MonitorSession, inKey: String, outKey: String) = runOnMainSync {
        s.model.clear()
        val row = s.model.rows[0]
        s.setRouteSide(row, true, inKey)
        s.setRouteSide(s.model.rows[0], false, outKey)
    }

    // ---- scenarios ----

    private fun scenarioNativeSelfTest(): Pair<Boolean, String> {
        NativeAudio.loadError?.let { return false to it }
        val result = NativeAudio.selfTest()
        return result.startsWith("PASS") to result
    }

    private fun scenarioLaunchTabs(): Pair<Boolean, String> {
        val s = session()
        if (!coldStartLoadingSeen) {
            return false to "cold-start loading overlay not observed"
        }
        if (!waitUntil(30000) {
                !s.loadingPublic && (s.inputs.isNotEmpty() || s.outputs.isNotEmpty())
            }) {
            return false to "device discovery unfinished (inputs=${s.inputs.size}, " +
                "outputs=${s.outputs.size}, loading=${s.loadingPublic})"
        }
        dismissSystemDialogs()
        if (!waitUntil(5000) { hasText("任务控制") }) {
            return false to "navigation bar missing; " + diagnostics(s)
        }
        if (!clickText("输出设置")) return false to "output tab item not clickable"
        if (!waitUntil(6000) { hasText("选择通道自动播放铃声") }) {
            val texts = dumpTextsGuarded().take(25).joinToString("|")
            return false to "output page did not render; page=[" + texts + "]"
        }
        if (!clickText("任务控制")) return false to "task tab item not clickable"
        if (!waitUntil(4000) { hasText("个转发") }) return false to "task page did not render"
        if (!clickText("输入设置")) return false to "input tab item not clickable"
        if (!waitUntil(4000) { hasText("采样率") }) return false to "input page did not render"
        return true to "tabs switch and pages render " +
            "(inputs=${s.inputs.size}, outputs=${s.outputs.size})"
    }

    private fun scenarioDeviceValidity(): Pair<Boolean, String> {
        val s = session()
        if (!waitUntil(30000) { !s.loadingPublic && s.inputs.isNotEmpty() }) {
            return false to "discovery unfinished"
        }
        val enabledIn = s.inputs.values.count { it.key !in s.disabled }
        val enabledOut = s.outputs.values.count { it.key !in s.disabled }
        if (enabledIn == 0) return false to "no enabled input channel (inputs=${s.inputs.size})"
        if (enabledOut == 0) return false to "no enabled output channel (outputs=${s.outputs.size})"
        return true to "enabled inputs=$enabledIn/${s.inputs.size}, outputs=$enabledOut/${s.outputs.size}"
    }

    private fun scenarioSelectionPreview(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        val channel = firstEnabledInput(s) ?: return false to "no enabled input channel"
        runOnMainSync {
            s.tab = 0
            if (s.selectedInput != channel.key) s.selectInput(channel.key)
            s.updatePreview()
        }
        if (!waitUntil(10000) { !s.previewOpeningPublic }) {
            return false to "preview open did not settle; " + diagnostics(s)
        }
        if (s.selectedInput != channel.key) return false to "selection lost"
        val got = waitUntil(8000) { s.spectrum.value.size > 2 }
        val bins = s.spectrum.value
        runOnMainSync {
            s.selectInput(null)
            s.updatePreview()
        }
        if (!waitUntil(5000) { s.selectedInput == null }) return false to "deselect failed"
        if (!got) return false to "no spectrum bins arrived; " + diagnostics(s)
        return true to "spectrum bins=${bins.size} sampleRate=${bins[0].toInt()}"
    }

    private fun scenarioSpectrumTooltip(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        if (!waitUntil(10000) { !s.operationsBlockedPublic }) return false to "session busy"
        runOnMainSync { s.switchTab(0); s.dismissInputPreview(s.selectedInput) }
        check(waitUntil(8000) { !s.operationsBlockedPublic }) { diagnostics(s) }
        val channel = firstEnabledInput(s) ?: return false to "no enabled input"
        val original = s.config(channel).copyOf()
        val siblings = s.inputs.values.filter { it.deviceKey == channel.deviceKey && it.key != channel.key }
            .associate { it.key to (it.key in s.disabled) }
        val checks = ArrayList<String>()
        fun expect(condition: Boolean, message: String) {
            check(condition) { message + "; " + diagnostics(s) }
        }
        fun released() = s.selectedInput == null && s.previewKeyPublic == null &&
            !s.operationsBlockedPublic && s.spectrum.value.isEmpty() &&
            NativeAudio.endpointStats(channel.device.id, true).isEmpty()
        fun open() {
            expect(waitUntil(10000) { !s.operationsBlockedPublic }, "busy before select")
            runOnMainSync {
                s.switchTab(0)
                if (s.selectedInput != channel.key) s.selectInput(channel.key)
                s.updatePreview()
            }
            expect(waitUntil(10000) { s.selectedInput == channel.key && !s.previewOpeningPublic &&
                s.spectrum.value.size > 2 }, "preview did not start")
            expect(waitUntil(5000) { device.hasObject(By.text("实时频谱")) }, "tooltip missing")
        }
        try {
            expect(!device.hasObject(By.text("实时频谱")), "idle spectrum still visible")
            device.takeScreenshot(File(targetContext.getExternalFilesDir(null), "spectrum-idle.png"))
            // Select through the actual card and retain its UI-derived bounds
            // for an outside tap after the popup appears above/below it.
            val anchor = device.findObject(By.descContains("ID ${channel.device.id}，通道 ${channel.index}，"))
                ?: error("input anchor missing")
            val anchorBounds = anchor.visibleBounds
            val header = device.findObject(By.desc("允许 ID ${channel.device.id} 通道 ${channel.index} 用于转发"))
                ?: error("input header missing")
            val headerY = header.visibleBounds.centerY()
            device.click(anchorBounds.centerX(), headerY)
            expect(waitUntil(10000) { s.selectedInput == channel.key && s.spectrum.value.size > 2 }, "card tap failed")
            expect(device.hasObject(By.text("实时频谱")), "card did not show rich tooltip")
            device.takeScreenshot(File(targetContext.getExternalFilesDir(null), "spectrum-tooltip.png"))
            // Persistence: a native non-persistent tooltip would time out here.
            SystemClock.sleep(2500)
            expect(s.selectedInput == channel.key && device.hasObject(By.text("实时频谱")), "tooltip timed out")
            device.pressBack()
            expect(waitUntil(8000) { released() }, "back failed to release input")
            expect(!device.hasObject(By.text("实时频谱")), "graph survived back")
            expect(channel.key !in s.disabled, "normal dismissal disabled input")
            checks.add("card/back/persistence")

            open()
            device.click(anchorBounds.centerX(), headerY)
            expect(waitUntil(8000) { released() }, "outside tap failed to release input")
            checks.add("outside-tap")

            // Closing while startConfigured is queued must invalidate its
            // completion and still release the native stream it may open.
            runOnMainSync {
                s.selectInput(channel.key)
                s.updatePreview()
                s.dismissInputPreview(channel.key)
            }
            expect(waitUntil(8000) { released() }, "pending open leaked")
            checks.add("opening-dismissal")

            open()
            val activity = s.listener as? MainActivity ?: error("activity missing")
            runOnMainSync { activity.setContent {} }
            try {
                expect(waitUntil(8000) { released() }, "tooltip composition disposal leaked")
                expect(s.visible, "disposal test unexpectedly paused the activity")
                checks.add("composition-disposal")
            } finally {
                runOnMainSync { activity.setContent { MonitorApp(activity, s) } }
            }

            open()
            val oldToken = s.inputSelectionToken
            runOnMainSync { s.dismissInputPreview(channel.key, oldToken) }
            expect(waitUntil(8000) { released() }, "first selection cleanup failed")
            open()
            runOnMainSync { s.dismissInputPreview(channel.key, oldToken) }
            expect(s.selectedInput == channel.key && s.previewKeyPublic == channel.key,
                "stale tooltip dismissed a newer selection")
            runOnMainSync { s.switchTab(1) }
            expect(waitUntil(8000) { released() }, "tab exit leaked")
            expect(!device.hasObject(By.text("实时频谱")), "graph survived tab exit")
            checks.add("stale-disposal/tab-exit")

            open()
            device.executeShellCommand("am start -a android.settings.SETTINGS")
            expect(waitUntil(8000) { !s.visible && released() }, "background leaked")
            bringToFront()
            expect(waitUntil(8000) { s.visible }, "foreground not restored")
            expect(s.selectedInput == null && !device.hasObject(By.text("实时频谱")), "preview restored after background")
            checks.add("background")

            open()
            NativeAudio.injectError(-899) // oboe Result::ErrorDisconnected
            expect(waitUntil(8000) { released() }, "delayed error leaked")
            expect(!device.hasObject(By.text("实时频谱")), "graph survived error")
            expect(channel.key in s.disabled, "failed input still enabled")
            expect(s.notices.value?.contains("ErrorDisconnected") == true, "raw error missing")
            expect(siblings.all { (key, disabled) -> (key in s.disabled) == disabled }, "error changed sibling channels")
            checks.add("delayed-error")
            runOnMainSync { s.enable(channel, true) }
            dismissSnackbar()

            runOnMainSync {
                s.updateSettings(channel) { it.copyOf(rate = -1) }
                s.selectInput(channel.key)
                s.updatePreview()
            }
            expect(waitUntil(10000) { released() && channel.key in s.disabled }, "open error did not close preview")
            expect(!device.hasObject(By.text("实时频谱")), "graph survived open failure")
            checks.add("open-error")
            return true to checks.joinToString(", ") + "; native stats and spectrum empty after every release"
        } finally {
            runOnMainSync {
                s.dismissInputPreview(s.selectedInput)
                s.updateSettings(channel) { original }
            }
            waitUntil(10000) { !s.operationsBlockedPublic }
            runOnMainSync { s.enable(channel, true); s.switchTab(0) }
            dismissSnackbar()
        }
    }

    private fun scenarioRingtone(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        check(waitUntil(10000) { !s.operationsBlockedPublic }) { diagnostics(s) }
        val channel = firstEnabledOutput(s) ?: return false to "no enabled output channel"
        val other = s.outputs.values.firstOrNull {
            it.device.id == channel.device.id && it.key != channel.key && it.key !in s.disabled
        } ?: return false to "need two enabled output channels for switching"
        val originalRingtone = Settings.System.getString(targetContext.contentResolver, Settings.System.RINGTONE)
        val originalSettings = s.config(channel).copyOf()
        val audioManager = targetContext.getSystemService(AudioManager::class.java)
        val rate = 48000
        val durationMs = 24250L // Cross both the former 8 s cap and 20 s watchdog.
        val frames = (rate * durationMs / 1000).toInt()
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { wav.putShort((kotlin.math.sin(it * 2.0 * Math.PI * 440 / rate) * 2000).toInt().toShort()) }
        val fixtures = ArrayList<android.net.Uri>()
        fun mediaFixture(name: String, data: ByteArray): android.net.Uri {
            val resolver = targetContext.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/")
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            })!!
            fixtures.add(uri)
            resolver.openOutputStream(uri)!!.use { it.write(data) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        fun setRingtone(value: String?) {
            if (value == null) device.executeShellCommand("settings delete system ringtone")
            else device.executeShellCommand("settings put system ringtone $value")
            check(RingtoneManager.getActualDefaultRingtoneUri(targetContext, RingtoneManager.TYPE_RINGTONE)?.toString() == value)
        }
        fun active() = audioManager.activePlaybackConfigurations.isNotEmpty()
        fun released() = !s.testingPublic && !s.operationsBlockedPublic && !active() &&
            NativeAudio.endpointStats(channel.device.id, false).isEmpty()
        fun clickOutput(output: cn.xiaotao29.HighSpeedIEM.audio.Channel = channel) {
            repeat(8) {
                device.waitForIdle(1000)
                // Different audio devices can share productName and channel index.
                // The accessible card identity includes the device ID. The
                // header's checkbox supplies visible bounds without repeating
                // the decorative title in the accessibility tree.
                val card = device.findObject(By.descContains("ID ${output.device.id}，通道 ${output.index}，"))
                val header = device.findObject(By.desc("允许 ID ${output.device.id} 通道 ${output.index} 用于转发"))
                if (card != null && header != null) {
                    try {
                        if (!s.outputSelectionLockedPublic && header.visibleBounds.height() > 0) {
                            device.click(card.visibleBounds.centerX(), header.visibleBounds.centerY())
                            device.waitForIdle(1000)
                            return
                        }
                    } catch (_: androidx.test.uiautomator.StaleObjectException) {
                        SystemClock.sleep(100)
                        return@repeat
                    }
                }
                val down = output.index > channel.index
                device.swipe(device.displayWidth / 2,
                    if (down) device.displayHeight * 3 / 4 else device.displayHeight / 3,
                    device.displayWidth / 2,
                    if (down) device.displayHeight / 3 else device.displayHeight * 3 / 4, 20)
            }
            error("output card not visible: ${output.key}; ${diagnostics(s)}")
        }
        val checks = ArrayList<String>()
        try {
            runOnMainSync {
                s.switchTab(1)
                s.updateSettings(channel) { originalSettings.copy(exclusive = false, rate = 0, format = 0) }
            }
            val originalStatus = s.status
            val originalRows = s.model.rows.map { it.input to it.output }
            setRingtone(mediaFixture("HighSpeedIEM-complete-test.wav", wav.array()).toString())
            val startedAt = SystemClock.uptimeMillis()
            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() }) { diagnostics(s) }
            check(!device.hasObject(By.text("测试选中通道")) && !device.hasObject(By.text("停止测试"))) { "old test button remains" }
            check(!s.outputSelectionLockedPublic) { "output cards locked during playback" }
            while (SystemClock.uptimeMillis() - startedAt < 21500) {
                check(s.testingPublic && s.selectedOutput == channel.key) { "early completion; " + diagnostics(s) }
                SystemClock.sleep(100)
            }
            check(waitUntil(10000) { released() }) { "natural completion leaked; " + diagnostics(s) }
            val elapsed = SystemClock.uptimeMillis() - startedAt
            check(elapsed >= durationMs - 250) { "tail truncated: ${elapsed}ms" }
            check(s.selectedOutput == null && channel.key !in s.disabled && s.status == originalStatus)
            check(s.model.rows.map { it.input to it.output } == originalRows)
            checks.add("automatic complete ${durationMs}ms ringtone (${elapsed}ms), deselected/enabled retained, resources released")

            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() })
            val stoppedAt = SystemClock.uptimeMillis()
            clickOutput()
            check(waitUntil(2000) { released() }) { "manual stop leaked; " + diagnostics(s) }
            val stopElapsed = SystemClock.uptimeMillis() - stoppedAt
            check(s.selectedOutput == null && channel.key !in s.disabled && s.status == originalStatus) { "manual stop state; " + diagnostics(s) }
            check(s.model.rows.map { it.input to it.output } == originalRows)
            checks.add("same-card cancellation released in ${stopElapsed}ms; deselected, enabled retained")

            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() })
            clickOutput(other)
            check(waitUntil(5000) { s.testingPublic && active() && s.selectedOutput == other.key }) { "switch failed; " + diagnostics(s) }
            check(channel.key !in s.disabled && other.key !in s.disabled)
            check(waitUntil(3000) {
                actionSelected(device.findObject(By.descContains("ID ${other.device.id}，通道 ${other.index}，"))) &&
                    !actionSelected(device.findObject(By.descContains("ID ${channel.device.id}，通道 ${channel.index}，")))
            }) { "selection highlight did not switch to the second card" }
            clickOutput(other)
            check(waitUntil(2000) { released() }) { "switched channel cancellation leaked" }
            checks.add("UI switch to second channel, one selection, cancel/release both channels")

            // All requests land before the old worker's cleanup callback: only
            // the last choice may play, including switching back to the same key.
            runOnMainSync {
                s.selectOutput(channel.key); s.ringtone()
                s.selectOutput(other.key); s.ringtone()
                s.selectOutput(channel.key); s.ringtone()
            }
            check(waitUntil(5000) { s.testingPublic && active() && s.selectedOutput == channel.key }) { "rapid switch wedged; " + diagnostics(s) }
            runOnMainSync { s.selectOutput(other.key); s.ringtone(); s.selectOutput(other.key) }
            check(waitUntil(2000) { released() && s.selectedOutput == null }) { "queued cancellation leaked" }
            checks.add("rapid A-B-A switch and cancellation of queued selection")

            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() })
            check(clickText("任务控制"))
            check(waitUntil(2000) { released() && s.selectedOutput == null })
            check(channel.key !in s.disabled && other.key !in s.disabled)
            check(clickText("输出设置"))
            check(s.selectedOutput == null && !s.testingPublic)
            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() })
            device.pressHome()
            check(waitUntil(2000) { released() && s.selectedOutput == null })
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check(waitUntil(5000) { s.visible })
            check(s.selectedOutput == null && channel.key !in s.disabled)
            checks.add("tab exit and background release; returning does not restore playback")

            val missingUri = mediaFixture("HighSpeedIEM-missing-test.wav", wav.array())
            val emptyUri = mediaFixture("HighSpeedIEM-empty-test.wav", wav.array().copyOf(44).also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36).putInt(40, 0)
            })
            for ((label, uri) in listOf(
                "silent-default" to null,
                "missing-resource" to missingUri.toString(),
                "no-audio-track" to emptyUri.toString(),
            )) {
                setRingtone(uri)
                if (label == "missing-resource") {
                    targetContext.contentResolver.delete(missingUri, null, null)
                    fixtures.remove(missingUri)
                }
                clickOutput()
                check(waitUntil(5000) { released() && s.selectedOutput == null }) { "$label leaked; " + diagnostics(s) }
                check(channel.key !in s.disabled && s.status == originalStatus)
                check(waitUntil(8000) { hasText("系统没有可播放的默认电话铃声") }) { "$label notice missing" }
                dismissSnackbar()
                checks.add("$label notice, deselected/enabled retained")
            }
            // The same output must remain reusable after every completion path.
            setRingtone(originalRingtone)
            clickOutput()
            check(waitUntil(5000) { s.testingPublic && active() })
            runOnMainSync { s.stopOutputTestAndDeselect() }
            check(waitUntil(5000) { released() })
            checks.add("original default replayed")
            return true to checks.joinToString("; ")
        } catch (t: Throwable) {
            device.dumpWindowHierarchy(File(targetContext.filesDir, "ringtone-failure.xml"))
            device.takeScreenshot(File(targetContext.filesDir, "ringtone-failure.png"))
            throw IllegalStateException("completed=[${checks.joinToString("; ")}]; $t at ${t.stackTrace.firstOrNull()}; ${diagnostics(s)}", t)
        } finally {
            runOnMainSync { s.stopOutputTestAndDeselect() }
            waitUntil(10000) { !s.operationsBlockedPublic }
            setRingtone(originalRingtone)
            runOnMainSync { s.updateSettings(channel) { originalSettings }; s.switchTab(0) }
            fixtures.forEach { targetContext.contentResolver.delete(it, null, null) }
            dismissSnackbar()
        }
    }

    /** Exercise native ringtone tail draining on the phone's shared AAudio endpoint. */
    private fun scenarioRingtoneNativeDrain(): Pair<Boolean, String> {
        val s = session()
        check(awaitDiscovery(s) && waitUntil(10000) { !s.operationsBlockedPublic })
        val channel = firstEnabledOutput(s) ?: return false to "no enabled output"
        runOnMainSync { s.dismissInputPreview(s.selectedInput); s.stopOutputTestAndDeselect() }
        check(waitUntil(10000) { !s.operationsBlockedPublic })
        try {
            val error = NativeAudio.startConfigured(intArrayOf(
                -1, 0, 0, 0, 0, 0, 0,
                channel.device.id, channel.index, channel.total, 0, 0, 0, 0,
            ))
            check(error.isEmpty()) { error }
            val rate = NativeAudio.outputRate(channel.device.id)
            val samples = FloatArray(rate / 10) { (kotlin.math.sin(it * 2.0 * Math.PI * 440 / rate) * 0.05).toFloat() }
            val startedAt = SystemClock.uptimeMillis()
            var offset = 0
            while (offset < samples.size) {
                val written = NativeAudio.writeTest(channel.device.id, channel.index, samples, offset, samples.size - offset)
                check(written >= 0 && SystemClock.uptimeMillis() - startedAt < 5000)
                offset += written
                if (written == 0) SystemClock.sleep(5)
            }
            check(NativeAudio.testPending(channel.device.id) > 0) { "queued frames vanished before playback" }
            check(waitUntil(5000) { NativeAudio.testPending(channel.device.id) == 0 }) { "native tail failed to drain" }
            val elapsed = SystemClock.uptimeMillis() - startedAt
            check(elapsed >= 90) { "native tail cut short: ${elapsed}ms" }
            check(NativeAudio.testPending(channel.device.id) == 0) { "drained snapshot changed" }
            return true to "100ms AAudio ringtone + hardware tail drained in ${elapsed}ms"
        } finally {
            NativeAudio.stop()
            check(NativeAudio.endpointStats(channel.device.id, false).isEmpty())
        }
    }

    private fun scenarioRouteRules(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        val inKey = firstEnabledInput(s)?.key ?: return false to "no enabled input"
        val outKey = firstEnabledOutput(s)?.key ?: return false to "no enabled output"
        if (!waitUntil(5000) { !s.operationsBlockedPublic && !s.runningPublic }) {
            return false to "session busy"
        }
        setPair(s, inKey, outKey)
        if (s.model.validCount() != 1 || s.model.rows.size != 2) {
            return false to "pair not accepted (rows=${s.model.rows.size}, valid=${s.model.validCount()})"
        }
        // A duplicate complete pair must collapse onto the first occurrence.
        runOnMainSync {
            val blank = s.model.rows.last()
            s.setRouteSide(blank, true, inKey)
            s.setRouteSide(blank, false, outKey)
        }
        if (s.model.validCount() != 1) {
            return false to "duplicate pair not collapsed (valid=${s.model.validCount()})"
        }
        // Same input with a different output may coexist when such a channel exists.
        val outKey2 = s.outputs.values.firstOrNull { it.key !in s.disabled && it.key != outKey }?.key
        var sameInputMessage = "single output channel, partial-duplicate rule skipped"
        if (outKey2 != null) {
            runOnMainSync {
                val blank = s.model.rows.last()
                s.setRouteSide(blank, true, inKey)
                s.setRouteSide(blank, false, outKey2)
            }
            if (s.model.validCount() != 2) {
                return false to "same input with different output rejected (valid=${s.model.validCount()})"
            }
            sameInputMessage = "partial duplicates allowed"
        }
        // Clearing sides removes the emptied row and keeps one blank insertion row.
        runOnMainSync {
            s.model.clear()
        }
        if (s.model.validCount() != 0 || s.model.rows.size != 1 || !s.model.rows[0].blank()) {
            return false to "clear did not restore a single blank row"
        }
        return true to "normalize, dedupe and clear rules hold; $sameInputMessage"
    }

    private fun pickRouteChannel(index: Int, input: Boolean,
                                 channel: cn.xiaotao29.HighSpeedIEM.audio.Channel?) {
        val hint = (if (input) "输入" else "输出") +
            "转发通道，第 ${index + 1} 行；选择空白可清除"
        val anchor = device.findObject(By.desc(hint)) ?: error("missing route anchor: $hint")
        anchor.click()
        val label = channel?.let { "ID ${it.device.id} · 通道 ${it.index}" } ?: " "
        check(waitUntil(3000) { device.findObject(By.text(label)) != null }) { "missing menu item: $label" }
        device.findObject(By.text(label)).click()
        device.waitForIdle(1000)
    }

    private fun checkRouteTable(s: MonitorSession, rows: Int, complete: Int) {
        check(waitUntil(4000) {
            s.model.rows.size == rows && s.model.validCount() == complete &&
                device.findObject(By.res("task-route-${rows - 1}")) != null &&
                device.findObject(By.res("task-route-$rows")) == null &&
                device.findObject(By.text("启动 $complete 个转发")) != null
        }) { "table/UI mismatch: expected=$rows/$complete actual=${s.model.rows.size}/${s.model.validCount()}" }
        check(s.model.rows.last().blank() && s.model.rows.count { it.blank() } == 1)
    }

    /** Every edit uses the phone's real dropdown; no tab rebuilds hide stale intervals. */
    private fun scenarioTaskRouteEditing(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic })
            return false to "session busy"
        val input = firstEnabledInput(s) ?: return false to "no enabled input"
        val outputs = s.outputs.values.filter { it.key !in s.disabled }
        if (outputs.size < 2) return false to "need two output channels"
        val output = outputs[0]
        val otherOutput = outputs[1]
        runOnMainSync { s.model.clear(); s.switchTab(2) }
        dismissSnackbar()
        checkRouteTable(s, 1, 0)
        // The original add/clear crash, with each selection order and no tab switch.
        repeat(8) { cycle ->
            val inputFirst = cycle % 2 == 0
            pickRouteChannel(0, inputFirst, if (inputFirst) input else output)
            checkRouteTable(s, 2, 0)
            pickRouteChannel(0, inputFirst, null)
            checkRouteTable(s, 1, 0)
        }
        pickRouteChannel(0, true, input)
        pickRouteChannel(0, false, output)
        checkRouteTable(s, 2, 1)
        val first = s.model.rows.first()
        pickRouteChannel(1, true, input)
        checkRouteTable(s, 3, 1)
        pickRouteChannel(1, false, otherOutput)
        checkRouteTable(s, 3, 2)
        val second = s.model.rows[1]
        // Delete before a surviving pair and verify its identity and opposite side.
        pickRouteChannel(0, true, null)
        checkRouteTable(s, 3, 1)
        check(s.model.rows.first().output == output.key)
        pickRouteChannel(0, false, null)
        checkRouteTable(s, 2, 1)
        check(s.model.rows.first().id == second.id && s.model.rows.first().output == otherOutput.key)
        runOnMainSync { s.setRouteSide(first, true, input.key) }
        checkRouteTable(s, 2, 1)
        // Duplicate completion while status remains ready used to crash in LazyColumn.
        pickRouteChannel(1, true, input)
        pickRouteChannel(1, false, output)
        checkRouteTable(s, 3, 2)
        pickRouteChannel(1, false, otherOutput)
        checkRouteTable(s, 2, 1)
        check(s.model.rows.first().id == second.id)
        pickRouteChannel(0, false, otherOutput) // no-op: keep selection and IDs
        checkRouteTable(s, 2, 1)
        // Output-only insertion and clear while another complete pair remains ready.
        pickRouteChannel(1, false, output)
        checkRouteTable(s, 3, 1)
        pickRouteChannel(1, false, null)
        checkRouteTable(s, 2, 1)
        runOnMainSync { s.setRouteSide(s.model.rows.last(), true, "missing-channel") }
        checkRouteTable(s, 2, 1)
        // Disable an endpoint while its menu is open: remove row and popup together.
        val anchor = device.findObject(By.desc("输入转发通道，第 1 行；选择空白可清除"))
        anchor.click()
        check(waitUntil(3000) { device.findObject(By.text("ID ${input.device.id} · 通道 ${input.index}")) != null })
        runOnMainSync { s.enable(input, false) }
        try {
            checkRouteTable(s, 1, 0)
            runOnMainSync { s.setRouteSide(s.model.rows.last(), true, input.key) }
            checkRouteTable(s, 1, 0)
        } finally {
            runOnMainSync { s.enable(input, true) }
        }
        // Native start/stop via the same task controls, including edit locks/stats.
        pickRouteChannel(0, false, output)
        pickRouteChannel(0, true, input)
        checkRouteTable(s, 2, 1)
        check(clickText("启动 1 个转发"))
        check(waitUntil(25000) { s.runningPublic }) { diagnostics(s) }
        check(waitUntil(10000) { s.actualStats[input.deviceKey]?.isNotEmpty() == true && hasText("停止转发") })
        val before = s.model.rows
        device.findObject(By.desc("输入转发通道，第 1 行；选择空白可清除")).click()
        check(device.findObject(By.textContains(input.device.productName.toString())) == null) {
            "running route opened a menu"
        }
        runOnMainSync { s.setRouteSide(s.model.rows.first(), true, null) }
        check(s.model.rows == before)
        check(clickText("停止转发"))
        check(waitUntil(20000) { !s.forwardingLockedPublic })
        checkRouteTable(s, 2, 1)
        // A tab round-trip must preserve content and stable IDs, not repair it.
        check(clickText("输入设置") && clickText("任务控制"))
        checkRouteTable(s, 2, 1)
        check(s.model.rows == before)
        return true to "8 add/clear cycles, both orders; immediate rows/counts; survivor IDs; dedupe; stale/invalid/disabled picks; open-menu prune; UI start/stop, lock/stats; tab preservation"
    }

    /** Exercise overflow independently of the emulator's limited channel count. */
    private fun scenarioTaskToolbar(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.operationsBlockedPublic && !s.runningPublic })
            return false to "session busy"
        val inKey = firstEnabledInput(s)?.key ?: return false to "no enabled input"
        val outKey = firstEnabledOutput(s)?.key ?: return false to "no enabled output"
        val originalRows = s.model.rows.toList()
        try {
            runOnMainSync {
                s.switchTab(0)
                // Legal partial rows exercise overflow without bypassing table rules.
                s.model.clear()
                repeat(12) { s.setRouteSide(s.model.rows.last(), true, inKey) }
                s.switchTab(2)
            }
            dismissSnackbar()
            check(waitUntil(5000) { device.findObject(By.res("task-forwarding-toolbar")) != null })
            val toolbarBefore = device.findObject(By.res("task-forwarding-toolbar")).visibleBounds
            val list = device.findObject(By.res("task-route-list")) ?: error("missing route list")
            val listBounds = list.visibleBounds
            check(toolbarBefore.width() < listBounds.width()) { "toolbar spans the page" }
            check(kotlin.math.abs(toolbarBefore.centerX() - listBounds.centerX()) <= 2) { "toolbar not centered" }
            check(toolbarBefore.bottom < listBounds.bottom) { "toolbar overlaps navigation boundary" }
            val nav = device.findObject(By.text("任务控制")) ?: error("navigation missing")
            check(toolbarBefore.bottom < nav.visibleBounds.top) { "toolbar overlaps navigation" }
            // Swipe only in the unobscured list region, with coordinates from its bounds.
            var last = device.findObject(By.res("task-route-12"))
            var attempts = 0
            while (attempts++ < 24) {
                val toolbar = device.findObject(By.res("task-forwarding-toolbar")).visibleBounds
                if (last != null && last.visibleBounds.bottom < toolbar.top &&
                    last.visibleBounds.height() > 0) break
                device.swipe(listBounds.centerX(), toolbar.top - 20,
                    listBounds.centerX(), listBounds.top + 20, 30)
                SystemClock.sleep(250)
                last = device.findObject(By.res("task-route-12"))
            }
            // Continue to the actual end, including bottom padding. A row can
            // otherwise appear above the toolbar while its title is clipped.
            repeat(2) {
                device.swipe(listBounds.centerX(), toolbarBefore.top - 20,
                    listBounds.centerX(), listBounds.top + 20, 30)
                SystemClock.sleep(250)
            }
            last = device.findObject(By.res("task-route-12"))
            val finalToolbar = device.findObject(By.res("task-forwarding-toolbar")).visibleBounds
            check(finalToolbar == toolbarBefore) { "toolbar moved while scrolling" }
            val lastBounds = (last ?: error("last row unreachable")).visibleBounds
            check(lastBounds.top > listBounds.top) { "last row clipped at top: $lastBounds / $listBounds" }
            check(lastBounds.bottom < finalToolbar.top) { "last row obscured: $lastBounds / $finalToolbar" }
            val input = device.findObject(By.descContains("输入转发通道，第 13 行")) ?: error("last input missing")
            var output = device.findObject(By.descContains("输出转发通道，第 13 行")) ?: error("last output missing")
            check(input.visibleBounds.bottom < finalToolbar.top && output.visibleBounds.bottom < finalToolbar.top)
            val artifactDir = File(targetContext.getExternalFilesDir(null), "toolbar-qa").apply { mkdirs() }
            device.takeScreenshot(File(artifactDir, "last-row.png"))
            device.dumpWindowHierarchy(File(artifactDir, "last-row.xml"))
            input.click()
            val inputName = s.inputs.getValue(inKey).device.productName.toString()
            check(waitUntil(3000) { device.findObject(By.textContains(inputName)) != null }) { "input menu did not open" }
            device.pressBack()
            check(waitUntil(3000) { device.findObject(By.textContains(inputName)) == null }) { "input menu did not close" }
            output = device.findObject(By.descContains("输出转发通道，第 13 行")) ?: error("last output missing after menu")
            output.click()
            val outputName = s.outputs.getValue(outKey).device.productName.toString()
            check(waitUntil(3000) { device.findObject(By.textContains(outputName)) != null }) { "output menu did not open" }
            device.pressBack()
            check(waitUntil(3000) { device.findObject(By.textContains(outputName)) == null }) { "output menu did not close" }
            return true to "13 rows; fixed wrap-content toolbar=$finalToolbar; last row=$lastBounds; both final dropdowns usable"
        } finally {
            runOnMainSync {
                s.switchTab(0)
                s.model.clear()
                for (original in originalRows.filterNot { it.blank() }) {
                    val blank = s.model.rows.last()
                    s.setRouteSide(blank, true, original.input)
                    s.setRouteSide(blank, false, original.output)
                }
                s.switchTab(2)
            }
        }
    }

    private fun scenarioForwardStartStop(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        val inKey = firstEnabledInput(s)?.key ?: return false to "no enabled input"
        val outKey = firstEnabledOutput(s)?.key ?: return false to "no enabled output"
        if (!waitUntil(10000) { !s.operationsBlockedPublic && !s.runningPublic }) {
            return false to "session busy before start"
        }
        setPair(s, inKey, outKey)
        runOnMainSync {
            s.prepareForwarding {
                if (s.startingPublic && !s.loadingPublic) {
                    targetContext.startForegroundService(Intent(targetContext, ForwardingService::class.java))
                }
            }
        }
        if (!waitUntil(25000) { s.runningPublic }) {
            return false to "forwarding did not start; " + diagnostics(s)
        }
        if (!waitUntil(8000) { s.actualStats.isNotEmpty() }) {
            return false to "no stream stats while running; " + diagnostics(s)
        }
        val stats = s.actualStats.values.firstOrNull()?.replace("\n", "; ") ?: ""
        if (!waitUntil(3000) { s.service != null }) return false to "service not attached"
        runOnMainSync { s.stop(null, false) }
        if (!waitUntil(20000) { !s.runningPublic && s.service == null }) {
            return false to "forwarding did not stop"
        }
        return true to "start/stop ok; stats=$stats"
    }

    private fun scenarioErrorStop(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        val inKey = firstEnabledInput(s)?.key ?: return false to "no enabled input"
        val outKey = firstEnabledOutput(s)?.key ?: return false to "no enabled output"
        setPair(s, inKey, outKey)
        runOnMainSync {
            s.prepareForwarding {
                if (s.startingPublic && !s.loadingPublic) {
                    targetContext.startForegroundService(Intent(targetContext, ForwardingService::class.java))
                }
            }
        }
        if (!waitUntil(25000) { s.runningPublic }) {
            return false to "forwarding did not start; " + diagnostics(s)
        }
        NativeAudio.injectError(-899) // oboe Result::ErrorDisconnected
        if (!waitUntil(10000) { !s.runningPublic }) {
            return false to "engine error did not stop forwarding; " + diagnostics(s)
        }
        Thread.sleep(1500)
        val stopped = !s.runningPublic && s.service == null
        return stopped to "raw error path stopped forwarding (status=${s.status})"
    }

    private fun scenarioRotation(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        // Stop the platform launcher first: on this emulator the launcher
        // crashes on rotation and its dialog would steal the window focus.
        device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
        dismissSystemDialogs()
        device.setOrientationLeft()
        SystemClock.sleep(3500)
        dismissSystemDialogs()
        bringToFront()
        var landscapeOk = false
        var landscapeSample = ""
        for (attempt in 0 until 8) {
            if (attempt == 2) dismissSnackbar()
            val texts = dumpTextsGuarded()
            // In landscape the parameter row can sit below the fold; the device
            // list and the navigation bar are the visible landmarks.
            if (texts.any { it.contains("任务控制") } &&
                (texts.any { it.contains("sdk_gphone") } || texts.any { it.contains("个转发") })) {
                landscapeOk = true
                break
            }
            landscapeSample = "count=" + texts.size +
                " hasNav=" + texts.any { it.contains("任务控制") } +
                " hasRate=" + texts.any { it.contains("采样率") } +
                " hasMic=" + texts.any { it.contains("sdk_gphone") }
            SystemClock.sleep(1200)
        }
        device.setOrientationNatural()
        SystemClock.sleep(3500)
        device.unfreezeRotation()
        dismissSystemDialogs()
        bringToFront()
        var portraitOk = false
        for (attempt in 0 until 8) {
            val texts = dumpTextsGuarded()
            if (texts.any { it.contains("任务控制") } && s.inputs.isNotEmpty()) {
                portraitOk = true
                break
            }
            SystemClock.sleep(1200)
        }
        val sample = if (landscapeOk) "" else " landscapeTexts=[" + landscapeSample + "]"
        return (landscapeOk && portraitOk) to
            "landscape=$landscapeOk portrait=$portraitOk session inputs=${s.inputs.size}" + sample
    }

    private fun compactPage(index: Int): Boolean {
        fun selected(page: Int): Boolean {
            val item = device.findObject(By.desc("第 ${page + 1} 页")) ?: return false
            return actionSelected(item)
        }
        repeat(5) {
            val current = (0..2).firstOrNull(::selected) ?: return false
            if (current == index) return true
            // UiDevice reports physical dimensions even after a wm size override.
            // Derive gesture coordinates from the actual indicator's UI bounds.
            val footer = device.findObject(By.desc("页面指示器"))?.visibleBounds ?: return false
            val w = footer.right
            val h = footer.bottom
            val left = (w * 0.24f).toInt()
            val right = (w * 0.76f).toInt()
            device.swipe(if (current < index) right else left, (h * 0.45f).toInt(),
                if (current < index) left else right, (h * 0.45f).toInt(), 55)
            SystemClock.sleep(700)
        }
        return selected(index)
    }

    private fun scenarioCompactMode(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s)) return false to "discovery unfinished"
        val watch = targetContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_WATCH
        val sizeBefore = device.executeShellCommand("wm size")
        val previousOverride = Regex("Override size: ([0-9]+x[0-9]+)").find(sizeBefore)?.groupValues?.get(1)
        val alreadyCompact = hasText("页面指示器")
        if (!alreadyCompact) {
            val ins = s.inputs.values.filter { it.key !in s.disabled }
            val outs = s.outputs.values.filter { it.key !in s.disabled }
            if (ins.isEmpty() || outs.isEmpty()) return false to "no route endpoints"
            setPair(s, ins.first().key, outs.first().key)
            if (ins.size > 1 || outs.size > 1) runOnMainSync {
                val blank = s.model.rows.last()
                s.setRouteSide(blank, true, ins.getOrElse(1) { ins.first() }.key)
                s.setRouteSide(blank, false, outs.getOrElse(1) { outs.first() }.key)
            }
        }
        val routesBefore = s.model.rows.map { it.input to it.output }
        try {
            if (!alreadyCompact) {
                device.executeShellCommand("wm size 600x600")
                SystemClock.sleep(3500)
                bringToFront()
            }
            if (!compactPage(0)) return false to "cannot reach input page; texts=${dumpTextsGuarded()}; size=${device.executeShellCommand("wm size")}; ${diagnostics(s)}"
            val first = dumpTextsGuarded()
            if (first.any { it == "输入" || it == "输出" || it == "任务控制" || it.contains("小涛29开发") }) {
                return false to "extra heading/navigation on list page"
            }
            if (!first.contains("输入通道列表") || !first.contains("空白，清除输入通道")) {
                return false to "input list/blank dropdown option missing: $first"
            }
            if ((1..3).any { !first.contains("第 $it 页") }) return false to "three indicators missing"
            if (!compactPage(1) || !hasText("输出通道列表")) return false to "output page missing"
            if (!compactPage(2) || !hasText("转发信息") || !hasText("个转发")) return false to "information/action page missing"
            if (s.model.rows.map { it.input to it.output } != routesBefore) return false to "resolution switch changed routes"
            if (!hasText("启动 ${s.model.validCount()} 个转发")) return false to "transition count incorrect"
            return true to "watch=$watch; title-free lists, blank options, 3 checked dots, information/action; preserved ${s.model.validCount()} routes"
        } finally {
            if (!alreadyCompact) {
                device.executeShellCommand("wm size ${previousOverride ?: "reset"}")
                SystemClock.sleep(2500)
                bringToFront()
            }
        }
    }

    /** Traverse and activate phone controls using real remote-control key events. */
    private fun scenarioKeyboardPhone(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic }) return false to "session busy"
        val activity = s.listener as? MainActivity ?: return false to "activity missing"
        val keys = KeyboardAudit(this, device, activity, ::publish)
        runOnMainSync { s.model.clear(); s.consumeNotice() }
        // A previous scenario may still have an expiring Snackbar. Let it leave
        // before auditing stable controls; audit its close action separately.
        SystemClock.sleep(10000)
        for (tab in 0..2) {
            runOnMainSync {
                s.switchTab(tab)
                activity.setContent { MonitorApp(activity, s) }
            }
            SystemClock.sleep(700)
            keys.sweep("phone-tab-$tab")
            if (tab < 2) {
                keys.seek("设备能力")
                val anchor = keys.focused()?.id
                keys.press(android.view.KeyEvent.KEYCODE_ENTER)
                check(waitUntil(3000) { keys.focused()?.label == "关闭" }) { "capability close unreachable" }
                keys.screenshot("phone-$tab-capability")
                keys.press(android.view.KeyEvent.KEYCODE_BACK)
                check(waitUntil(3000) { keys.focused()?.id == anchor }) { "capability did not restore focus" }
                for (label in if (tab == 0) listOf("采样率", "采样深度", "录音预设") else listOf("采样率", "采样深度")) {
                    keys.seek(label)
                    val field = keys.focused()?.id
                    keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    val channel = (if (tab == 0) s.inputs else s.outputs).values.first()
                    val count = when (label) {
                        "采样率" -> cn.xiaotao29.HighSpeedIEM.audio.DeviceSettings.rates(channel.device).size
                        "采样深度" -> cn.xiaotao29.HighSpeedIEM.audio.DeviceSettings.formats(channel.device).size
                        else -> cn.xiaotao29.HighSpeedIEM.audio.DeviceSettings.PRESET_VALUES.size
                    }
                    keys.menu("$tab-$label", count)
                    keys.press(android.view.KeyEvent.KEYCODE_BACK)
                    check(waitUntil(3000) { keys.focused()?.id == field }) { "$label Back lost anchor" }
                }
                if (tab == 0) {
                    val input = firstEnabledInput(s) ?: error("no input")
                    keys.seek("ID ${input.device.id}，通道 ${input.index}，")
                    val card = keys.focused()?.id
                    keys.press(android.view.KeyEvent.KEYCODE_ENTER)
                    check(waitUntil(10000) { s.spectrumBinsPublic > 0 }) { "key preview failed" }
                    check(waitUntil(3000) { keys.focused()?.label == "关闭" }) { "spectrum close unreachable" }
                    keys.screenshot("phone-spectrum")
                    keys.press(android.view.KeyEvent.KEYCODE_ENTER)
                    check(waitUntil(10000) { s.selectedInputPublic == null && !s.previewOpeningPublic }) { "spectrum key close failed" }
                    check(waitUntil(3000) { keys.focused()?.id == card }) { "spectrum lost anchor" }
                } else {
                    val output = firstEnabledOutput(s) ?: error("no output")
                    keys.seek("ID ${output.device.id}，通道 ${output.index}，")
                    keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    check(waitUntil(5000) { s.testingPublic }) { "key ringtone start failed" }
                    keys.press(android.view.KeyEvent.KEYCODE_ENTER)
                    check(waitUntil(10000) { !s.testingPublic && s.selectedOutputPublic == null }) { "key ringtone cancel failed" }
                }
            }
        }
        keys.seek("输入转发通道")
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        check(waitUntil(3000) { device.findObjects(By.checkable(true)).isNotEmpty() }) { "menu missing" }
        keys.menu("route-input", s.enabledChannels(s.inputs).size + 1)
        keys.press(android.view.KeyEvent.KEYCODE_BACK)
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        check(s.model.rows.any { it.input != null }) { "input menu selection failed" }
        check(waitUntil(3000) { keys.focused()?.label?.contains("输入转发通道") == true }) { "menu did not restore anchor focus: ${keys.focused()}" }
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        check(keys.focused()?.label?.contains("输出转发通道") == true) { "right did not reach output" }
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        check(s.model.validCount() == 1) { "output menu selection failed" }
        keys.seek("启动 1 个转发")
        keys.screenshot("phone-start-focused")
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        check(waitUntil(15000) { s.runningPublic }) { "key start failed: ${diagnostics(s)}" }
        keys.sweep("phone-running")
        keys.seek("停止转发")
        keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        check(waitUntil(15000) { !s.runningPublic && !s.operationsBlockedPublic }) { "key stop failed" }
        keys.seek("后台运行保护")
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        SystemClock.sleep(600)
        keys.press(android.view.KeyEvent.KEYCODE_BACK)
        check(waitUntil(5000) { s.visible }) { "return from system settings failed" }
        keys.press(android.view.KeyEvent.KEYCODE_HOME)
        check(waitUntil(3000) { !s.visible }) { "home did not background activity" }
        bringToFront()
        check(s.visible && s.selectedInputPublic == null && s.selectedOutputPublic == null) { "stale preview after Home" }
        runOnMainSync { s.floatingNotice("按键提示关闭测试") }
        check(waitUntil(3000) { device.hasObject(By.text("按键提示关闭测试")) })
        keys.seek("关闭")
        keys.press(android.view.KeyEvent.KEYCODE_ENTER)
        check(waitUntil(3000) { !device.hasObject(By.text("按键提示关闭测试")) }) { "Snackbar key close failed" }
        return true to "all phone controls, dropdown selection/restore, left/right, real start/stop, settings Back, Home"
    }

    private fun scenarioKeyboardOverflowPhone(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic }) return false to "session busy"
        val activity = s.listener as? MainActivity ?: return false to "activity missing"
        val original = s.model.rows
        val keys = KeyboardAudit(this, device, activity, ::publish)
        try {
            val input = firstEnabledInput(s) ?: error("no input")
            runOnMainSync {
                s.model.clear()
                repeat(12) { s.setRouteSide(s.model.rows.last(), true, input.key) }
                s.switchTab(2)
                activity.setContent { MonitorApp(activity, s) }
            }
            SystemClock.sleep(700)
            keys.seek("输出转发通道，第 13 行")
            SystemClock.sleep(600)
            val last = device.findObject(By.descContains("输出转发通道，第 13 行")) ?: error("last field missing")
            val toolbar = device.findObject(By.res("task-forwarding-toolbar")) ?: error("toolbar missing")
            check(last.visibleBounds.bottom < toolbar.visibleBounds.top) { "keyboard-focused field obscured by toolbar: ${last.visibleBounds} / ${toolbar.visibleBounds}" }
            keys.screenshot("phone-keyboard-last-row")
            // Large fonts stack the two fields vertically; use their actual
            // visual relationship instead of assuming a horizontal row.
            keys.press(if (activity.resources.configuration.fontScale > 1.2f)
                android.view.KeyEvent.KEYCODE_DPAD_UP else android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            check(keys.focused()?.label?.contains("输入转发通道，第 13 行") == true) { "last row adjacent field failed" }
            repeat(24) { keys.press(android.view.KeyEvent.KEYCODE_DPAD_UP) }
            check(keys.focused()?.label?.contains("输入转发通道，第 1 行；") == true) { "reverse long-list traversal failed: ${keys.focused()}" }
            return true to "26 dropdowns across 13 rows reached using keys; reverse traversal; focused last field above floating toolbar"
        } finally {
            runOnMainSync {
                s.model.clear()
                original.filter { !it.blank() }.forEach { saved ->
                    val row = s.model.rows.last()
                    s.setRouteSide(row, true, saved.input)
                    s.model.rows.firstOrNull { it.id == row.id }?.let { s.setRouteSide(it, false, saved.output) }
                }
                activity.setContent { MonitorApp(activity, s) }
            }
        }
    }

    private fun scenarioKeyboardCompactPhone(): Pair<Boolean, String> = keyboardCompact(small = false)

    private fun scenarioKeyboardCompactSmallPhone(): Pair<Boolean, String> = keyboardCompact(small = true)

    private fun keyboardCompact(small: Boolean): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic }) return false to "session busy"
        val activity = s.listener as? MainActivity ?: return false to "activity missing"
        val keys = KeyboardAudit(this, device, activity, ::publish)
        try {
            runOnMainSync {
                s.model.clear()
                s.switchTab(0)
                activity.setContent {
                    HighSpeedIEMTheme {
                        if (small) {
                            val roundConfiguration = android.content.res.Configuration(activity.resources.configuration).apply {
                                screenLayout = (screenLayout and android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or
                                    android.content.res.Configuration.SCREENLAYOUT_ROUND_YES
                            }
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalConfiguration provides roundConfiguration) {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.size(220.dp),
                                        shape = androidx.compose.foundation.shape.CircleShape) { CompactFlow(activity, s) }
                                }
                            }
                        } else androidx.compose.material3.Surface { CompactFlow(activity, s) }
                    }
                }
            }
            SystemClock.sleep(700)
            keys.sweep("compact-input")
            val input = firstEnabledInput(s) ?: error("no input")
            keys.seek("ID ${input.device.id} · 通道 ${input.index}")
            keys.press(android.view.KeyEvent.KEYCODE_ENTER)
            check(s.model.compactRow.input == input.key) { "compact input key pick failed" }
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            keys.sweep("compact-output")
            val output = firstEnabledOutput(s) ?: error("no output")
            keys.seek("ID ${output.device.id} · 通道 ${output.index}")
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            check(s.model.compactRow.output == output.key) { "compact output key pick failed" }
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            keys.sweep("compact-task")
            keys.seek("启动 1 个转发")
            keys.screenshot("compact-start-focused")
            keys.press(android.view.KeyEvent.KEYCODE_ENTER)
            check(waitUntil(15000) { s.runningPublic }) { "compact key start failed: ${diagnostics(s)}" }
            if (small) {
                check(waitUntil(5000) { s.actualStats.isNotEmpty() }) { "compact stats missing" }
                keys.seek("输入\n")
                val info = keys.focused() ?: error("information scroll target missing")
                keys.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                SystemClock.sleep(400)
                check((keys.focused()?.scrollValue ?: 0f) > (info.scrollValue ?: 0f)) { "small information cannot scroll using keys" }
                keys.screenshot("compact-round-small-scrolled")
            }
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            keys.sweep("compact-running-locked")
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            keys.seek("停止转发")
            keys.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            check(waitUntil(15000) { !s.runningPublic && !s.operationsBlockedPublic }) { "compact key stop failed" }
            return true to "actual CompactFlow on phone; every list action and page tab, left/right, Enter/Center, locked selections, real audio start/stop"
        } finally {
            runOnMainSync {
                s.stop(null, false)
                activity.setContent { MonitorApp(activity, s) }
            }
            waitUntil(10000) { !s.operationsBlockedPublic }
        }
    }

    private fun scenarioCompactBindingPhone(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic }) return false to "session busy"
        val activity = s.listener as? MainActivity ?: return false to "activity missing"
        try {
            runOnMainSync {
                activity.setContent {
                    HighSpeedIEMTheme {
                        androidx.compose.material3.Surface { CompactFlow(activity, s) }
                    }
                }
            }
            if (!waitUntil(5000) { hasText("页面指示器") }) return false to "compact content missing"
            return scenarioCompactRoutes()
        } finally {
            runOnMainSync {
                s.stop(null, false)
                activity.setContent { MonitorApp(activity, s) }
            }
            waitUntil(10000) { !s.operationsBlockedPublic }
        }
    }

    private fun scenarioCompactRoutes(): Pair<Boolean, String> {
        val s = session()
        if (!awaitDiscovery(s) || !waitUntil(10000) { !s.forwardingLockedPublic }) return false to "session busy"
        if (!hasText("页面指示器")) return false to "requires watch or compact screen"
        val input = firstEnabledInput(s) ?: return false to "no enabled input"
        val output = firstEnabledOutput(s) ?: return false to "no enabled output"
        fun description(c: cn.xiaotao29.HighSpeedIEM.audio.Channel) =
            "ID ${c.device.id} · 通道 ${c.index} · ${c.typeLabel}"
        fun selected(description: String): Boolean {
            val result = waitUntil(4000) {
                val node = device.findObject(By.desc(description))
                // ListItem's descriptive node is inside its selectable wrapper.
                // Follow same-bounds ancestors to inspect the actual item state.
                val bounds = node?.visibleBounds
                var item = node
                var picked = false
                while (item != null && item.visibleBounds == bounds) {
                    if (item.isSelected || item.isChecked) { picked = true; break }
                    item = item.parent
                }
                picked
            }
            if (!result) {
                device.dumpWindowHierarchy(File(targetContext.filesDir, "compact-selection-failure.xml"))
                device.takeScreenshot(File(targetContext.filesDir, "compact-selection-failure.png"))
            }
            return result
        }
        fun screenshot(name: String) {
            device.waitForIdle(1500)
            SystemClock.sleep(400)
            device.takeScreenshot(File(targetContext.filesDir, "compact-$name.png"))
        }
        runOnMainSync {
            s.model.clear()
            s.setCompactRouteSide(true, null)
        }
        val startup = s.model.compactRow
        if (!compactPage(0) || !clickText(description(input))) return false to "input pick unavailable"
        if (!waitUntil(4000) { s.model.rows.first().input == input.key && s.model.validCount() == 0 })
            return false to "partial input lost; rows=${s.model.rows.map { it.input to it.output }}; ${diagnostics(s)}; texts=${dumpTextsGuarded()}"
        screenshot("input")
        if (!compactPage(1) || !clickText(description(output))) return false to "output pick unavailable"
        if (!waitUntil(4000) { s.model.rows.first().input == input.key && s.model.rows.first().output == output.key }) return false to "pair not preserved"
        screenshot("output")
        if (s.model.compactRow.id != startup.id) return false to "startup mapping changed"
        val nextInput = s.inputs.values.firstOrNull { it.key !in s.disabled && it.key != input.key }
        val nextOutput = s.outputs.values.firstOrNull { it.key !in s.disabled && it.key != output.key }
        val secondIn = nextInput?.key ?: input.key
        val secondOut = if (nextInput != null) output.key else nextOutput?.key
            ?: return false to "need two enabled channels for multi-route validation"
        runOnMainSync {
            val blank = s.model.rows.last()
            s.setRouteSide(blank, true, secondIn)
            s.setRouteSide(blank, false, secondOut)
        }
        val second = s.model.rows[1]
        if (s.model.compactRow.id != startup.id) return false to "adding a route changed mapping"
        if (!compactPage(2) || !hasText("启动 2 个转发") || !hasText("已就绪")) return false to "multi-route action/status not refreshed"
        screenshot("ready")
        if (!clickText("启动 2 个转发") || !waitUntil(25000) { s.runningPublic }) return false to "start failed: ${diagnostics(s)}"
        if (!waitUntil(10000) { s.actualStats[input.deviceKey]?.isNotEmpty() == true } ||
            !waitUntil(10000) { hasText("正在转发") && hasText("停止转发") })
            return false to "running information missing; ${diagnostics(s)}; texts=${dumpTextsGuarded()}"
        val firstStats = s.actualStats[input.deviceKey].orEmpty()
        if (firstStats.isEmpty() || !hasText(firstStats.lineSequence().first().take(20))) return false to "first-row stats not displayed: $firstStats"
        screenshot("running")
        if (!compactPage(0)) return false to "cannot inspect locked selection"
        screenshot("locked")
        // A merged Compose accessibility wrapper may still report enabled=true.
        // Exercise the actual touch path instead: neither a different item nor
        // the blank item may change a running route.
        if (nextInput != null) clickText(description(nextInput))
        clickText("空白，清除输入通道")
        SystemClock.sleep(500)
        if (s.model.validCount() != 2 || s.model.rows.first().input != input.key)
            return false to "running list accepted a UI edit"
        runOnMainSync { s.setCompactRouteSide(true, null) }
        if (s.model.validCount() != 2 || s.model.rows.first().input != input.key) return false to "running routes mutated"
        if (!compactPage(2) || !clickText("停止转发") || !waitUntil(20000) { !s.runningPublic && !s.operationsBlockedPublic }) return false to "stop failed"
        if (!compactPage(0) || !clickText("空白，清除输入通道")) return false to "input clear unavailable"
        if (!waitUntil(4000) { s.model.validCount() == 1 && s.model.compactRow.id == startup.id && s.model.compactRow.input == null && s.model.compactRow.output == output.key }) return false to "input clear lost output/mapping"
        if (!compactPage(1) || !selected(description(output))) return false to "retained output highlight missing"
        screenshot("input-cleared-output-retained")
        if (!compactPage(2) || !hasText("启动 1 个转发")) return false to "partial count not updated"
        if (!compactPage(1) || !clickText("空白，清除输出通道")) return false to "output clear unavailable"
        if (!waitUntil(4000) { s.model.rows.size == 2 && s.model.compactRow.blank() && s.model.compactRow.id != startup.id && s.model.rows.first().id == second.id }) return false to "both-clear adopted existing route"
        if (!compactPage(0) || !selected("空白，清除输入通道")) return false to "new input blank highlight missing"
        screenshot("rebound-blank-with-existing-route")
        // A conflict against an earlier surviving row removes the mapped row.
        val conflicting = s.model.compactRow
        val conflictInput = s.inputs[secondIn] ?: return false to "second input missing"
        val conflictOutput = s.outputs[secondOut] ?: return false to "second output missing"
        if (!clickText(description(conflictInput))) return false to "conflict input pick missing"
        if (!compactPage(1) || !clickText(description(conflictOutput))) return false to "conflict output pick missing"
        if (!waitUntil(4000) { s.model.compactRow.id != conflicting.id && s.model.compactRow.blank() && s.model.rows.first().id == second.id && s.model.validCount() == 1 }) return false to "dedupe did not reset mapping to add row"
        if (!selected("空白，清除输出通道")) return false to "conflict blank highlight missing"
        screenshot("conflict-reset")
        // Select output first to rebuild behind the surviving row.
        val rebuilt = s.model.compactRow
        if (!clickText(description(output)) || !compactPage(0) || !clickText(description(input))) return false to "rebuild behind survivor failed"
        if (!waitUntil(4000) { s.model.compactRow.id == rebuilt.id && s.model.compactRow.complete() && s.model.rows.first().id == second.id && s.model.validCount() == 2 }) return false to "rebuild edited existing first row"
        // Reverse clear order exercises output-only clearing with input retained.
        if (!compactPage(1) || !clickText("空白，清除输出通道")) return false to "reverse clear unavailable"
        if (!waitUntil(4000) { s.model.compactRow.id == rebuilt.id && s.model.compactRow.input == input.key && s.model.compactRow.output == null }) return false to "output clear lost input"
        if (!compactPage(0) || !selected(description(input))) return false to "retained input highlight missing"
        screenshot("output-cleared-input-retained")
        if (!clickText("空白，清除输入通道")) return false to "reverse last clear unavailable"
        if (!waitUntil(4000) { s.model.compactRow.blank() && s.model.compactRow.id != rebuilt.id && s.model.rows.first().id == second.id }) return false to "reverse clear did not rebind"
        // Real session pruning on disable follows the same reset path.
        if (!clickText(description(input)) || !compactPage(1) || !clickText(description(output))) return false to "prune setup failed"
        val pruned = s.model.compactRow
        runOnMainSync { s.enable(input, false) }
        if (!waitUntil(4000) { s.model.compactRow.id != pruned.id && s.model.compactRow.blank() && pruned !in s.model.rows }) return false to "disabled endpoint did not reset binding"
        if (!compactPage(0) || !selected("空白，清除输入通道")) return false to "prune input blank not shown"
        runOnMainSync { s.enable(input, true) }
        screenshot("pruned-reset")
        // Clear fixture rows and validate the genuinely empty disabled action.
        runOnMainSync {
            for (row in s.model.rows.toList()) {
                s.setRouteSide(row, true, null)
                s.setRouteSide(row, false, null)
            }
        }
        if (!compactPage(2) || !hasText("启动 0 个转发") || !hasText("待配置")) return false to "empty state not refreshed"
        clickText("启动 0 个转发")
        SystemClock.sleep(300)
        if (s.runningPublic || s.startingPublic || s.notices.value != null)
            return false to "empty action accepted a click"
        screenshot("empty")
        // Leave one ready route for manual UI review, with forwarding stopped.
        if (!compactPage(1) || !clickText(description(output))) return false to "output-first pick failed"
        if (!compactPage(0) || !clickText(description(input))) return false to "input-second pick failed"
        if (!compactPage(2) || !hasText("启动 1 个转发")) return false to "output-first pair lost"
        return true to "fixed startup binding; both single-side clears retain opposite highlight; both-clear add-row reset; extra routes; dedupe/prune reset; 2-route start/stop, stats, lock; ready handoff"
    }
}
