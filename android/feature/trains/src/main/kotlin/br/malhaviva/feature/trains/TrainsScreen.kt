package br.malhaviva.feature.trains

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.ConfidenceRing
import br.malhaviva.core.designsystem.EmptyState
import br.malhaviva.core.designsystem.LineHeader
import br.malhaviva.core.designsystem.LineTimeline
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.MalhaVivaTheme
import br.malhaviva.core.designsystem.RefreshIndicator
import br.malhaviva.core.designsystem.StopState
import br.malhaviva.core.designsystem.TimelineStop
import br.malhaviva.core.designsystem.TrainMarker
import br.malhaviva.core.designsystem.TrainStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/** Altura de cada linha do [LineTimeline] (fixa no design system); os trens sao posicionados sobre ela. */
private val ROW_HEIGHT = 48.dp
private val MARKER_SIZE = 28.dp
private const val PERCENT = 100

/** Aba Trens: liga o ciclo de vida da tela ao ViewModel (so conecta com a tela visivel). */
@Composable
fun TrainsScreen(
    viewModel: TrainsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.setActive(true)
                    Lifecycle.Event.ON_STOP -> viewModel.setActive(false)
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            viewModel.setActive(false)
        }
    }
    TrainsContent(state, viewModel::selectDirection, viewModel::retry, modifier)
}

@Composable
fun TrainsContent(
    state: TrainsState,
    onDirection: (Direction) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val views = remember(state) { state.views() }
    val health = lineHealth(state, views)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        LineHeader(
            line = stringResource(R.string.trains_line_name),
            direction = directionLabel(state.direction),
            status = health.status,
            statusLabel = healthLabel(health),
        )
        DirectionChips(state.direction, onDirection)
        RefreshIndicator(
            refreshing = state.link == Link.Connecting || state.link == Link.Reconnecting,
            label = refreshLabel(state),
            modifier = Modifier.padding(horizontal = MalhaVivaSpacing.l, vertical = MalhaVivaSpacing.s),
        )
        val line = state.line
        when {
            line != null -> LineContent(state, line, views)
            state.networkError -> NetworkError(onRetry)
            else ->
                RefreshIndicator(
                    true,
                    stringResource(R.string.trains_loading),
                    Modifier.padding(MalhaVivaSpacing.l),
                )
        }
    }
}

@Composable
private fun NetworkError(onRetry: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.trains_network_error_title),
        message = stringResource(R.string.trains_network_error_message),
        action = { CheckInButton(stringResource(R.string.trains_network_error_retry), onRetry) },
    )
}

@Composable
private fun DirectionChips(
    selected: Direction,
    onSelect: (Direction) -> Unit,
) {
    Row(
        Modifier.padding(horizontal = MalhaVivaSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s),
    ) {
        Direction.entries.forEach { direction ->
            FilterChip(
                selected = direction == selected,
                onClick = { onSelect(direction) },
                label = { Text(shortDirectionLabel(direction)) },
            )
        }
    }
}

@Composable
private fun LineContent(
    state: TrainsState,
    line: LineNetwork,
    views: List<TrainView>,
) {
    if (views.isEmpty()) {
        val offline = state.link == Link.Reconnecting
        EmptyState(
            title = stringResource(if (offline) R.string.trains_no_signal_title else R.string.trains_empty_title),
            message = stringResource(if (offline) R.string.trains_no_signal_message else R.string.trains_empty_message),
        )
    }
    val stations = if (state.direction.sign > 0) line.stations else line.stations.reversed()
    val stops =
        stations.map { station ->
            val here = views.any { it.atStationId == station.id }
            TimelineStop(
                name = station.name,
                time =
                    if (here) {
                        stringResource(
                            R.string.trains_eta_now,
                        )
                    } else {
                        etaLabel(etaMinutes(views, station, state.direction))
                    },
                state = if (here) StopState.Current else StopState.Upcoming,
            )
        }
    Box(Modifier.fillMaxWidth().padding(horizontal = MalhaVivaSpacing.l)) {
        LineTimeline(stops)
        views.forEach { view ->
            val index = displayIndex(line, state.direction, view.alongM)
            TrainOnLine(view, index, nearestName(line, view))
        }
    }
    if (views.isNotEmpty()) TrainList(views, line)
}

@Composable
private fun TrainOnLine(
    view: TrainView,
    index: Double,
    where: String,
) {
    // Movimento linear de um tick: com a extrapolacao a cada segundo, o trem desliza sem degraus.
    val y: Dp by animateDpAsState(
        targetValue = ROW_HEIGHT * index.toFloat() + (ROW_HEIGHT - MARKER_SIZE) / 2,
        animationSpec = tween(TrainsPolicy.TICK_MS.toInt(), easing = LinearEasing),
        label = "trainY",
    )
    TrainMarker(
        status = markerStatus(view),
        description = stringResource(R.string.trains_marker_description, freshnessLabel(view.freshness), where),
        modifier = Modifier.offset(x = (32.dp - MARKER_SIZE) / 2, y = y),
    )
}

@Composable
private fun TrainList(
    views: List<TrainView>,
    line: LineNetwork,
) {
    Column(Modifier.padding(MalhaVivaSpacing.l)) {
        Text(stringResource(R.string.trains_list_title), style = MaterialTheme.typography.titleMedium)
        views.forEach { view ->
            val percent = (view.confidence * PERCENT).roundToInt()
            Row(
                Modifier.fillMaxWidth().padding(top = MalhaVivaSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfidenceRing(
                    confidence = view.confidence.toFloat(),
                    description = stringResource(R.string.trains_item_confidence, percent),
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.padding(start = MalhaVivaSpacing.m)) {
                    Text(placeLabel(view, line), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        freshnessLabel(view.freshness),
                        style = MaterialTheme.typography.labelMedium,
                        color = MalhaVivaTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

private fun markerStatus(view: TrainView): TrainStatus {
    val doubtful = view.freshness == Freshness.Estimated || view.confidence < TrainsPolicy.LOW_CONFIDENCE
    return when {
        view.freshness == Freshness.NoSignal -> TrainStatus.NoSignal
        doubtful -> TrainStatus.LowConfidence
        else -> TrainStatus.OnTime
    }
}

private fun nearestName(
    line: LineNetwork,
    view: TrainView,
): String =
    line.stations
        .minByOrNull { abs(it.alongM - view.alongM) }
        ?.name
        .orEmpty()

@Composable
private fun placeLabel(
    view: TrainView,
    line: LineNetwork,
): String {
    val name = nearestName(line, view)
    return stringResource(if (view.atStationId != null) R.string.trains_item_at else R.string.trains_item_near, name)
}
