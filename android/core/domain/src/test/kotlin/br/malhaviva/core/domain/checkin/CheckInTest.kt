package br.malhaviva.core.domain.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInTest {
    private val path = listOf(GeoPoint(-22.90, -43.20), GeoPoint(-22.90, -43.10))
    private val stations = listOf(Station("a", GeoPoint(-22.90, -43.20)), Station("b", GeoPoint(-22.90, -43.10)))

    private fun r(
        lat: Double,
        lon: Double,
        speed: Float = 0f,
        acc: Float = 10f,
        t: Long = 0,
    ) = Reading(GeoPoint(lat, lon), acc, speed, t)

    @Test
    fun distanceOfOneDegreeLatIsAbout111km() {
        val d = distanceM(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertTrue(d in 111_000.0..111_500.0)
    }

    @Test
    fun stationAcceptedWhenAllReadingsInside() {
        val res = evaluateStationCheckIn(listOf(r(-22.9001, -43.2001), r(-22.9, -43.2, t = 10_000)), stations)
        assertEquals(CheckInResult.Accepted("a"), res)
    }

    @Test
    fun stationRejectedOnPoorAccuracy() {
        val res = evaluateStationCheckIn(listOf(r(-22.9, -43.2, acc = 80f), r(-22.9, -43.2)), stations)
        assertEquals(CheckInResult.Rejected(RejectReason.LowAccuracy), res)
    }

    @Test
    fun stationRejectedWithSingleReading() {
        val res = evaluateStationCheckIn(listOf(r(-22.9, -43.2)), stations)
        assertEquals(CheckInResult.Rejected(RejectReason.NotEnoughReadings), res)
    }

    @Test
    fun stationRejectedWhenReadingsSpanTooLong() {
        val res = evaluateStationCheckIn(listOf(r(-22.9, -43.2), r(-22.9, -43.2, t = 31_000)), stations)
        assertEquals(CheckInResult.Rejected(RejectReason.Stale), res)
    }

    @Test
    fun stationRejectedOutsideRadius() {
        val res = evaluateStationCheckIn(listOf(r(-22.95, -43.2), r(-22.95, -43.2, t = 5_000)), stations)
        assertEquals(CheckInResult.Rejected(RejectReason.OutsideStation), res)
    }

    @Test
    fun trainAcceptedWhenMovingOnPath() {
        val res = evaluateTrainCheckIn(listOf(r(-22.9, -43.15, 40f), r(-22.9, -43.149, 40f, t = 8_000)), path, stations)
        assertEquals(CheckInResult.Accepted(), res)
    }

    @Test
    fun trainPendingWhenStoppedAtStation() {
        val res = evaluateTrainCheckIn(listOf(r(-22.9, -43.2), r(-22.9, -43.2, t = 8_000)), path, stations)
        assertEquals(CheckInResult.Pending, res)
    }

    @Test
    fun trainRejectedOffCorridor() {
        val res =
            evaluateTrainCheckIn(listOf(r(-22.91, -43.15, 40f), r(-22.91, -43.15, 40f, t = 8_000)), path, stations)
        assertEquals(CheckInResult.Rejected(RejectReason.OutsideCorridor), res)
    }

    @Test
    fun trainRejectedWhenSlowBetweenStations() {
        val res = evaluateTrainCheckIn(listOf(r(-22.9, -43.15, 3f), r(-22.9, -43.15, 3f, t = 8_000)), path, stations)
        assertEquals(CheckInResult.Rejected(RejectReason.TooSlow), res)
    }

    @Test
    fun trainAcceptedOnStationToTrackTransition() {
        val res = evaluateTrainCheckIn(listOf(r(-22.9, -43.2), r(-22.9, -43.195, 5f, t = 8_000)), path, stations)
        assertEquals(CheckInResult.Accepted(), res)
    }
}
