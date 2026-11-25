package br.malhaviva.feature.trains

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineGeometryTest {
    private val a = LineStation("A", "A", 0.0)
    private val b = LineStation("B", "B", 1_000.0)
    private val c = LineStation("C", "C", 3_000.0)

    // A(0,0) -> B(0,0.01) reto; B -> C com quebra: sobe 0.01 e depois vai para leste 0.01
    private val shape =
        LineShape(
            mapOf("A" to LatLon(0.0, 0.0), "B" to LatLon(0.0, 0.01), "C" to LatLon(0.01, 0.02)),
            mapOf(
                ("A" to "B") to listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01)),
                ("B" to "C") to listOf(LatLon(0.0, 0.01), LatLon(0.01, 0.01), LatLon(0.01, 0.02)),
            ),
        )
    private val line = LineNetwork(listOf(a, b, c), 3_000.0, shape)

    @Test
    fun `interpola no meio do trecho reto`() {
        val p = alongToLatLon(line, 500.0)!!
        assertEquals(0.005, p.lon, 1e-9)
        assertEquals(0.0, p.lat, 1e-9)
    }

    @Test
    fun `segue a quebra do traçado e prende nas pontas`() {
        // metade do trecho B-C = metade do comprimento do traçado na quebra
        val p = alongToLatLon(line, 2_000.0)!!
        assertEquals(0.01, p.lat, 1e-4)
        assertEquals(0.01, p.lon, 1e-4)
        assertEquals(LatLon(0.0, 0.0), alongToLatLon(line, -50.0))
        assertEquals(0.02, alongToLatLon(line, 9_999.0)!!.lon, 1e-9)
    }

    @Test
    fun `sem geometria nao ha posicao`() {
        assertNull(alongToLatLon(LineNetwork(listOf(a, b), 1_000.0), 10.0))
    }
}
