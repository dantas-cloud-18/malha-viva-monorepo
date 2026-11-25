package br.malhaviva.feature.trains

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

private const val SEC_PER_MS = 1000L

@Composable
internal fun directionLabel(direction: Direction): String =
    stringResource(
        when (direction) {
            Direction.CentralToSantaCruz -> R.string.trains_direction_central_to_santa_cruz
            Direction.SantaCruzToCentral -> R.string.trains_direction_santa_cruz_to_central
        },
    )

@Composable
internal fun shortDirectionLabel(direction: Direction): String =
    stringResource(
        when (direction) {
            Direction.CentralToSantaCruz -> R.string.trains_direction_short_central_to_santa_cruz
            Direction.SantaCruzToCentral -> R.string.trains_direction_short_santa_cruz_to_central
        },
    )

@Composable
internal fun confidenceLabel(band: ConfidenceBand): String =
    stringResource(
        when (band) {
            ConfidenceBand.Low -> R.string.trains_item_confidence_low
            ConfidenceBand.Medium -> R.string.trains_item_confidence_medium
            ConfidenceBand.High -> R.string.trains_item_confidence_high
        },
    )

@Composable
internal fun healthLabel(health: LineHealth): String =
    stringResource(
        when (health) {
            LineHealth.Connecting -> R.string.trains_health_connecting
            LineHealth.Live -> R.string.trains_health_live
            LineHealth.Estimated -> R.string.trains_health_estimated
            LineHealth.NoSignal -> R.string.trains_health_no_signal
        },
    )

@Composable
internal fun freshnessLabel(freshness: Freshness): String =
    stringResource(
        when (freshness) {
            Freshness.Live -> R.string.trains_item_live
            Freshness.Estimated -> R.string.trains_item_estimated
            Freshness.NoSignal -> R.string.trains_item_no_signal
        },
    )

@Composable
internal fun etaLabel(minutes: Int?): String =
    when (minutes) {
        null -> stringResource(R.string.trains_eta_none)
        0 -> stringResource(R.string.trains_eta_now)
        else -> stringResource(R.string.trains_eta_minutes, minutes)
    }

@Composable
internal fun refreshLabel(state: TrainsState): String {
    val last = state.lastUpdateMs
    val seconds = if (last == null) null else ((state.nowMs - last) / SEC_PER_MS).coerceAtLeast(0L).toInt()
    return when {
        state.link == Link.Connecting -> stringResource(R.string.trains_refresh_connecting)
        state.link == Link.Reconnecting -> stringResource(R.string.trains_refresh_reconnecting)
        seconds == null -> stringResource(R.string.trains_refresh_waiting)
        seconds < 2 -> stringResource(R.string.trains_refresh_now)
        else -> stringResource(R.string.trains_refresh_ago, seconds)
    }
}
