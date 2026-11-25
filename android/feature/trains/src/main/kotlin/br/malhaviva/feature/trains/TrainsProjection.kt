package br.malhaviva.feature.trains

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Posicao do trem em [nowMs]: ultima posicao informada mais velocidade vezes idade, sem passar da proxima
 * estacao no sentido da viagem (o trem para la) nem dos extremos da linha. Depois de [TrainsPolicy.NO_SIGNAL_MS]
 * a posicao congela e a confianca zera; antes disso a confianca decai a partir de [TrainsPolicy.FRESH_MS].
 */
fun projectTrain(
    tracked: TrackedTrain,
    line: LineNetwork,
    direction: Direction,
    nowMs: Long,
): TrainView {
    val train = tracked.train
    val age = (nowMs - tracked.receivedAtMs).coerceAtLeast(0L)
    val freshness =
        when {
            age <= TrainsPolicy.FRESH_MS -> Freshness.Live
            age <= TrainsPolicy.NO_SIGNAL_MS -> Freshness.Estimated
            else -> Freshness.NoSignal
        }
    val travelledM = train.speedMps * min(age, TrainsPolicy.NO_SIGNAL_MS) / TrainsPolicy.MS_PER_S
    val raw = train.alongM + direction.sign * travelledM
    val limit = nextStationAlong(line, train.alongM, direction)
    val held = limit != null && direction.sign * (raw - limit) >= 0
    val along = (if (held && limit != null) limit else raw).coerceIn(0.0, line.lengthM)
    val stopped = held || train.speedMps < TrainsPolicy.STOPPED_MPS
    val near = line.stations.minByOrNull { abs(it.alongM - along) }
    val atStation = near?.takeIf { stopped && abs(it.alongM - along) <= TrainsPolicy.ARRIVAL_M }
    return TrainView(
        id = train.id,
        alongM = along,
        speedMps = train.speedMps,
        signals = train.signals,
        confidence = confidenceAt(train.confidence, age),
        freshness = freshness,
        atStationId = atStation?.id,
    )
}

private fun confidenceAt(
    reported: Double,
    ageMs: Long,
): Double {
    val decay = (ageMs - TrainsPolicy.FRESH_MS).toDouble() / (TrainsPolicy.NO_SIGNAL_MS - TrainsPolicy.FRESH_MS)
    return reported.coerceIn(0.0, 1.0) * (1.0 - decay.coerceIn(0.0, 1.0))
}

/** Primeira estacao estritamente a frente de [alongM] no sentido da viagem; null se nao ha mais nenhuma. */
fun nextStationAlong(
    line: LineNetwork,
    alongM: Double,
    direction: Direction,
): Double? =
    if (direction.sign > 0) {
        line.stations.firstOrNull { it.alongM > alongM + TrainsPolicy.STATION_EPS_M }?.alongM
    } else {
        line.stations.lastOrNull { it.alongM < alongM - TrainsPolicy.STATION_EPS_M }?.alongM
    }

/**
 * Indice fracionario do trem na lista exibida (0 = primeira linha). Entre duas estacoes interpola pela distancia;
 * fora dos extremos prende na primeira ou na ultima. A lista exibida segue o sentido da viagem.
 */
fun displayIndex(
    line: LineNetwork,
    direction: Direction,
    alongM: Double,
): Double {
    val s = line.stations
    if (s.size < 2) return 0.0
    val upper = s.indexOfFirst { it.alongM > alongM }
    val ascending =
        when (upper) {
            0 -> 0.0
            -1 -> s.lastIndex.toDouble()
            else -> {
                val a = s[upper - 1].alongM
                val span = s[upper].alongM - a
                (upper - 1) + if (span <= 0.0) 0.0 else (alongM - a) / span
            }
        }
    return if (direction.sign > 0) ascending else s.lastIndex - ascending
}

/**
 * Minutos (arredondados para cima) ate a [station] pelo trem em movimento que chega antes, supondo marcha
 * constante e sem paradas intermediarias: e uma estimativa. 0 = chegando; null = nenhum trem se aproxima.
 */
fun etaMinutes(
    views: List<TrainView>,
    station: LineStation,
    direction: Direction,
): Int? {
    val seconds =
        views
            .filter {
                it.freshness != Freshness.NoSignal && it.atStationId == null && it.speedMps >= TrainsPolicy.MOVING_MPS
            }.map { direction.sign * (station.alongM - it.alongM) to it.speedMps }
            .filter { (distance, _) -> distance >= 0 }
            .minOfOrNull { (distance, speed) -> distance / speed }
            ?: return null
    val minutes = ceil(seconds / TrainsPolicy.S_PER_MIN).toInt()
    return if (minutes > TrainsPolicy.MAX_ETA_MIN) null else minutes
}
