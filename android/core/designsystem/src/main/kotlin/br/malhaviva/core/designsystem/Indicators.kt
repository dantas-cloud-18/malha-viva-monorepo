package br.malhaviva.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CrowdLevel(
    val bars: Int,
) {
    Empty(ONE),
    Moderate(TWO),
    Full(BARS),
}

/** Lotacao = barras preenchidas + texto; nunca so cor. */
@Composable
fun CrowdIndicator(
    level: CrowdLevel,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MalhaVivaTheme.colors
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 20.dp, height = 14.dp)) {
            val barW = size.width / BARS_TOTAL_UNITS
            repeat(BARS) { i ->
                val h = size.height * (i + 1) / BARS
                val filled = i < level.bars
                val topLeft = Offset(i * 2 * barW, size.height - h)
                if (filled) {
                    drawRect(colors.primary, topLeft, Size(barW * 2 - barW / 2, h))
                } else {
                    drawRect(colors.textMuted, topLeft, Size(barW * 2 - barW / 2, h), style = Stroke(1.dp.toPx()))
                }
            }
        }
        Text(
            text = label,
            color = colors.textMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = MalhaVivaSpacing.xs),
        )
    }
}

/** Confianca 0..1: arco cheio = alta; tracejado quando abaixo de [LOW]. */
@Composable
fun ConfidenceRing(
    confidence: Float,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 24.dp,
) {
    val colors = MalhaVivaTheme.colors
    val value = confidence.coerceIn(0f, 1f)
    Canvas(modifier.size(diameter).clearAndSetSemantics { contentDescription = description }) {
        val line = 3.dp.toPx()
        val inset = line / 2
        val arcSize = Size(size.width - line, size.height - line)
        val topLeft = Offset(inset, inset)
        drawArc(colors.textMuted, 0f, FULL, false, topLeft, arcSize, style = Stroke(1.dp.toPx()))
        val color = if (value < LOW) colors.status.lowConfidence else colors.status.onTime
        val effect = if (value < LOW) PathEffect.dashPathEffect(floatArrayOf(DASH, DASH)) else null
        drawArc(
            color,
            -QUARTER,
            FULL * value,
            false,
            topLeft,
            arcSize,
            style = Stroke(line, cap = StrokeCap.Round, pathEffect = effect),
        )
    }
}

private const val BARS = 3
private const val BARS_TOTAL_UNITS = 6f
private const val LOW = 0.5f
private const val FULL = 360f
private const val QUARTER = 90f
private const val DASH = 6f
private const val ONE = 1
private const val TWO = 2
