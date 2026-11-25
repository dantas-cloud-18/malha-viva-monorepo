package br.malhaviva.feature.trains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.LineHeader
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.RefreshIndicator

@Composable
internal fun TrainsHeader(
    state: TrainsState,
    views: List<TrainView>,
    onDirection: (Direction) -> Unit,
) {
    val health = lineHealth(state, views)
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
}

@Composable
internal fun ViewToggle(
    showMap: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.padding(horizontal = MalhaVivaSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s),
    ) {
        FilterChip(!showMap, { onChange(false) }, { Text(stringResource(R.string.trains_view_list)) })
        FilterChip(showMap, { onChange(true) }, { Text(stringResource(R.string.trains_view_map)) })
    }
}

@Composable
internal fun DirectionChips(
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
