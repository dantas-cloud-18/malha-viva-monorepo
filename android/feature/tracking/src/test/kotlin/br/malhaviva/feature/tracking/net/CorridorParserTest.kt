package br.malhaviva.feature.tracking.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorridorParserTest {
    // formato do schema CorridorNetwork; segmento s2 listado antes de s1 para provar a ordenacao
    private val json =
        """
        {
          "corridor": "RAMAL_SANTA_CRUZ",
          "stations": [
            {"station_id": "B", "name": "Segunda", "seq_order": 2, "km": 1.0,
             "platform": {"type": "Polygon", "coordinates": [[[-43.0, -22.0], [-43.0, -22.2],
               [-43.2, -22.2], [-43.2, -22.0], [-43.0, -22.0]]]}},
            {"station_id": "A", "name": "Primeira", "seq_order": 1, "km": 0.0,
             "platform": {"type": "Polygon", "coordinates": [[[-44.0, -23.0], [-44.0, -23.2],
               [-44.2, -23.2], [-44.2, -23.0], [-44.0, -23.0]]]}}
          ],
          "segments": [
            {"segment_id": "s2", "from_station_id": "B", "to_station_id": "C",
             "line": {"type": "LineString", "coordinates": [[-43.1, -22.1], [-43.1, -22.0]]}},
            {"segment_id": "s1", "from_station_id": "A", "to_station_id": "B",
             "line": {"type": "LineString", "coordinates": [[-44.1, -23.1], [-43.1, -22.1]]}}
          ]
        }
        """.trimIndent()

    @Test
    fun `segments are concatenated in station order without duplicating the join point`() {
        val route = requireNotNull(CorridorParser.parse(json))
        assertEquals(3, route.path.size)
        assertEquals(-23.1, route.path[0].lat, 1e-9)
        assertEquals(-44.1, route.path[0].lon, 1e-9)
        assertEquals(-22.0, route.path[2].lat, 1e-9)
    }

    @Test
    fun `station is the polygon centroid with lat and lon in the right order`() {
        val route = requireNotNull(CorridorParser.parse(json))
        val ids = route.stationsAlong.map { it.first.id }
        assertEquals(listOf("A", "B"), ids)
        val a =
            route.stationsAlong
                .first()
                .first.point
        assertEquals(-23.1, a.lat, 1e-9)
        assertEquals(-44.1, a.lon, 1e-9)
    }

    @Test
    fun `corridor with fewer than two track points gives no route`() {
        val empty = """{"corridor":"X","stations":[],"segments":[]}"""
        assertNull(CorridorParser.parse(empty))
    }
}
