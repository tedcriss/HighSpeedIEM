package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * Single-line text with automatic marquee scrolling on overflow — the app-wide
 * default for every text display control (except snackbar messages, which wrap).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE),
        style = style,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/** One shared dropdown style for the whole app: native exposed dropdown menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { MarqueeText(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                focusManager.clearFocus()
            },
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { MarqueeText(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                        focusManager.clearFocus()
                    },
                )
            }
        }
    }
}

/**
 * Two-column key/value table used by the capability and realtime-parameter
 * tooltips. A shared label width keeps every row aligned.
 */
@Composable
fun InfoTable(entries: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            entries.forEach { (name, value) ->
                MarqueeText(name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {})
                MarqueeText(value.ifEmpty { "（空）" }, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "$name，${value.ifEmpty { "空" }}"
                    })
            }
        },
    ) { measurables, constraints ->
        val columnGap = 16.dp.roundToPx()
        val rowGap = 8.dp.roundToPx()
        val rows = measurables.chunked(2)
        if (rows.isEmpty()) return@Layout layout(0, 0) {}
        val labelWidth = (rows.maxOf { it[0].maxIntrinsicWidth(Constraints.Infinity) })
            .coerceAtMost((constraints.maxWidth - columnGap).coerceAtLeast(0) / 2)
        val valueWidth = (constraints.maxWidth - columnGap - labelWidth).coerceAtLeast(0)
        val cells = rows.map { row ->
            row[0].measure(Constraints(maxWidth = labelWidth)) to
                row[1].measure(Constraints(maxWidth = valueWidth))
        }
        val height = cells.sumOf { (label, value) -> maxOf(label.height, value.height) } +
            rowGap * (cells.size - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(labelWidth + columnGap + valueWidth),
            constraints.constrainHeight(height)) {
            var y = 0
            cells.forEach { (label, value) ->
                label.placeRelative(0, y)
                value.placeRelative(labelWidth + columnGap, y)
                y += maxOf(label.height, value.height) + rowGap
            }
        }
    }
}

/**
 * Shared page header for all three tabs: title with a live status badge and a
 * single-line description, matching the pre-rewrite app heading style.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PageHeading(title: String, description: String, status: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MarqueeText(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            StatusBadge(status)
        }
        MarqueeText(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Live status pill beside the page title. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBadge(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        MarqueeText(
            status,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Full-screen dialog shared by every full-screen dialog in the app.
 *
 * The dialog owns its window: it is edge-to-edge (background paints under the
 * transparent system bars and the display cutout, no letterbox black bars) and
 * lays out into cutout areas on the short edges — the top edge in portrait and
 * the left/right edge in either landscape rotation. Bar visibility follows the
 * orientation only (status bar visible in portrait, hidden in landscape; the
 * navigation bar and gesture pill always remain), configured on the dialog
 * window itself so the activity window keeps its own rules after dismiss.
 * Content must apply [safeContentInsets] once at its root to stay in the safe
 * area beneath the visible bars and the cutout.
 */
@Composable
fun ImmersiveDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val darkTheme = isSystemInDarkTheme()
        val orientation = LocalConfiguration.current.orientation
        LaunchedEffect(window, darkTheme, orientation) {
            window ?: return@LaunchedEffect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // SHORT_EDGES covers the cutout on the top edge in portrait and
                // on the left/right edge in both landscape rotations; the
                // attribute itself only exists from API 28.
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            configureEdgeToEdgeWindow(window, darkTheme, orientation)
        }
        content()
    }
}

/**
 * Official edge-to-edge configuration for any window, activity or dialog:
 * transparent system bars without the system contrast scrim so the caller's
 * own background shows through beneath them, icon luminosity matching the
 * theme, and status-bar visibility driven by orientation only — visible in
 * portrait, hidden in landscape — while the navigation bar and the gesture
 * pill always remain visible.
 */
fun configureEdgeToEdgeWindow(window: Window, darkTheme: Boolean, orientation: Int) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // From API 35 the bars are always transparent and these setters are
        // deprecated no-ops; below that, clear the translucent defaults.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // The scrim-based bar styles enforce a system contrast layer behind
        // the bars; disable it so the window background stays continuous.
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !darkTheme
    controller.isAppearanceLightNavigationBars = !darkTheme
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
}

/**
 * Rotation-aware display-cutout plus curved-edge insets. Consumed once at a
 * screen root so backgrounds still paint edge to edge while navigation and
 * page content move inside the safe area; consuming here also keeps inner
 * scaffolds from adding the same margins a second time.
 */
@Composable
fun cutoutWaterfallInsets(): WindowInsets =
    WindowInsets.displayCutout.union(WindowInsets.waterfall)

/**
 * Full safe content area for edge-to-edge surfaces: system bars, display
 * cutout, waterfall and the input method, each reported for the current
 * rotation. Union keeps correctness whether or not the platform already folds
 * waterfall into safeDrawing.
 */
@Composable
fun safeContentInsets(): WindowInsets =
    WindowInsets.safeDrawing.union(WindowInsets.waterfall).union(WindowInsets.ime)
