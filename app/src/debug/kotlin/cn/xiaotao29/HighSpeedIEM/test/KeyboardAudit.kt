package cn.xiaotao29.HighSpeedIEM.test

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.uiautomator.UiDevice
import cn.xiaotao29.HighSpeedIEM.MainActivity

/** Reads actual Compose semantics; every navigation/activation uses real remote key events.
 * No performClick/requestFocus or touch coordinates are used by this audit. */
internal class KeyboardAudit(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val activity: MainActivity,
    private val report: (String) -> Unit,
) {
    data class Target(val id: Int, val label: String, val focused: Boolean,
        val disabled: Boolean, val actionable: Boolean, val bounds: androidx.compose.ui.geometry.Rect,
        val scrollValue: Float?)

    fun targets(): List<Target> {
        val result = ArrayList<Target>()
        instrumentation.runOnMainSync {
            fun inspect(view: View) {
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                    val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
                    fun text(node: SemanticsNode): String {
                        val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        if (description.isNotEmpty()) return description.joinToString(" / ")
                        val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                        return (own + node.children.map { text(it) }).filter { it.isNotBlank() }.joinToString(" / ")
                    }
                    fun walk(node: SemanticsNode) {
                        // Lazy grids retain prefetched/beyond-bounds nodes without placing them.
                        // Their last coordinates do not describe a visible focus target.
                        if (!node.layoutInfo.isPlaced) return
                        val focus = node.config.getOrNull(SemanticsProperties.Focused)
                        if (focus != null) result.add(Target(node.id, text(node).ifBlank { "node-${node.id}" },
                            focus, node.config.contains(SemanticsProperties.Disabled),
                            node.config.contains(SemanticsActions.OnClick) ||
                                node.config.contains(SemanticsActions.ScrollBy), node.boundsInRoot,
                            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()))
                        node.children.forEach(::walk)
                    }
                    walk(owner.unmergedRootSemanticsNode)
                }
                if (view is ViewGroup) repeat(view.childCount) { inspect(view.getChildAt(it)) }
            }
            val windows = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
            } else listOf(activity.window.decorView)
            windows.filter { it.hasWindowFocus() }.forEach(::inspect)
        }
        return result
    }

    fun press(key: Int) {
        check(device.pressKeyCode(key)) { "key injection failed: $key" }
        SystemClock.sleep(160)
        instrumentation.waitForIdleSync()
    }

    fun focused(): Target? {
        val focused = targets().filter { it.focused }
        check(focused.size <= 1) { "multiple input focuses: $focused" }
        check(focused.none { it.disabled }) { "disabled control focused: $focused" }
        return focused.singleOrNull()
    }

    fun screenshot(name: String) {
        val dir = java.io.File(activity.getExternalFilesDir(null), "keyboard-qa").apply { mkdirs() }
        device.takeScreenshot(java.io.File(dir, "$name.png"))
        device.dumpWindowHierarchy(java.io.File(dir, "$name.xml"))
    }

    fun seek(label: String, limit: Int = 120): Target {
        repeat(limit) {
            focused()?.takeIf { it.label.contains(label) }?.let { return it }
            press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        screenshot("unreachable-target")
        error("unreachable: $label; focused=${focused()}; targets=${targets()}")
    }

    fun sweep(name: String): Set<String> {
        val seen = LinkedHashMap<String, Target>()
        // Includes offscreen device cards in a regular Column. Lazy items are
        // discovered dynamically when focus brings the next row into view.
        val expected = targets().filter { !it.disabled && it.actionable }.associateBy { it.label }.toMutableMap()
        repeat(150) {
            press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            val snapshot = targets()
            val visible = snapshot.filter { !it.disabled && it.actionable && it.bounds.width > 0 && it.bounds.height > 0 }
            check(visible.groupBy { it.bounds }.values.none { it.size > 1 }) {
                "$name: simultaneous duplicate targets at identical bounds: $visible"
            }
            check(visible.groupBy { it.label }.values.none { it.size > 1 }) { "$name: duplicate control names: $visible" }
            snapshot.filter { !it.disabled && it.actionable }.forEach { expected[it.label] = it }
            val focus = focused() ?: return@repeat
            check(focus.actionable) { "$name: non-actionable focus: $focus" }
            if (seen.containsKey(focus.label)) {
                val missing = expected.keys - seen.keys
                check(missing.isEmpty()) { "$name: unreachable=${missing.map { expected[it] }}; visited=${seen.values}" }
                val reverse = HashSet<String>()
                repeat(seen.size) {
                    press(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    val previous = focused() ?: error("$name: lost reverse focus")
                    check(reverse.add(previous.label)) { "$name: repeated reverse target $previous" }
                }
                check(reverse == seen.keys) { "$name: forward/reverse traversal differ" }
                report("KEYBOARD $name: ${seen.size} unique targets, both directions, disabled skipped; ${seen.values.joinToString { it.label }}")
                return seen.values.map { it.label }.toSet()
            }
            seen[focus.label] = focus
        }
        error("$name: traversal did not finish; visited=${seen.values}")
    }

    /** Popup menus live in a separate Android window. Inspect its actual input
     * focus after each real key, including scrolling items into view. */
    fun menu(name: String, expectedCount: Int) {
        val seen = LinkedHashSet<String>()
        repeat(100) {
            press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            val actual = focused() ?: error("$name: menu has no input focus")
            check(!actual.disabled && actual.actionable) { "$name: invalid menu target $actual" }
            val identity = actual.label
            if (!seen.add(identity)) {
                check(seen.size > 1) { "$name: arrow trapped on $identity" }
                check(seen.size == expectedCount) { "$name: only ${seen.size}/$expectedCount options reachable: $seen" }
                val reverse = HashSet<String>()
                repeat(expectedCount) {
                    press(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    reverse.add(focused()?.label ?: error("$name: lost reverse focus"))
                }
                check(reverse == seen) { "$name: forward/reverse options differ: $reverse / $seen" }
                report("KEYBOARD menu-$name: ${seen.size} options; ${seen.joinToString()}")
                return
            }
        }
        error("$name: menu traversal did not finish")
    }
}
