package br.malhaviva.tracking.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrainClustererTest {
    private val now = 1_800_000_000_000L
    private var seq = 0
    private val clusterer = TrainClusterer { "t${seq++}" }

    private fun pos(
        along: Double,
        ageMs: Long = 0,
        speed: Double = 10.0,
    ) = PassengerPosition(along, speed, now - ageMs)

    @Test
    fun `agrupa passageiros proximos e separa trens distantes`() {
        val trains = clusterer.cluster(listOf(pos(1000.0), pos(1080.0), pos(1150.0), pos(5000.0)), now)
        assertEquals(2, trains.size)
        assertEquals(3, trains[0].signals)
        assertEquals(1, trains[1].signals)
        assertEquals(0.3, trains[1].confidence)
    }

    @Test
    fun `confianca com dois sinais e 0,5`() {
        val trains = clusterer.cluster(listOf(pos(1000.0), pos(1050.0)), now)
        assertEquals(0.5, trains.single().confidence)
    }

    @Test
    fun `ids permanecem estaveis enquanto o trem anda ate 300 m`() {
        val first = clusterer.cluster(listOf(pos(1000.0), pos(1040.0)), now)
        val second = clusterer.cluster(listOf(pos(1250.0), pos(1290.0)), now, first)
        assertEquals(first.single().id, second.single().id)
        val jumped = clusterer.cluster(listOf(pos(2000.0)), now, second)
        assertNotEquals(second.single().id, jumped.single().id)
    }

    @Test
    fun `rejeita outliers com 30 por cento de falsos`() {
        val real = (0 until 7).map { pos(5000.0 + it * 10) }
        val fake = listOf(pos(5200.0), pos(5210.0), pos(5220.0))
        val train = clusterer.cluster(real + fake, now).single()
        assertEquals(10, train.signals)
        assertTrue(train.alongM in 4990.0..5070.0, "alongM=${train.alongM}")
        assertEquals(0.6 + 0.4 * 0.7, train.confidence, 1e-9)
    }

    @Test
    fun `descarta posicoes com mais de 30 s`() {
        val trains = clusterer.cluster(listOf(pos(1000.0, ageMs = 30_001), pos(3000.0, ageMs = 5_000)), now)
        assertEquals(1, trains.size)
        assertEquals(3000.0, trains.single().alongM)
    }
}
