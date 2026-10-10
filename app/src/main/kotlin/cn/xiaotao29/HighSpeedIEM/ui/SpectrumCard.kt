package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import java.util.Locale
import kotlin.math.min

/** Content hosted by the selected channel's RichTooltip. Bins: [rate, dBFS...]. */
@Composable
internal fun SpectrumContent(
    session: MonitorSession,
    modifier: Modifier = Modifier,
    retainFrame: Boolean = false,
) {
    val liveBins by session.spectrum.collectAsState()
    var lastFrame by remember(session) { mutableStateOf(FloatArray(0)) }
    SideEffect {
        if (!retainFrame) lastFrame = liveBins
    }
    // The session clears its data immediately when closing the preview.
    // Keep only the last rendered frame during the native exit transition.
    val bins = if (retainFrame) lastFrame else liveBins
    Column(
        modifier = modifier.fillMaxWidth().height(144.dp).semantics {
            contentDescription = "实时柱状频谱"
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("实时频谱", style = MaterialTheme.typography.labelLarge)
            Text("dBFS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (bins.size < 2) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "等待音频数据…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        } else {
            SpectrumPlot(bins, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun SpectrumPlot(bins: FloatArray, modifier: Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = MaterialTheme.colorScheme.primary
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        val left = 28.dp.toPx()
        val top = 6.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val bottom = size.height - 20.sp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        paint.color = labelColor.toArgb()
        paint.textSize = 10.sp.toPx()
        paint.textAlign = android.graphics.Paint.Align.LEFT
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3
            drawLine(gridColor.copy(alpha = 0.65f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText((-i * 30).toString(), 0f, y + 3.sp.toPx(), paint)
        }
        val n = bins.size - 1
        if (n < 1) return@Canvas
        // Peak aggregation retains narrow FFT peaks while leaving visible gaps.
        // Contiguous, equally sized frequency bands keep the axis linear.
        val count = min(n, min(48, ((right - left) / 6.dp.toPx()).toInt().coerceAtLeast(1)))
        val step = (right - left) / count
        val gap = min(2.dp.toPx(), step * 0.3f)
        val brush = Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.55f)), top, bottom)
        for (bar in 0 until count) {
            var peak = -90f
            for (bin in (bar * n / count + 1)..((bar + 1) * n / count)) {
                val value = bins[bin]
                if (value.isFinite()) peak = maxOf(peak, value)
            }
            val barHeight = (bottom - top) * ((peak + 90f).coerceIn(0f, 90f) / 90f)
            if (barHeight > 0f) {
                drawRoundRect(brush,
                    topLeft = Offset(left + step * bar + gap / 2, bottom - barHeight),
                    size = Size(step - gap, barHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }
        val labelY = bottom + 15.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText("0 Hz", left, labelY, paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(
            String.format(Locale.US, "%.1f kHz", bins[0] / 2000), right, labelY, paint)
    }
}
