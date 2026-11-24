package br.malhaviva.core.domain.sampling

import br.malhaviva.core.domain.checkin.CheckInPolicy
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.checkin.distanceM
import br.malhaviva.core.domain.tracking.Route
import br.malhaviva.core.domain.tracking.TrackPolicy

enum class StopReason { LeftCorridor, StoppedOutsideStation, MaxDuration }

/** Regras de fim automatico de viagem (BRIEF 3.1); valores PROPOSTOS. */
object AutoStopPolicy {
    const val OFF_ROUTE_READINGS = 3
    const val STOPPED_OUTSIDE_MS = 600_000L
    const val MAX_DURATION_MS = 10_800_000L
}

/**
 * Motivo para encerrar a viagem ou null para continuar.
 * [recent] sao as leituras mais recentes (ultima = atual); [stoppedMs] e o tempo parado ate agora.
 */
fun shouldStopTrip(
    route: Route,
    recent: List<Reading>,
    stoppedMs: Long,
    tripMs: Long,
): StopReason? =
    when {
        tripMs >= AutoStopPolicy.MAX_DURATION_MS -> StopReason.MaxDuration
        leftCorridor(route, recent) -> StopReason.LeftCorridor
        stoppedOutsideStation(route, recent, stoppedMs) -> StopReason.StoppedOutsideStation
        else -> null
    }

private fun leftCorridor(
    route: Route,
    recent: List<Reading>,
): Boolean {
    val last = recent.takeLast(AutoStopPolicy.OFF_ROUTE_READINGS)
    return last.size == AutoStopPolicy.OFF_ROUTE_READINGS &&
        last.all { route.project(it.point).lateralM > TrackPolicy.MAX_LATERAL_M }
}

private fun stoppedOutsideStation(
    route: Route,
    recent: List<Reading>,
    stoppedMs: Long,
): Boolean {
    val now = recent.lastOrNull() ?: return false
    val nearStation =
        route.stationsAlong.any { distanceM(now.point, it.first.point) <= CheckInPolicy.STATION_RADIUS_M }
    return stoppedMs > AutoStopPolicy.STOPPED_OUTSIDE_MS && !nearStation
}
