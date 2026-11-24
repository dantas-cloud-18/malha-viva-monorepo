package br.malhaviva.core.domain.sampling

import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.checkin.Station
import br.malhaviva.core.domain.tracking.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    private val route =
        Route(
            listOf(GeoPoint(LAT, -43.20), GeoPoint(LAT, -43.10)),
            listOf(Station("a", GeoPoint(LAT, -43.20)), Station("c", GeoPoint(LAT, -43.10))),
        )

    private fun at(
        alongM: Double,
        t: Long = 0L,
        latOffset: Double = 0.0,
    ) = Reading(GeoPoint(LAT + latOffset, -43.20 + alongM / M_PER_LON), 5f, 36f, t)

    private fun offRoute(t: Long) = at(5_000.0, t, latOffset = OFF_LAT)

    @Test
    fun intervalDependsOnSpeedStateAndBattery() {
        assertEquals(5_000L, nextIntervalMs(10.1, stationary = false, batteryLow = false))
        assertEquals(10_000L, nextIntervalMs(10.0, stationary = false, batteryLow = false))
        assertEquals(10_000L, nextIntervalMs(0.0, stationary = false, batteryLow = false))
        assertEquals(15_000L, nextIntervalMs(20.0, stationary = true, batteryLow = false))
        assertEquals(15_000L, nextIntervalMs(20.0, stationary = false, batteryLow = true))
    }

    @Test
    fun pausesAfterTwoMinutesWithin25m() {
        val d = StationaryDetector()
        assertFalse(d.update(at(1_000.0, 0)))
        assertFalse(d.update(at(1_020.0, 119_999)))
        assertTrue(d.update(at(1_020.0, 120_000)))
    }

    @Test
    fun movingBeyond25mRestartsTheClock() {
        val d = StationaryDetector()
        d.update(at(1_000.0, 0))
        assertFalse(d.update(at(1_030.0, 100_000)))
        assertFalse(d.update(at(1_030.0, 200_000)))
        assertTrue(d.update(at(1_030.0, 220_000)))
    }

    @Test
    fun resumesOnlyBeyond50mFromAnchor() {
        val d = StationaryDetector()
        d.update(at(1_000.0, 0))
        assertTrue(d.update(at(1_000.0, 120_000)))
        assertTrue(d.update(at(1_040.0, 130_000)))
        assertFalse(d.update(at(1_060.0, 140_000)))
        assertFalse(d.shouldPause)
    }

    @Test
    fun stopsAfterMaxDuration() {
        val r = listOf(at(1_000.0))
        assertNull(shouldStopTrip(route, r, 0, 10_799_999))
        assertEquals(StopReason.MaxDuration, shouldStopTrip(route, r, 0, 10_800_000))
    }

    @Test
    fun leftCorridorNeedsThreeConsecutiveOffRouteReadings() {
        val two = listOf(at(1_000.0, 0), offRoute(1), offRoute(2))
        assertNull(shouldStopTrip(route, two, 0, 0))
        val three = listOf(offRoute(1), offRoute(2), offRoute(3))
        assertEquals(StopReason.LeftCorridor, shouldStopTrip(route, three, 0, 0))
        assertNull(shouldStopTrip(route, three.take(2), 0, 0))
    }

    @Test
    fun stoppedOutsideStationAfterTenMinutes() {
        val mid = listOf(at(5_000.0))
        assertNull(shouldStopTrip(route, mid, 600_000, 0))
        assertEquals(StopReason.StoppedOutsideStation, shouldStopTrip(route, mid, 600_001, 0))
    }

    @Test
    fun stoppedInsideStationRadiusDoesNotStop() {
        assertNull(shouldStopTrip(route, listOf(at(100.0)), 3_600_000, 0))
        assertNull(shouldStopTrip(route, emptyList(), 3_600_000, 0))
    }

    @Test
    fun batchesAtMostMaxSizeWithIncreasingSeq() {
        val b = BatchBuilder()
        val batches = (0 until 250).mapNotNull { b.add(at(it.toDouble(), it * 5_000L)) }
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.samples.size == MAX_BATCH_SIZE })
        val tail = b.flush()
        assertNotNull(tail)
        assertEquals(10, tail!!.samples.size)
        val seqs = (batches + tail).flatMap { it.samples }.map { it.seq }
        assertEquals((0 until 250).toList(), seqs)
        assertNull(b.flush())
    }

    @Test
    fun emptyBuilderEmitsNothingAndFlushClearsPending() {
        val b = BatchBuilder()
        assertNull(b.flush())
        assertNull(b.add(at(0.0)))
        assertEquals(1, b.flush()!!.samples.size)
        assertNull(b.flush())
    }

    @Test
    fun sampleConvertsSpeedAndKeepsFields() {
        val b = BatchBuilder()
        b.add(Reading(GeoPoint(1.0, 2.0), 7f, 36f, 99L))
        val s = b.flush()!!.samples.single()
        assertEquals(Sample(0, 99L, 1.0, 2.0, 7f, 10.0), s)
    }

    @Test
    fun batchTypesHaveNoIdentifierFields() {
        val banned = listOf("user", "device", "account")
        listOf(Sample::class.java, SampleBatch::class.java).flatMap { it.declaredFields.toList() }.forEach { f ->
            val n = f.name.lowercase()
            assertTrue("campo proibido: $n", n != "id" && banned.none { n.contains(it) })
        }
    }

    private companion object {
        const val LAT = -22.90
        const val M_PER_LON = 102_500.0
        const val OFF_LAT = 0.002
    }
}
