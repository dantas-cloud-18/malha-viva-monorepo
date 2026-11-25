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
 * Parte pura do servico: guarda as leituras da viagem so em memoria e aplica [sanitizeTrip] (corredor e corte de
 * inicio e fim) antes de montar os lotes. [drain] roda durante a viagem (a cada LIVE_UPLOAD_INTERVAL_MS) e trata o
 * instante atual como se fosse o fim: so sai o que ja esta a 300 m ou mais da posicao mais nova, entao os ultimos 300 m
 * ficam retidos ate [finish], como antes. Cada leitura sai uma vez so (por tempo) e o seq continua crescendo.
 */
class TripPipeline(
    private val route: Route,
    private val startMs: Long,
) {
    private val readings = mutableListOf<Reading>()
    private val stationary = StationaryDetector()
    private val builder = BatchBuilder()
    private var lastSentMs = Long.MIN_VALUE
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

    /** Lotes das leituras que ja podem sair (fora do corte do inicio e a 300 m ou mais da mais nova). */
    fun drain(): List<SampleBatch> = batchesOf(sanitizeTrip(readings, route))

    /** Saneia a viagem inteira, monta os lotes do que falta e descarta as leituras brutas. */
    fun finish(): List<SampleBatch> = batchesOf(sanitizeTrip(readings, route)).also { readings.clear() }

    private fun batchesOf(sendable: List<Reading>): List<SampleBatch> {
        val fresh = sendable.filter { it.timeMs > lastSentMs }
        fresh.lastOrNull()?.let { lastSentMs = it.timeMs }
        val batches = mutableListOf<SampleBatch>()
        fresh.forEach { builder.add(it)?.let(batches::add) }
        builder.flush()?.let(batches::add)
        return batches
    }
}
