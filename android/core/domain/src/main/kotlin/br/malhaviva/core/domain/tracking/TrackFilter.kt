package br.malhaviva.core.domain.tracking

import br.malhaviva.core.domain.checkin.Reading
import kotlin.math.abs

enum class TrackStatus { Live, AtStation, Extrapolated, NoSignal }

data class TrackState(
    val alongM: Double,
    val speedMs: Double,
    val status: TrackStatus,
    /** 0..1; cai quando a posicao e estimada. */
    val confidence: Double,
    val stationId: String? = null,
)

/** Limites PROPOSTOS; calibrar com viagens reais. */
object TrackPolicy {
    const val MAX_ACCURACY_M = 50f
    const val MAX_LATERAL_M = 60.0
    const val MAX_SPEED_MS = 40.0
    const val RESYNC_AFTER = 3
    const val JITTER_M = 30.0
    const val SMOOTHING = 0.5
    const val ARRIVAL_M = 80.0
    const val STOPPED_MS = 2.0
    const val FRESH_MS = 15_000L
    const val EXTRAPOLATE_MS = 60_000L
    const val KMH_TO_MS = 3.6
    const val MS_PER_S = 1000.0
}

/**
 * Corrige erros de rastreamento sobre o tracado: descarta imprecisos e fora do corredor,
 * rejeita saltos, suaviza, impede recuo por ruido, encaixa na estacao quando parado
 * perto dela e estima a posicao quando o sinal falha.
 */
class TrackFilter(
    private val route: Route,
) {
    private var last: TrackState? = null
    private var lastTimeMs = 0L
    private var outliers = 0

    fun update(r: Reading): TrackState? {
        val proj = route.project(r.point)
        val usable = r.accuracyM <= TrackPolicy.MAX_ACCURACY_M && proj.lateralM <= TrackPolicy.MAX_LATERAL_M
        val prev = last
        val along =
            when {
                !usable || r.timeMs <= lastTimeMs -> null
                prev == null -> proj.alongM
                else -> accept(prev, proj.alongM, r.timeMs, r.speedKmh / TrackPolicy.KMH_TO_MS)
            }
        if (along != null) {
            outliers = 0
            last = snapToStation(along, r.speedKmh / TrackPolicy.KMH_TO_MS)
            lastTimeMs = r.timeMs
        }
        return last
    }

    /** Estado em [nowMs]: ao vivo, estimado ou sem sinal. */
    fun at(nowMs: Long): TrackState? {
        val s = last ?: return null
        val gap = nowMs - lastTimeMs
        return when {
            gap <= TrackPolicy.FRESH_MS -> s
            gap > TrackPolicy.EXTRAPOLATE_MS -> s.copy(status = TrackStatus.NoSignal, confidence = 0.0)
            s.status == TrackStatus.AtStation -> s
            else -> extrapolate(s, gap)
        }
    }

    private fun accept(
        prev: TrackState,
        measured: Double,
        timeMs: Long,
        speedMs: Double,
    ): Double? {
        val dt = (timeMs - lastTimeMs) / TrackPolicy.MS_PER_S
        val jump = abs(measured - prev.alongM) / dt
        if (jump > TrackPolicy.MAX_SPEED_MS && ++outliers < TrackPolicy.RESYNC_AFTER) return null
        // Parado: o GPS confirma que nao anda, entao confia na medida. Em movimento: preve e corrige.
        val predicted = prev.alongM + prev.speedMs * dt
        val alpha = if (speedMs < TrackPolicy.STOPPED_MS) 1.0 else TrackPolicy.SMOOTHING
        val filtered = predicted + alpha * (measured - predicted)
        val jitter = filtered < prev.alongM && prev.alongM - filtered < TrackPolicy.JITTER_M
        return if (jitter) prev.alongM else filtered
    }

    private fun snapToStation(
        along: Double,
        speed: Double,
    ): TrackState {
        val near = route.stationsAlong.minByOrNull { abs(it.second - along) }
        val arrived =
            near != null && abs(near.second - along) <= TrackPolicy.ARRIVAL_M && speed < TrackPolicy.STOPPED_MS
        return if (arrived) {
            TrackState(near!!.second, 0.0, TrackStatus.AtStation, 1.0, near.first.id)
        } else {
            TrackState(along, speed, TrackStatus.Live, 1.0)
        }
    }

    private fun extrapolate(
        s: TrackState,
        gapMs: Long,
    ): TrackState {
        val travelled = s.speedMs * gapMs / TrackPolicy.MS_PER_S
        var along = (s.alongM + travelled).coerceAtMost(route.lengthM)
        // O trem para na proxima estacao: nao estima alem dela.
        route.stationsAlong.firstOrNull { it.second > s.alongM }?.let { along = minOf(along, it.second) }
        val conf = 1.0 - (gapMs - TrackPolicy.FRESH_MS).toDouble() / (TrackPolicy.EXTRAPOLATE_MS - TrackPolicy.FRESH_MS)
        return s.copy(alongM = along, status = TrackStatus.Extrapolated, confidence = conf.coerceIn(0.0, 1.0))
    }
}
