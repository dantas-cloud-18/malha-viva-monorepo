package br.malhaviva.feature.trains

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainsProjectionTest {
    // Central 0 m, Madureira 10 km, Bangu 20 km, Santa Cruz 30 km (along cresce Central -> Santa Cruz)
    private val line =
        LineNetwork(
            listOf(
                LineStation("C", "Central", 0.0),
                LineStation("M", "Madureira", 10_000.0),
                LineStation("B", "Bangu", 20_000.0),
                LineStation("S", "Santa Cruz", 30_000.0),
            ),
            lengthM = 30_000.0,
        )

    private fun train(
        along: Double,
        speed: Double = 10.0,
        confidence: ConfidenceBand = ConfidenceBand.High,
    ) = TrackedTrain(LiveTrain("t", along, speed, confidence, 1L), receivedAtMs = 1_000L)

    private fun view(
        tracked: TrackedTrain,
        direction: Direction,
        ageMs: Long,
    ) = projectTrain(tracked, line, direction, tracked.receivedAtMs + ageMs)

    @Test
    fun `fresh train is where it was reported plus what it moved since`() {
        val v = view(train(12_000.0), Direction.CentralToSantaCruz, 5_000)
        assertEquals(12_050.0, v.alongM, 1e-6)
        assertEquals(Freshness.Live, v.freshness)
        assertEquals(0.9, v.confidence, 1e-9)
    }

    @Test
    fun `descending direction extrapolates toward lower along`() {
        val v = view(train(12_000.0), Direction.SantaCruzToCentral, 5_000)
        assertEquals(11_950.0, v.alongM, 1e-6)
    }

    @Test
    fun `extrapolation stops at the next station and marks the train there`() {
        // 9 950 m, 10 m/s, 20 s -> 10 150 m passaria Madureira (10 000 m): para la
        val v = view(train(9_950.0), Direction.CentralToSantaCruz, 20_000)
        assertEquals(10_000.0, v.alongM, 1e-6)
        assertEquals("M", v.atStationId)
        assertEquals(Freshness.Estimated, v.freshness)
    }

    @Test
    fun `stops at the next station in the descending direction too`() {
        val v = view(train(10_040.0), Direction.SantaCruzToCentral, 30_000)
        assertEquals(10_000.0, v.alongM, 1e-6)
        assertEquals("M", v.atStationId)
    }

    @Test
    fun `a train already at a station and moving leaves toward the following one, not stuck`() {
        val v = view(train(10_000.0), Direction.CentralToSantaCruz, 10_000)
        assertEquals(10_100.0, v.alongM, 1e-6)
        assertNull(v.atStationId)
    }

    @Test
    fun `clamped to the ends of the line when there is no station ahead`() {
        val v = view(train(29_990.0), Direction.CentralToSantaCruz, 12_000)
        assertEquals(30_000.0, v.alongM, 1e-6)
        val back = view(train(4.0, 10.0), Direction.SantaCruzToCentral, 12_000)
        assertEquals(0.0, back.alongM, 1e-6)
    }

    @Test
    fun `parked train near a station is at that station without moving`() {
        val v = view(train(20_030.0, speed = 0.0), Direction.CentralToSantaCruz, 40_000)
        assertEquals(20_030.0, v.alongM, 1e-6)
        assertEquals("B", v.atStationId)
    }

    @Test
    fun `confidence holds while fresh, decays while estimated and is zero without signal`() {
        val t = train(5_000.0)
        assertEquals(0.9, view(t, Direction.CentralToSantaCruz, 15_000).confidence, 1e-9)
        assertEquals(0.45, view(t, Direction.CentralToSantaCruz, 37_500).confidence, 1e-9)
        val gone = view(t, Direction.CentralToSantaCruz, 61_000)
        assertEquals(0.0, gone.confidence, 1e-9)
        assertEquals(Freshness.NoSignal, gone.freshness)
    }

    @Test
    fun `no signal freezes the position instead of drifting forever`() {
        val t = train(1_000.0)
        val at60 = view(t, Direction.CentralToSantaCruz, 60_000).alongM
        val at170 = view(t, Direction.CentralToSantaCruz, 170_000).alongM
        assertEquals(at60, at170, 1e-9)
    }

    @Test
    fun `clock going backwards never moves the train backwards`() {
        val t = train(1_000.0)
        assertEquals(1_000.0, projectTrain(t, line, Direction.CentralToSantaCruz, 0L).alongM, 1e-9)
    }

    @Test
    fun `display index interpolates between stations and follows the travel direction`() {
        assertEquals(1.5, displayIndex(line, Direction.CentralToSantaCruz, 15_000.0), 1e-9)
        assertEquals(1.5, displayIndex(line, Direction.SantaCruzToCentral, 15_000.0), 1e-9)
        assertEquals(0.0, displayIndex(line, Direction.CentralToSantaCruz, 0.0), 1e-9)
        assertEquals(3.0, displayIndex(line, Direction.SantaCruzToCentral, 0.0), 1e-9)
        assertEquals(3.0, displayIndex(line, Direction.CentralToSantaCruz, 99_999.0), 1e-9)
        assertEquals(2.0, displayIndex(line, Direction.SantaCruzToCentral, 10_000.0), 1e-9)
    }

    @Test
    fun `eta uses the nearest approaching moving train`() {
        val views =
            listOf(
                TrainView("a", 19_400.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null),
                TrainView("b", 5_000.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null),
            )
        val bangu = line.stations[2]
        // a esta a 600 m a 10 m/s = 60 s -> 1 min; b esta a 15 km
        assertEquals(1, etaMinutes(views, bangu, Direction.CentralToSantaCruz))
        // no sentido oposto os dois ja passaram de Bangu: ninguem se aproxima
        assertNull(etaMinutes(views, bangu, Direction.SantaCruzToCentral))
    }

    @Test
    fun `eta ignores trains moving away, stopped, at a station or without signal`() {
        val central = line.stations[0]
        val views =
            listOf(
                TrainView("away", 5_000.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null),
                TrainView("slow", 5_000.0, 0.2, ConfidenceBand.High, 1.0, Freshness.Live, null),
                TrainView("dwell", 5_000.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, "M"),
                TrainView("lost", 5_000.0, 10.0, ConfidenceBand.Low, 0.0, Freshness.NoSignal, null),
            )
        // Central -> Santa Cruz: todos estao alem da Central, afastando-se
        assertNull(etaMinutes(views, central, Direction.CentralToSantaCruz))
        // Santa Cruz -> Central: so "away" conta (5 km a 10 m/s = 500 s = 9 min); lento, parado e sem sinal nao
        assertEquals(9, etaMinutes(views, central, Direction.SantaCruzToCentral))
    }

    @Test
    fun `eta rounds up and gives up beyond 99 minutes`() {
        val near = TrainView("a", 19_990.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null)
        // 10 010 m a 10 m/s = 1 001 s = 16,7 min -> 17
        assertEquals(17, etaMinutes(listOf(near), line.stations[3], Direction.CentralToSantaCruz))
        val far = TrainView("f", 0.0, 1.0, ConfidenceBand.High, 1.0, Freshness.Live, null)
        // 30 km a 1 m/s = 500 min
        assertNull(etaMinutes(listOf(far), line.stations[3], Direction.CentralToSantaCruz))
        val soon = TrainView("s", 29_999.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null)
        assertEquals(1, etaMinutes(listOf(soon), line.stations[3], Direction.CentralToSantaCruz))
        val there = TrainView("h", 30_000.0, 10.0, ConfidenceBand.High, 1.0, Freshness.Live, null)
        assertEquals(0, etaMinutes(listOf(there), line.stations[3], Direction.CentralToSantaCruz))
    }
}
