package br.malhaviva.tracking.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorridorGeometryTest {
    private val corridor = PolylineCorridor(listOf(-22.9 to -43.2, -22.9 to -43.1))

    @Test
    fun `along cresce no sentido do tracado e lateral e perpendicular`() {
        val (along, lateral) = corridor.alongTrack(-22.9, -43.15)!!
        assertEquals(5_100.0, along, 100.0)
        assertEquals(0.0, lateral, 0.5)
        val (_, off) = corridor.alongTrack(-22.8995, -43.15)!!
        assertEquals(55.7, off, 1.0)
    }

    @Test
    fun `limite lateral de 60 m`() {
        assertFalse(corridor.offCorridor(-22.8995, -43.15))
        assertTrue(corridor.offCorridor(-22.899, -43.15))
    }
}
