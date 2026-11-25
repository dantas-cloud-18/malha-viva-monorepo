package br.malhaviva.core.domain.checkin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

data class Reading(
    val point: GeoPoint,
    val accuracyM: Float,
    val speedKmh: Float,
    val timeMs: Long,
    /** Rumo do GPS (0 a 360, exclusivo), quando o aparelho informa; so vale em movimento. */
    val bearingDeg: Float? = null,
)

data class Station(
    val id: String,
    val point: GeoPoint,
)

enum class RejectReason { LowAccuracy, NotEnoughReadings, Stale, OutsideStation, OutsideCorridor, TooSlow }

sealed interface CheckInResult {
    data class Accepted(
        val stationId: String? = null,
    ) : CheckInResult

    /** Parado na estacao: espera o trem se mover. */
    data object Pending : CheckInResult

    data class Rejected(
        val reason: RejectReason,
    ) : CheckInResult
}

/** Criterios do BRIEF 3.1; valores PROPOSTOS, ajustar com dados reais. */
object CheckInPolicy {
    const val STATION_RADIUS_M = 150.0
    const val MAX_ACCURACY_M = 50f
    const val MIN_READINGS = 2
    const val WINDOW_MS = 30_000L
    const val CORRIDOR_MAX_M = 40.0
    const val MIN_SPEED_KMH = 15f
}

private const val EARTH_M = 111_320.0
private const val HALF_TURN = 180.0

/** Distancia em metros (projecao equirretangular; erro desprezivel em ~10 km). */
fun distanceM(
    a: GeoPoint,
    b: GeoPoint,
): Double {
    val k = cos((a.lat + b.lat) / 2 * PI / HALF_TURN)
    return hypot((b.lat - a.lat) * EARTH_M, (b.lon - a.lon) * EARTH_M * k)
}

/** Menor distancia em metros do ponto ao tracado (polilinha). */
fun distanceToPath(
    p: GeoPoint,
    path: List<GeoPoint>,
): Double {
    require(path.size >= 2) { "tracado precisa de 2 pontos" }
    val k = cos(p.lat * PI / HALF_TURN)

    fun xy(g: GeoPoint) = (g.lon - p.lon) * EARTH_M * k to (g.lat - p.lat) * EARTH_M
    return path.zipWithNext().minOf { (a, b) ->
        val (ax, ay) = xy(a)
        val (bx, by) = xy(b)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        hypot(ax + t * dx, ay + t * dy)
    }
}

private fun nearest(
    p: GeoPoint,
    stations: List<Station>,
): Station? =
    stations
        .minByOrNull { distanceM(p, it.point) }
        ?.takeIf { distanceM(p, it.point) <= CheckInPolicy.STATION_RADIUS_M }

private fun validate(readings: List<Reading>): RejectReason? =
    when {
        readings.size < CheckInPolicy.MIN_READINGS -> RejectReason.NotEnoughReadings
        readings.any { it.accuracyM > CheckInPolicy.MAX_ACCURACY_M } -> RejectReason.LowAccuracy
        readings.last().timeMs - readings.first().timeMs > CheckInPolicy.WINDOW_MS -> RejectReason.Stale
        else -> null
    }

/** "Estou na estacao": todas as leituras no raio da mesma estacao. */
fun evaluateStationCheckIn(
    readings: List<Reading>,
    stations: List<Station>,
): CheckInResult {
    validate(readings)?.let { return CheckInResult.Rejected(it) }
    val ids = readings.map { nearest(it.point, stations)?.id }
    val id = ids.first()
    return if (id != null && ids.all { it == id }) {
        CheckInResult.Accepted(id)
    } else {
        CheckInResult.Rejected(RejectReason.OutsideStation)
    }
}

/** "Estou no trem": perto do tracado e em movimento (ou saindo da estacao). */
fun evaluateTrainCheckIn(
    readings: List<Reading>,
    path: List<GeoPoint>,
    stations: List<Station>,
): CheckInResult {
    val invalid = validate(readings)
    val offCorridor = readings.any { distanceToPath(it.point, path) > CheckInPolicy.CORRIDOR_MAX_M }
    val moving = readings.lastOrNull()?.speedKmh?.let { it > CheckInPolicy.MIN_SPEED_KMH } == true
    val atStart = readings.firstOrNull()?.let { nearest(it.point, stations) } != null
    val atEnd = readings.lastOrNull()?.let { nearest(it.point, stations) } != null
    return when {
        invalid != null -> CheckInResult.Rejected(invalid)
        offCorridor -> CheckInResult.Rejected(RejectReason.OutsideCorridor)
        moving || (atStart && !atEnd) -> CheckInResult.Accepted()
        atEnd -> CheckInResult.Pending
        else -> CheckInResult.Rejected(RejectReason.TooSlow)
    }
}
