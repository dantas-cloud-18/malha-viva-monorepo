package br.malhaviva.tracking.ingest

import kotlin.math.cos
import kotlin.math.hypot

data class IngestSample(
    val seq: Long,
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val speedMps: Double? = null,
    val mockLocation: Boolean = false,
    val bearingDeg: Double? = null,
)

data class ValidationResult(
    val accepted: List<IngestSample>,
    val rejected: Map<String, Int>,
)

object SamplePolicy {
    const val MAX_ACCURACY_M = 50.0
    const val MAX_SPEED_MPS = 40.0
    const val MAX_AGE_MS = 10 * 60 * 1000L
    const val MAX_FUTURE_MS = 60_000L
}

private const val EARTH_RADIUS_M = 6_371_000.0
private const val MS_PER_SECOND = 1000.0

/** Distancia equirretangular em metros; suficiente para trechos curtos. */
private fun distanceM(
    a: IngestSample,
    b: IngestSample,
): Double {
    val meanLat = Math.toRadians((a.lat + b.lat) / 2)
    val dx = Math.toRadians(b.lon - a.lon) * cos(meanLat)
    val dy = Math.toRadians(b.lat - a.lat)
    return hypot(dx, dy) * EARTH_RADIUS_M
}

/** dt zero ou negativo com deslocamento conta como implausivel. */
private fun impliedSpeedTooHigh(
    prev: IngestSample,
    cur: IngestSample,
): Boolean {
    val dtS = (cur.tsMs - prev.tsMs) / MS_PER_SECOND
    val dist = distanceM(prev, cur)
    return if (dtS <= 0.0) dist > 0.0 || dtS < 0.0 else dist / dtS > SamplePolicy.MAX_SPEED_MPS
}

private fun rejectionReason(
    s: IngestSample,
    nowMs: Long,
    maxSeq: Long?,
    prev: IngestSample?,
    offCorridor: (IngestSample) -> Boolean,
): String? =
    when {
        s.mockLocation -> "MOCK"
        maxSeq != null && s.seq <= maxSeq -> "DUPLICATE"
        s.accuracyM > SamplePolicy.MAX_ACCURACY_M -> "ACCURACY"
        s.tsMs < nowMs - SamplePolicy.MAX_AGE_MS || s.tsMs > nowMs + SamplePolicy.MAX_FUTURE_MS -> "STALE"
        (s.speedMps ?: 0.0) > SamplePolicy.MAX_SPEED_MPS -> "IMPLAUSIBLE_SPEED"
        offCorridor(s) -> "OFF_CORRIDOR"
        prev != null && impliedSpeedTooHigh(prev, s) -> "IMPLAUSIBLE_SPEED"
        else -> null
    }

fun validateSamples(
    samples: List<IngestSample>,
    nowMs: Long,
    lastSeq: Long?,
    offCorridor: (IngestSample) -> Boolean,
): ValidationResult {
    val accepted = mutableListOf<IngestSample>()
    val rejected = linkedMapOf<String, Int>()
    var maxSeq = lastSeq
    for (s in samples) {
        val reason = rejectionReason(s, nowMs, maxSeq, accepted.lastOrNull(), offCorridor)
        if (reason == null) {
            accepted += s
            maxSeq = s.seq
        } else {
            rejected.merge(reason, 1, Int::plus)
        }
    }
    return ValidationResult(accepted, rejected)
}
