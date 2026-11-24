package br.malhaviva.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

enum class TrainStatus { OnTime, Delayed, Interrupted, NoSignal, LowConfidence }

fun StatusColors.colorOf(status: TrainStatus): Color =
    when (status) {
        TrainStatus.OnTime -> onTime
        TrainStatus.Delayed -> delayed
        TrainStatus.Interrupted -> interrupted
        TrainStatus.NoSignal -> noSignal
        TrainStatus.LowConfidence -> lowConfidence
    }

/** Estado = cor + icone + texto; nunca so cor. */
@Composable
fun StatusBadge(
    status: TrainStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val color = MalhaVivaTheme.colors.status.colorOf(status)
    Row(
        modifier =
            modifier
                .clearAndSetSemantics { contentDescription = label }
                .border(1.5.dp, color, CircleShape)
                .padding(horizontal = MalhaVivaSpacing.s, vertical = MalhaVivaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) { drawGlyph(status, color) }
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = MalhaVivaSpacing.xs),
        )
    }
}

private fun DrawScope.drawGlyph(
    status: TrainStatus,
    color: Color,
) {
    val line = 2.dp.toPx()
    val ringRadius = size.minDimension / 2 - line / 2
    when (status) {
        TrainStatus.OnTime -> drawCircle(color)
        TrainStatus.Delayed ->
            drawPath(
                Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                },
                color,
            )
        TrainStatus.Interrupted -> drawRect(color)
        TrainStatus.NoSignal -> drawCircle(color, radius = ringRadius, style = Stroke(width = line))
        TrainStatus.LowConfidence ->
            drawCircle(
                color,
                radius = ringRadius,
                style = Stroke(width = line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))),
            )
    }
}

private const val DASH = 4f
