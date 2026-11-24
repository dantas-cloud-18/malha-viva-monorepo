package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.sampling.AutoStopPolicy
import br.malhaviva.core.domain.sampling.BatchBuilder
import br.malhaviva.core.domain.sampling.SampleBatch
import br.malhaviva.core.domain.sampling.StationaryDetector
import br.malhaviva.core.domain.sampling.StopReason
import br.malhaviva.core.domain.sampling.nextIntervalMs
import br.malhaviva.core.domain.sampling.shouldStopTrip
import br.malhaviva.core.domain.tracking.Route
import br.malhaviva.core.domain.tracking.TrackPolicy
import br.malhaviva.core.domain.tracking.sanitizeTrip

data class PipelineStep(
    val paused: Boolean,
    val stop: StopReason?,
    val nextIntervalMs: Long,
)

/**
 * Parte pura do servico: guarda as leituras da viagem so em memoria e, ao final, aplica
 * [sanitizeTrip] (corredor e corte de inicio e fim) antes de montar os lotes.
 * shortcut: nada sai durante a viagem (o corte do fim so existe com a viagem completa); mover para
 * envio com atraso se a UX pedir posicao quase em tempo real.
 */
class TripPipeline(
    private val route: Route,
    private val startMs: Long,
) {
    private val readings = mutableListOf<Reading>()
    private val stationary = StationaryDetector()
    private var pausedSinceMs: Long? = null

    fun onReading(
        r: Reading,
        batteryLow: Boolean,
    ): PipelineStep {
        val usable = r.accuracyM <= TrackPolicy.MAX_ACCURACY_M
        val paused = if (usable) stationary.update(r) else stationary.shouldPause
        if (usable && !paused) readings += r
        // aproximacao: o tempo parado conta a partir do momento em que a pausa foi detectada
        pausedSinceMs = if (paused) pausedSinceMs ?: r.timeMs else null
        val stoppedMs = pausedSinceMs?.let { r.timeMs - it } ?: 0L
        val stop =
            shouldStopTrip(route, readings.takeLast(AutoStopPolicy.OFF_ROUTE_READINGS), stoppedMs, r.timeMs - startMs)
        val speedMs = r.speedKmh / TrackPolicy.KMH_TO_MS
        return PipelineStep(paused, stop, nextIntervalMs(speedMs, paused, batteryLow))
    }

    /** Saneia a viagem, monta os lotes e descarta as leituras brutas. */
    fun finish(): List<SampleBatch> {
        val builder = BatchBuilder()
        val batches = mutableListOf<SampleBatch>()
        sanitizeTrip(readings, route).forEach { builder.add(it)?.let(batches::add) }
        builder.flush()?.let(batches::add)
        readings.clear()
        return batches
    }
}
