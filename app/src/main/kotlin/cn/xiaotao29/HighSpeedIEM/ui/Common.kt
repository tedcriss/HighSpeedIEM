package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

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
) {
    Text(
        text = text,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE),
        style = style,
        color = color,
        fontWeight = fontWeight,
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
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
