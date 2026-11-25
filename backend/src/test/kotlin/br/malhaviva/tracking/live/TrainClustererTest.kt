package br.malhaviva.tracking.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrainClustererTest {
    private val now = 1_800_000_000_000L
    private var seq = 0
    private val stations = listOf(StationRef("A", 1000.0), StationRef("B", 3000.0), StationRef("C", 5000.0))
    private val clusterer = TrainClusterer(minContributors = 3, newId = { "t${seq++}" }, stations = { stations })

    private fun pos(
        along: Double,
        ageMs: Long = 0,
        speed: Double = 10.0,
    ) = PassengerPosition(along, speed, now - ageMs)

    private fun train(
        along: Double,
        speed: Double,
        n: Int = 1,
        at: Long = now,
    ) = List(n) { _ -> PassengerPosition(along, speed, at) }

    @Test
    fun `agrupa passageiros proximos e separa trens distantes`() {
        val trains = clusterer.cluster(listOf(pos(1000.0), pos(1080.0), pos(1150.0), pos(5000.0)), now)
        assertEquals(1, trains.size, "o grupo de 1 sinal nao e publicado")
        assertEquals(ConfidenceBand.MEDIUM, trains[0].confidence)
    }

    @Test
    fun `k-1 sinais escondem o trem e k mostram`() {
        assertTrue(clusterer.cluster(listOf(pos(1000.0), pos(1050.0)), now).isEmpty())
        assertEquals(1, clusterer.cluster(listOf(pos(1000.0), pos(1050.0), pos(1100.0)), now).size)
    }

    @Test
    fun `faixas de confianca nas fronteiras`() {
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(1))
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(2))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(3))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(6))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(7))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(50))
    }

    @Test
    fun `k igual a 2 publica trem de dois`() {
        val two = TrainClusterer(minContributors = 2, newId = { "x" })
        assertEquals(ConfidenceBand.LOW, two.cluster(listOf(pos(1000.0), pos(1050.0)), now).single().confidence)
    }

    @Test
    fun `env do limiar usa padrao 1 e nunca fica abaixo de 1`() {
        fun k(v: String?) = ClusterPolicy.minContributorsFromEnv { v }
        assertEquals(1, k(null))
        assertEquals(1, k("abc"))
        assertEquals(1, k("0"))
        assertEquals(1, k("-4"))
        assertEquals(3, k("3"))
        assertEquals(5, k("5"))
    }

    @Test
    fun `padrao publica trem de um celular so`() {
        val one = TrainClusterer(newId = { "x" })
        assertEquals(1, one.cluster(listOf(pos(1000.0)), now).size)
    }

    @Test
    fun `projeta cada posicao para agora no sentido da viagem`() {
        val p = List(3) { pos(1000.0, ageMs = 10_000, speed = 12.0) }
        assertEquals(1120.0, clusterer.cluster(p, now).single().alongM, 0.01)
        val back = clusterer.cluster(p, now, direction = Direction.SANTA_CRUZ_TO_CENTRAL).single()
        assertEquals(880.0, back.alongM, 0.01)
    }

    @Test
    fun `celulares em fases diferentes formam um trem so depois da projecao`() {
        // 12 m/s, envios com 0, 14 e 28 s de idade: sem projecao ficam a 168 m e 336 m uma da outra
        val p = listOf(pos(2000.0, 0, 12.0), pos(1832.0, 14_000, 12.0), pos(1664.0, 28_000, 12.0))
        val trains = clusterer.cluster(p, now)
        assertEquals(1, trains.size)
        assertEquals(2000.0, trains.single().alongM, 0.01)
    }

    @Test
    fun `situacao, estacao de referencia e ETA a frente no sentido`() {
        val t = clusterer.cluster(train(1500.0, 10.0, 3), now).single()
        assertEquals(Situation.MOVING, t.situation)
        assertEquals("B", t.stationId)
        assertEquals(listOf(Eta("B", 150), Eta("C", 350)), t.etas)
        val back = clusterer.cluster(train(4000.0, 10.0, 3), now, direction = Direction.SANTA_CRUZ_TO_CENTRAL).single()
        assertEquals("B", back.stationId)
        assertEquals(listOf(Eta("B", 100), Eta("A", 300)), back.etas)
    }

    @Test
    fun `parado na estacao e AT_STATION sem ETA`() {
        val t = clusterer.cluster(train(3030.0, 0.0, 3), now).single()
        assertEquals(Situation.AT_STATION, t.situation)
        assertEquals("B", t.stationId)
        assertTrue(t.etas.isEmpty())
    }

    @Test
    fun `parado fora de estacao vira alerta so depois de 60 s`() {
        val first = clusterer.cluster(train(2000.0, 0.0, 3), now).single()
        assertEquals(Situation.MOVING, first.situation)
        val later = clusterer.cluster(train(2000.0, 0.0, 3, now + 59_000), now + 59_000, listOf(first)).single()
        assertEquals(Situation.MOVING, later.situation)
        val alert = clusterer.cluster(train(2000.0, 0.0, 3, now + 60_000), now + 60_000, listOf(later)).single()
        assertEquals(Situation.STOPPED_BETWEEN_STATIONS, alert.situation)
        assertEquals("B", alert.stationId)
        val moving = clusterer.cluster(train(2100.0, 10.0, 3, now + 61_000), now + 61_000, listOf(alert)).single()
        assertEquals(Situation.MOVING, moving.situation)
    }

    @Test
    fun `json do trem leva situacao e nao leva o tempo parado`() {
        val t = clusterer.cluster(train(2000.0, 0.0, 3), now).single()
        val json =
            kotlinx.serialization.json.Json
                .encodeToString(TrainState.serializer(), t)
        assertTrue("stopped" !in json && "\"situation\":\"moving\"" in json, json)
    }

    @Test
    fun `ids permanecem estaveis enquanto o trem anda ate 300 m`() {
        val first = clusterer.cluster(listOf(pos(1000.0), pos(1040.0), pos(1060.0)), now)
        val second = clusterer.cluster(listOf(pos(1250.0), pos(1290.0), pos(1310.0)), now, first)
        assertEquals(first.single().id, second.single().id)
        val jumped = clusterer.cluster(listOf(pos(2000.0), pos(2020.0), pos(2040.0)), now, second)
        assertNotEquals(second.single().id, jumped.single().id)
    }

    @Test
    fun `rejeita outliers com 30 por cento de falsos`() {
        val real = (0 until 7).map { pos(5000.0 + it * 10) }
        val fake = listOf(pos(5200.0), pos(5210.0), pos(5220.0))
        val train = clusterer.cluster(real + fake, now).single()
        assertEquals(ConfidenceBand.HIGH, train.confidence)
        assertTrue(train.alongM in 4990.0..5070.0, "alongM=${train.alongM}")
    }

    @Test
    fun `descarta posicoes com mais de 60 s`() {
        val trains = clusterer.cluster(listOf(pos(1000.0, ageMs = 60_001), pos(3000.0), pos(3030.0), pos(3060.0)), now)
        assertEquals(1, trains.size)
        assertEquals(3030.0, trains.single().alongM)
    }
}
