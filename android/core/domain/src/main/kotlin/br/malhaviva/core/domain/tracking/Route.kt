package br.malhaviva.core.domain.tracking

import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Station
import br.malhaviva.core.domain.checkin.distanceM
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

data class Projection(
    /** Metros desde o inicio do tracado. */
    val alongM: Double,
    /** Distancia perpendicular ao tracado. */
    val lateralM: Double,
)

/** Tracado do ramal (polilinha) com estacoes posicionadas ao longo dele. */
class Route(
    val path: List<GeoPoint>,
    stations: List<Station>,
) {
    private val cumulative: DoubleArray =
        DoubleArray(path.size).also { c ->
            for (i in 1 until path.size) c[i] = c[i - 1] + distanceM(path[i - 1], path[i])
        }

    val lengthM: Double = cumulative.last()

    /** Estacoes ordenadas pela posicao no tracado. */
    val stationsAlong: List<Pair<Station, Double>> =
        stations.map { it to project(it.point).alongM }.sortedBy { it.second }

    fun project(p: GeoPoint): Projection {
        require(path.size >= 2) { "tracado precisa de 2 pontos" }
        val k = cos(p.lat * PI / HALF_TURN)

        fun xy(g: GeoPoint) = (g.lon - p.lon) * EARTH_M * k to (g.lat - p.lat) * EARTH_M
        var best = Projection(0.0, Double.MAX_VALUE)
        for (i in 0 until path.size - 1) {
            val (ax, ay) = xy(path[i])
            val (bx, by) = xy(path[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val lateral = hypot(ax + t * dx, ay + t * dy)
            if (lateral < best.lateralM) {
                best = Projection(cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), lateral)
            }
        }
        return best
    }

    private companion object {
        const val EARTH_M = 111_320.0
        const val HALF_TURN = 180.0
    }
}
