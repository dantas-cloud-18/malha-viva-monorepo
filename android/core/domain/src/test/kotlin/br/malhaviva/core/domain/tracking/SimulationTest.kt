package br.malhaviva.core.domain.tracking

import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.checkin.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Simulador de passageiros (BRIEF 3.5): ruido de GPS, tuneis e atacantes. */
class SimulationTest {
    private val route =
        Route(
            listOf(GeoPoint(LAT, -43.20), GeoPoint(LAT, -43.10)),
            listOf(Station("a", GeoPoint(LAT, -43.20)), Station("c", GeoPoint(LAT, -43.10))),
        )
    private val rnd = Random(42)

    private fun point(
        alongM: Double,
        lat: Double = LAT,
    ) = GeoPoint(lat, -43.20 + alongM / LON_M)

    private fun truth(tS: Long) = START + SPEED * tS

    /** Passageiro honesto: leitura a cada 5 s com ruido; sem leitura dentro do tunel. */
    private fun honest(tunnel: LongRange?): Double? {
        val f = TrackFilter(route)
        for (t in 5..END_S step 5) {
            if (tunnel != null && t.toLong() in tunnel) continue
            val noisy = truth(t.toLong()) + rnd.nextGaussian() * NOISE_M
            f.update(Reading(point(noisy), 10f, (SPEED * 3.6).toFloat(), t * 1000L))
        }
        return f.at(END_S * 1000L)?.takeIf { it.status != TrackStatus.NoSignal }?.alongM
    }

    /** Atacante: reporta posicao falsa fixa (jogando com o consenso). */
    private fun liar(fakeAlong: Double): Double? {
        val f = TrackFilter(route)
        for (t in 5..END_S step 5) f.update(Reading(point(fakeAlong), 10f, 40f, t * 1000L))
        return f.at(END_S * 1000L)?.alongM
    }

    @Test
    fun correctPositionWithThreeHonestAnd30PercentFalse() {
        val honest = (0 until 10).map { honest(if (it % 3 == 0) 60L..100L else null) }
        val liars = listOf(liar(8_000.0), liar(8_000.0), liar(500.0), liar(9_000.0))
        val c = consensus((honest + liars).filterNotNull())
        assertNotNull(c)
        assertEquals(truth(END_S.toLong()), c!!.alongM, 60.0)
        assertTrue(c.confidence > 0.6)
    }

    @Test
    fun minimumThreeHonestSignalsSurviveNearlyThirtyPercentLiars() {
        val honest = (0 until 3).map { honest(null) }
        val c = consensus((honest + liar(7_000.0)).filterNotNull())
        assertEquals(truth(END_S.toLong()), c!!.alongM, 60.0)
    }

    @Test
    fun singleSignalHasLowConfidence() {
        val c = consensus(listOfNotNull(liar(7_000.0)))
        assertNotNull(c)
        assertTrue(c!!.confidence <= ConsensusPolicy.CONF_ONE)
    }

    @Test
    fun offCorridorSpoofNeverEntersTheTrack() {
        val f = TrackFilter(route)
        for (t in 5..END_S step 5) f.update(Reading(point(3_000.0, lat = LAT - 0.02), 5f, 40f, t * 1000L))
        assertEquals(null, f.at(END_S * 1000L))
    }

    @Test
    fun poorAccuracySignalsAreIgnored() {
        val f = TrackFilter(route)
        for (t in 5..END_S step 5) f.update(Reading(point(3_000.0), 300f, 40f, t * 1000L))
        assertEquals(null, f.at(END_S * 1000L))
    }

    @Test
    fun noiseOnlyDoesNotInflateConfidenceLoss() {
        val c = consensus((0 until 8).map { honest(null) }.filterNotNull())
        assertEquals(8, c!!.signals)
        assertTrue(abs(c.alongM - truth(END_S.toLong())) < NOISE_M * 2)
    }

    @Test
    fun positionRecordsCarryNoUserIdentity() {
        val fields = Reading::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { "user" in it || "device" in it || "account" in it || it == "id" })
        assertFalse(TrackState::class.java.declaredFields.any { "user" in it.name.lowercase() })
    }

    private companion object {
        const val LAT = -22.90
        const val LON_M = 102_540.0
        const val SPEED = 20.0
        const val START = 1_000.0
        const val END_S = 150
        const val NOISE_M = 15.0
    }
}
