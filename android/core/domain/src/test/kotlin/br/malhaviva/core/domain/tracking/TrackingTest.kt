package br.malhaviva.core.domain.tracking

import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.checkin.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTest {
    private val lonPerM = 1 / 102_500.0
    private val stations =
        listOf(
            Station("a", GeoPoint(LAT, -43.20)),
            Station("b", GeoPoint(LAT, -43.15)),
            Station("c", GeoPoint(LAT, -43.10)),
        )
    private val route = Route(listOf(GeoPoint(LAT, -43.20), GeoPoint(LAT, -43.10)), stations)
    private val stationB = route.stationsAlong[1].second

    private fun rd(
        alongM: Double,
        t: Long,
        kmh: Float = 36f,
        acc: Float = 10f,
        lat: Double = LAT,
    ) = Reading(GeoPoint(lat, -43.20 + alongM * lonPerM), acc, kmh, t)

    @Test
    fun routeProjectsStationsInOrder() {
        assertEquals(listOf("a", "b", "c"), route.stationsAlong.map { it.first.id })
        assertEquals(5_127.0, stationB, 60.0)
    }

    @Test
    fun trainStoppedNearStationSnapsToIt() {
        val f = TrackFilter(route)
        f.update(rd(4_000.0, 1_000))
        f.update(rd(4_500.0, 21_000))
        f.update(rd(5_090.0, 41_000, kmh = 0f))
        f.update(rd(5_090.0, 51_000, kmh = 0f))
        val s = f.update(rd(5_090.0, 61_000, kmh = 0f))
        assertEquals(TrackStatus.AtStation, s?.status)
        assertEquals("b", s?.stationId)
        assertEquals(stationB, s!!.alongM, 0.001)
    }

    @Test
    fun lowAccuracyAndOffCorridorAreIgnored() {
        val f = TrackFilter(route)
        val first = f.update(rd(1_000.0, 1_000))
        assertEquals(first, f.update(rd(1_200.0, 6_000, acc = 120f)))
        assertEquals(first, f.update(rd(1_200.0, 7_000, lat = LAT - 0.01)))
    }

    @Test
    fun singleJumpIsRejectedButRepeatedJumpResyncs() {
        val f = TrackFilter(route)
        val first = f.update(rd(1_000.0, 1_000))
        assertEquals(first, f.update(rd(6_000.0, 2_000)))
        assertEquals(first, f.update(rd(6_010.0, 3_000)))
        val s = f.update(rd(6_020.0, 4_000))
        assertTrue(s!!.alongM > 3_000.0)
    }

    @Test
    fun jitterNeverMovesTrainBackwards() {
        val f = TrackFilter(route)
        f.update(rd(2_000.0, 1_000))
        val before = f.update(rd(2_100.0, 5_000))!!.alongM
        val after = f.update(rd(2_090.0, 9_000))!!.alongM
        assertTrue(after >= before)
    }

    @Test
    fun gapIsExtrapolatedThenLosesSignal() {
        val f = TrackFilter(route)
        f.update(rd(1_000.0, 1_000, kmh = 72f))
        val mid = f.at(31_000)!!
        assertEquals(TrackStatus.Extrapolated, mid.status)
        assertEquals(1_600.0, mid.alongM, 80.0)
        assertTrue(mid.confidence in 0.0..1.0 && mid.confidence < 1.0)
        assertEquals(TrackStatus.NoSignal, f.at(100_000)!!.status)
        assertEquals(TrackStatus.Live, f.at(5_000)!!.status)
    }

    @Test
    fun extrapolationStopsAtNextStation() {
        val f = TrackFilter(route)
        f.update(rd(4_500.0, 1_000, kmh = 108f))
        assertEquals(stationB, f.at(46_000)!!.alongM, 0.001)
    }

    @Test
    fun noStateBeforeFirstReading() {
        assertNull(TrackFilter(route).at(1_000))
    }

    @Test
    fun sanitizeDropsOffCorridorAndTrimsEnds() {
        val trip = (0..90).map { rd(it * 110.0, 1_000L + it * 5_000, lat = if (it == 45) LAT - 0.01 else LAT) }
        val out = sanitizeTrip(trip, route)
        assertEquals(84, out.size)
        assertTrue(out.none { it.point.lat != LAT })
        assertEquals(330.0, route.project(out.first().point).alongM, 10.0)
        assertEquals(9_570.0, route.project(out.last().point).alongM, 10.0)
    }

    @Test
    fun shortTripSendsNothing() {
        val trip = (0..4).map { rd(it * 100.0, 1_000L + it * 5_000) }
        assertTrue(sanitizeTrip(trip, route).isEmpty())
    }

    private companion object {
        const val LAT = -22.90
    }
}
