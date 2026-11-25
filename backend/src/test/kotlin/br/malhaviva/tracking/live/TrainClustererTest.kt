package br.malhaviva.tracking.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrainClustererTest {
    private val now = 1_800_000_000_000L
    private var seq = 0
    private val clusterer = TrainClusterer(newId = { "t${seq++}" })

    private fun pos(
        along: Double,
        ageMs: Long = 0,
        speed: Double = 10.0,
    ) = PassengerPosition(along, speed, now - ageMs)

    @Test
    fun `agrupa passageiros proximos e separa trens distantes`() {
        val trains = clusterer.cluster(listOf(pos(1000.0), pos(1080.0), pos(1150.0), pos(5000.0)), now)
        assertEquals(1, trains.size, "o grupo de 1 sinal nao e publicado")
        assertEquals(ConfidenceBand.LOW, trains[0].confidence)
    }

    @Test
    fun `k-1 sinais escondem o trem e k mostram`() {
        assertTrue(clusterer.cluster(listOf(pos(1000.0), pos(1050.0)), now).isEmpty())
        assertEquals(1, clusterer.cluster(listOf(pos(1000.0), pos(1050.0), pos(1100.0)), now).size)
    }

    @Test
    fun `faixas de confianca nas fronteiras`() {
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(2))
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(4))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(5))
        assertEquals(ConfidenceBand.MEDIUM, ConfidenceBand.of(9))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(10))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(50))
    }

    @Test
    fun `k igual a 2 publica trem de dois`() {
        val two = TrainClusterer(minContributors = 2, newId = { "x" })
        assertEquals(ConfidenceBand.LOW, two.cluster(listOf(pos(1000.0), pos(1050.0)), now).single().confidence)
    }

    @Test
    fun `env do limiar usa padrao 3 e nunca fica abaixo de 2`() {
        fun k(v: String?) = ClusterPolicy.minContributorsFromEnv { v }
        assertEquals(3, k(null))
        assertEquals(3, k("abc"))
        assertEquals(2, k("1"))
        assertEquals(2, k("0"))
        assertEquals(5, k("5"))
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
    fun `descarta posicoes com mais de 30 s`() {
        val trains = clusterer.cluster(listOf(pos(1000.0, ageMs = 30_001), pos(3000.0), pos(3030.0), pos(3060.0)), now)
        assertEquals(1, trains.size)
        assertEquals(3030.0, trains.single().alongM)
    }
}
