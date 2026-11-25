package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.sampling.MAX_BATCH_SIZE
import br.malhaviva.core.domain.sampling.SamplingPolicy
import br.malhaviva.core.domain.sampling.StopReason
import br.malhaviva.core.domain.tracking.PrivacyPolicy
import br.malhaviva.core.domain.tracking.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPipelineTest {
    // trajeto reto de ~3,3 km para o norte; 0,001 grau de latitude ~ 111 m
    private val route = Route((0..STEPS).map { GeoPoint(LAT0 + it * DEG, LON0) }, emptyList())

    private fun reading(
        step: Int,
        timeMs: Long = step * TEN_S,
        lateralDeg: Double = 0.0,
        accuracyM: Float = 10f,
        speedKmh: Float = 40f,
    ) = Reading(GeoPoint(LAT0 + step * DEG, LON0 + lateralDeg), accuracyM, speedKmh, timeMs)

    @Test
    fun `finish trims start and end and numbers samples from zero`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        (0..STEPS).forEach { pipeline.onReading(reading(it), batteryLow = false) }
        val samples = pipeline.finish().flatMap { it.samples }
        assertTrue(samples.isNotEmpty())
        assertEquals((0 until samples.size).toList(), samples.map { it.seq })
        val firstLat = samples.minOf { it.lat }
        val lastLat = samples.maxOf { it.lat }
        assertTrue((firstLat - LAT0) * METERS_PER_DEG >= PrivacyPolicy.TRIM_M - 1)
        assertTrue((LAT0 + STEPS * DEG - lastLat) * METERS_PER_DEG >= PrivacyPolicy.TRIM_M - 1)
        assertTrue(pipeline.finish().isEmpty())
    }

    @Test
    fun `drain sends only what is 300 m behind the newest reading, once, and finish sends the rest`() {
        val live = TripPipeline(route, startMs = 0L)
        val sent = mutableListOf<br.malhaviva.core.domain.sampling.Sample>()
        (0..STEPS).forEach {
            live.onReading(reading(it), batteryLow = false)
            if (it % 3 == 0) {
                val drained = live.drain().flatMap { b -> b.samples }
                // nada a menos de 300 m da leitura mais nova (a viagem ainda pode continuar)
                assertTrue(drained.all { s -> (LAT0 + it * DEG - s.lat) * METERS_PER_DEG >= PrivacyPolicy.TRIM_M - 1 })
                sent += drained
            }
        }
        assertTrue("drain should have sent something before the trip ended", sent.isNotEmpty())
        sent += live.finish().flatMap { it.samples }
        // mesmo conjunto de um envio unico no fim, sem repeticao e com seq continuo
        val once = TripPipeline(route, startMs = 0L)
        (0..STEPS).forEach { once.onReading(reading(it), batteryLow = false) }
        val expected = once.finish().flatMap { it.samples }
        assertEquals(expected.map { it.timeMs }, sent.map { it.timeMs })
        assertEquals((0 until sent.size).toList(), sent.map { it.seq })
    }

    @Test
    fun `drain of a short trip and a repeated drain send nothing`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        (0..2).forEach { pipeline.onReading(reading(it), batteryLow = false) }
        assertTrue(pipeline.drain().isEmpty())
        (3..STEPS).forEach { pipeline.onReading(reading(it), batteryLow = false) }
        assertTrue(pipeline.drain().isNotEmpty())
        assertTrue(pipeline.drain().isEmpty())
    }

    @Test
    fun `batches respect the contract limit`() {
        val long = Route((0..BIG).map { GeoPoint(LAT0 + it * DEG, LON0) }, emptyList())
        val pipeline = TripPipeline(long, startMs = 0L)
        (0..BIG).forEach { pipeline.onReading(reading(it), batteryLow = false) }
        val batches = pipeline.finish()
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.samples.size <= MAX_BATCH_SIZE })
    }

    @Test
    fun `short trip sends nothing`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        (0..2).forEach { pipeline.onReading(reading(it), batteryLow = false) }
        assertTrue(pipeline.finish().isEmpty())
    }

    @Test
    fun `inaccurate readings are dropped`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        (0..STEPS).forEach { pipeline.onReading(reading(it, accuracyM = 200f), batteryLow = false) }
        assertTrue(pipeline.finish().isEmpty())
    }

    @Test
    fun `interval follows speed and battery`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        assertEquals(
            SamplingPolicy.FAST_INTERVAL_MS,
            pipeline.onReading(reading(0, speedKmh = 60f), batteryLow = false).nextIntervalMs,
        )
        assertEquals(
            SamplingPolicy.IDLE_INTERVAL_MS,
            pipeline.onReading(reading(1, speedKmh = 60f), batteryLow = true).nextIntervalMs,
        )
    }

    @Test
    fun `staying put pauses and goes idle`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        val steps =
            (0..STILL_READINGS).map {
                pipeline.onReading(reading(5, timeMs = it * TEN_S, speedKmh = 0f), batteryLow = false)
            }
        assertTrue(steps.last().paused)
        assertEquals(SamplingPolicy.IDLE_INTERVAL_MS, steps.last().nextIntervalMs)
        assertTrue(pipeline.finish().size <= 1)
    }

    @Test
    fun `leaving the corridor requests stop`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        val steps = (0..2).map { pipeline.onReading(reading(it, lateralDeg = OFF_DEG), batteryLow = false) }
        assertNull(steps[1].stop)
        assertEquals(StopReason.LeftCorridor, steps[2].stop)
    }

    @Test
    fun `max duration requests stop`() {
        val pipeline = TripPipeline(route, startMs = 0L)
        val step = pipeline.onReading(reading(0, timeMs = THREE_HOURS_MS), batteryLow = false)
        assertEquals(StopReason.MaxDuration, step.stop)
    }

    private companion object {
        const val LAT0 = -23.5
        const val LON0 = -46.6
        const val DEG = 0.001
        const val METERS_PER_DEG = 111_320.0
        const val STEPS = 30
        const val BIG = 400
        const val TEN_S = 10_000L
        const val STILL_READINGS = 15
        const val OFF_DEG = 0.002
        const val THREE_HOURS_MS = 10_800_000L
    }
}
