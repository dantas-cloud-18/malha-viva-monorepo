package br.malhaviva.feature.trains

import br.malhaviva.core.designsystem.TrainStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainsReducerTest {
    private val line =
        LineNetwork(listOf(LineStation("C", "Central", 0.0), LineStation("S", "Santa Cruz", 10_000.0)), 10_000.0)

    private fun live(
        id: String,
        updatedAt: Long = 1L,
        along: Double = 100.0,
    ) = LiveTrain(id, along, 10.0, ConfidenceBand.High, updatedAt)

    private fun run(
        vararg intents: TrainsIntent,
        from: TrainsState = TrainsState(),
    ) = intents.fold(from) { s, i -> reduceTrains(s, i) }

    @Test
    fun `train events upsert by id and stamp the arrival time`() {
        val s =
            run(
                TrainsIntent.TrainReceived(live("a", 1, 100.0), 1_000),
                TrainsIntent.TrainReceived(live("a", 2, 200.0), 2_000),
            )
        assertEquals(1, s.trains.size)
        assertEquals(
            200.0,
            s.trains
                .getValue("a")
                .train.alongM,
            0.0,
        )
        assertEquals(2_000L, s.trains.getValue("a").receivedAtMs)
        assertEquals(2_000L, s.lastUpdateMs)
    }

    @Test
    fun `late event never overwrites a newer one`() {
        val s = run(TrainsIntent.TrainReceived(live("a", 5, 500.0), 1_000))
        val after = reduceTrains(s, TrainsIntent.TrainReceived(live("a", 4, 400.0), 2_000))
        assertSame(s, after)
    }

    @Test
    fun `snapshot replaces the whole list`() {
        val s =
            run(
                TrainsIntent.TrainReceived(live("old"), 1_000),
                TrainsIntent.SnapshotReceived(listOf(live("n1"), live("n2")), 2_000),
            )
        assertEquals(setOf("n1", "n2"), s.trains.keys)
        assertEquals(
            run(TrainsIntent.SnapshotReceived(emptyList(), 3_000), from = s).trains,
            emptyMap<String, TrackedTrain>(),
        )
    }

    @Test
    fun `connection lifecycle drives the link and the attempt counter`() {
        var s = run(TrainsIntent.Connecting)
        assertEquals(Link.Connecting, s.link)
        s = reduceTrains(s, TrainsIntent.StreamOpened)
        assertEquals(Link.Live, s.link)
        s = run(TrainsIntent.StreamLost(1), from = s)
        assertEquals(Link.Reconnecting, s.link)
        assertEquals(1, s.reconnectAttempt)
        // a proxima tentativa continua sinalizada como reconexao
        assertEquals(Link.Reconnecting, reduceTrains(s, TrainsIntent.Connecting).link)
        // dado novo prova que voltou: zera tentativas
        assertEquals(0, reduceTrains(s, TrainsIntent.TrainReceived(live("a"), 5_000)).reconnectAttempt)
    }

    @Test
    fun `tick advances the clock and drops trains older than the retention window`() {
        val s = run(TrainsIntent.TrainReceived(live("a"), 1_000), TrainsIntent.TrainReceived(live("b"), 100_000))
        val t = reduceTrains(s, TrainsIntent.Tick(1_000 + TrainsPolicy.DROP_MS))
        assertEquals(setOf("a", "b"), t.trains.keys)
        val later = reduceTrains(s, TrainsIntent.Tick(1_001 + TrainsPolicy.DROP_MS))
        assertEquals(setOf("b"), later.trains.keys)
        assertEquals(1_001 + TrainsPolicy.DROP_MS, later.nowMs)
    }

    @Test
    fun `switching direction clears trains, keeps the network and reconnects`() {
        val s =
            run(
                TrainsIntent.NetworkLoaded(line),
                TrainsIntent.StreamOpened,
                TrainsIntent.TrainReceived(live("a"), 1_000),
                TrainsIntent.DirectionSelected(Direction.SantaCruzToCentral),
            )
        assertTrue(s.trains.isEmpty())
        assertEquals(line, s.line)
        assertEquals(Direction.SantaCruzToCentral, s.direction)
        assertEquals(Link.Connecting, s.link)
        // mesmo sentido: nada muda
        val same = reduceTrains(s, TrainsIntent.DirectionSelected(Direction.SantaCruzToCentral))
        assertSame(s, same)
    }

    @Test
    fun `pausing goes idle and a direction change while paused stays idle`() {
        val paused = run(TrainsIntent.StreamOpened, TrainsIntent.Paused)
        assertEquals(Link.Idle, paused.link)
        assertEquals(Link.Idle, reduceTrains(paused, TrainsIntent.DirectionSelected(Direction.SantaCruzToCentral)).link)
    }

    @Test
    fun `network failure is flagged and cleared by a later success`() {
        val failed = run(TrainsIntent.NetworkFailed)
        assertTrue(failed.networkError)
        val ok = reduceTrains(failed, TrainsIntent.NetworkLoaded(line))
        assertEquals(false, ok.networkError)
    }

    @Test
    fun `health reflects freshest train, then estimation, then no signal`() {
        fun health(state: TrainsState) = lineHealth(state, state.views())

        val base = run(TrainsIntent.NetworkLoaded(line), TrainsIntent.StreamOpened, TrainsIntent.Tick(1_000))
        assertEquals(LineHealth.Live, health(base))
        val withTrain = reduceTrains(base, TrainsIntent.TrainReceived(live("a"), 1_000))
        assertEquals(TrainStatus.OnTime, health(withTrain).status)
        val estimated = reduceTrains(withTrain, TrainsIntent.Tick(1_000 + TrainsPolicy.FRESH_MS + 1))
        assertEquals(LineHealth.Estimated, health(estimated))
        val silent = reduceTrains(withTrain, TrainsIntent.Tick(1_000 + TrainsPolicy.NO_SIGNAL_MS + 1))
        assertEquals(LineHealth.NoSignal, health(silent))
        assertEquals(LineHealth.NoSignal, health(run(TrainsIntent.StreamLost(1), from = base)))
        assertEquals(LineHealth.Connecting, health(run(TrainsIntent.Connecting)))
    }

    @Test
    fun `crowding is replaced as a whole and cleared on direction change`() {
        val s = run(TrainsIntent.CrowdingLoaded(mapOf("C" to CrowdingLevel.High, "S" to null)))
        assertEquals(CrowdingLevel.High, s.crowding["C"])
        val replaced = reduceTrains(s, TrainsIntent.CrowdingLoaded(mapOf("S" to CrowdingLevel.Low)))
        assertEquals(mapOf<String, CrowdingLevel?>("S" to CrowdingLevel.Low), replaced.crowding)
        assertTrue(reduceTrains(s, TrainsIntent.DirectionSelected(Direction.SantaCruzToCentral)).crowding.isEmpty())
        assertTrue(reduceTrains(s, TrainsIntent.CrowdingLoaded(emptyMap())).crowding.isEmpty())
    }

    @Test
    fun `check-in lasts until expiry and is cleared by cancel or direction change`() {
        val ci = StationCheckIn("S", Direction.CentralToSantaCruz, "id", 10_000)
        val s = run(TrainsIntent.CheckInChanged(ci))
        assertEquals(ci, run(TrainsIntent.Tick(9_999), from = s).checkIn)
        assertEquals(null, run(TrainsIntent.Tick(10_000), from = s).checkIn)
        assertEquals(null, run(TrainsIntent.CheckInChanged(null), from = s).checkIn)
        assertEquals(null, run(TrainsIntent.DirectionSelected(Direction.SantaCruzToCentral), from = s).checkIn)
    }
}
