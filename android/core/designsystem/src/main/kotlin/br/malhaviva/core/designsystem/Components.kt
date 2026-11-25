package br.malhaviva.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Trem no mapa/linha: circulo com o mesmo glifo do [StatusBadge]. */
@Composable
fun TrainMarker(
    status: TrainStatus,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MalhaVivaTheme.colors
    val color = colors.status.colorOf(status)
    Box(
        modifier =
            modifier
                .size(28.dp)
                .clearAndSetSemantics { contentDescription = description }
                .background(colors.surface, CircleShape)
                .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) { drawGlyph(status, color) }
    }
}

/** Botao principal de check-in: alvo de toque de 56 dp. */
@Composable
fun CheckInButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(MalhaVivaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MalhaVivaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** Mostra progresso enquanto atualiza; senao, o texto de ultima atualizacao. */
@Composable
fun RefreshIndicator(
    refreshing: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Box(Modifier.width(MalhaVivaSpacing.s))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MalhaVivaTheme.colors.textMuted)
    }
}

@Composable
fun LineHeader(
    line: String,
    direction: String,
    status: TrainStatus,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(MalhaVivaSpacing.l)) {
        Text(line, style = MaterialTheme.typography.titleLarge)
        Text(direction, style = MaterialTheme.typography.bodyLarge, color = MalhaVivaTheme.colors.textMuted)
        StatusBadge(status, statusLabel, Modifier.padding(top = MalhaVivaSpacing.s))
    }
}

enum class StopState { Passed, Current, Upcoming }

data class TimelineStop(
    val name: String,
    val time: String,
    val state: StopState,
    val detail: String? = null,
    /** Toque na linha (ex.: escolher a estacao); nulo = linha nao clicavel. */
    val onClick: (() -> Unit)? = null,
)

@Composable
fun LineTimeline(
    stops: List<TimelineStop>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        stops.forEachIndexed { i, stop ->
            TimelineRow(stop, first = i == 0, last = i == stops.lastIndex)
        }
    }
}

@Composable
private fun TimelineRow(
    stop: TimelineStop,
    first: Boolean,
    last: Boolean,
) {
    val colors = MalhaVivaTheme.colors
    val current = stop.state == StopState.Current
    val stateText =
        when (stop.state) {
            StopState.Current -> "aqui"
            StopState.Passed -> "passou"
            StopState.Upcoming -> "previsto"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(if (stop.onClick != null) Modifier.clickable(onClick = stop.onClick) else Modifier)
                .clearAndSetSemantics {
                    contentDescription =
                        listOfNotNull(stop.name, stop.time, stateText, stop.detail).joinToString(", ")
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(32.dp).height(48.dp)) {
            val x = size.width / 2
            val y = size.height / 2
            val line = 2.dp.toPx()
            if (!first) drawLine(colors.textMuted, Offset(x, 0f), Offset(x, y), line)
            if (!last) drawLine(colors.textMuted, Offset(x, y), Offset(x, size.height), line)
            when (stop.state) {
                StopState.Passed -> drawCircle(colors.textMuted, 5.dp.toPx(), Offset(x, y))
                StopState.Upcoming -> drawCircle(colors.textMuted, 5.dp.toPx(), Offset(x, y), style = Stroke(line))
                StopState.Current -> {
                    drawCircle(colors.primary, 9.dp.toPx(), Offset(x, y))
                    drawCircle(colors.surface, 4.dp.toPx(), Offset(x, y))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                stop.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) colors.primary else colors.text,
            )
            stop.detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textMuted) }
        }
        Text(stop.time, style = TimeTextStyle.copy(fontSize = 17.sp), color = colors.textMuted)
        Box(Modifier.width(MalhaVivaSpacing.l))
    }
}
