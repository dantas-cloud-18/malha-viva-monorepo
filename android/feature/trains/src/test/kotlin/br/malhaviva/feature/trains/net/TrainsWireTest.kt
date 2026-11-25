package br.malhaviva.feature.trains.net

import br.malhaviva.feature.trains.ConfidenceBand
import br.malhaviva.feature.trains.CrowdingLevel
import br.malhaviva.feature.trains.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrainsWireTest {
    private val ok =
        """{"id":"t1","along_m":12345.6,"speed_mps":11.5,"confidence":"medium","updated_at_ms":1700000000000}"""

    @Test
    fun `parses a TrainState from the contract`() {
        val t = requireNotNull(TrainsWire.parseTrain(ok))
        assertEquals("t1", t.id)
        assertEquals(12345.6, t.alongM, 1e-9)
        assertEquals(11.5, t.speedMps, 1e-9)
        assertEquals(ConfidenceBand.Medium, t.confidence)
        assertEquals(1700000000000L, t.updatedAtMs)
    }

    @Test
    fun `parses situation, reference station and etas, and defaults them for an older server`() {
        val full =
            ok.dropLast(1) +
                ""","situation":"stopped_between_stations","station_id":"bangu",""" +
                """"etas":[{"station_id":"bangu","eta_s":90},{"station_id":"realengo","eta_s":240}]}"""
        val t = requireNotNull(TrainsWire.parseTrain(full))
        assertEquals(Situation.StoppedBetweenStations, t.situation)
        assertEquals("bangu", t.stationId)
        assertEquals(mapOf("bangu" to 90, "realengo" to 240), t.etasS)
        val noStation =
            requireNotNull(
                TrainsWire.parseTrain(
                    ok.dropLast(1) + ""","situation":"at_station","station_id":null,"etas":[]}""",
                ),
            )
        assertEquals(Situation.AtStation, noStation.situation)
        assertNull(noStation.stationId)
        val old = requireNotNull(TrainsWire.parseTrain(ok))
        assertEquals(Situation.Moving, old.situation)
        assertEquals(emptyMap<String, Int>(), old.etasS)
    }

    @Test
    fun `malformed or out of contract events are dropped`() {
        assertNull(TrainsWire.parseTrain("not json"))
        assertNull(TrainsWire.parseTrain("""{"id":"x"}"""))
        assertNull(TrainsWire.parseTrain(ok.replace("11.5", "-1")))
        assertNull(TrainsWire.parseTrain(ok.replace("medium", "extreme")))
    }

    @Test
    fun `confidence band maps low medium high and ignores any signals count`() {
        ConfidenceBand.entries.forEach {
            val json = ok.replace("medium", it.wire).replace("\"id\"", "\"signals\":7,\"id\"")
            assertEquals(it, requireNotNull(TrainsWire.parseTrain(json)).confidence)
        }
    }

    @Test
    fun `parses a snapshot and skips bad entries`() {
        val json = """{"corridor":"RAMAL_SANTA_CRUZ","direction":"CENTRAL_TO_SANTA_CRUZ","trains":[$ok,{"id":"bad"}]}"""
        assertEquals(listOf("t1"), requireNotNull(TrainsWire.parseSnapshot(json)).map { it.id })
        assertNull(TrainsWire.parseSnapshot("""{"corridor":"X"}"""))
        val good = """{"corridor":"RAMAL_SANTA_CRUZ","direction":"CENTRAL_TO_SANTA_CRUZ","trains":[$ok]}"""
        assertEquals(1, requireNotNull(TrainsWire.parseSnapshot(good)).size)
        assertNotNull(TrainsWire.parseSnapshot("""{"trains":[]}"""))
    }

    @Test
    fun `crowding keeps levels, null and unknown values become no data, counts are ignored`() {
        val json =
            """{"corridor":"RAMAL_SANTA_CRUZ","direction":"CENTRAL_TO_SANTA_CRUZ","stations":[
            {"station_id":"a","level":"low"},{"station_id":"b","level":"high"},
            {"station_id":"c","level":null},{"station_id":"d","level":"packed"},
            {"station_id":"e","level":"medium","count":7}]}"""
        val map = requireNotNull(TrainsWire.parseCrowding(json))
        assertEquals(CrowdingLevel.Low, map["a"])
        assertEquals(CrowdingLevel.High, map["b"])
        assertEquals(CrowdingLevel.Medium, map["e"])
        assertEquals(setOf("a", "b", "c", "d", "e"), map.keys)
        assertNull(map["c"])
        assertNull(map["d"])
        assertNull(TrainsWire.parseCrowding("not json"))
        assertNull(TrainsWire.parseCrowding("""{"corridor":"X"}"""))
    }
}
