package br.malhaviva.tracking.ingest

import kotlin.math.cos
import kotlin.math.hypot

/** Distancia lateral maxima ao tracado para a amostra contar como dentro do corredor. */
const val MAX_LATERAL_M = 60.0

private const val METERS_PER_DEGREE = 111_320.0

interface CorridorGeometry {
    /** (metros ao longo do tracado, metros laterais), ou null se nao houver tracado. */
    fun alongTrack(
        lat: Double,
        lon: Double,
    ): Pair<Double, Double>?

    /** Sem tracado conta como fora do corredor. */
    fun offCorridor(
        lat: Double,
        lon: Double,
    ): Boolean = (alongTrack(lat, lon)?.second ?: Double.MAX_VALUE) > MAX_LATERAL_M
}

/**
 * Tracado em memoria. [points] sao pares (lat, lon) do inicio (Central, along 0) ao fim.
 * Projecao equirretangular, igual ao Route.kt do app.
 */
class PolylineCorridor(
    private val points: List<Pair<Double, Double>>,
) : CorridorGeometry {
    init {
        require(points.size >= 2) { "tracado precisa de 2 pontos" }
    }

    private val cumulative =
        DoubleArray(points.size).also { c ->
            for (i in 1 until points.size) c[i] = c[i - 1] + lengthM(points[i - 1], points[i])
        }

    override fun alongTrack(
        lat: Double,
        lon: Double,
    ): Pair<Double, Double> =
        (0 until points.size - 1)
            .map { projectOnSegment(it, lat, lon) }
            .minBy { it.second }

    private fun projectOnSegment(
        i: Int,
        lat: Double,
        lon: Double,
    ): Pair<Double, Double> {
        val (ax, ay) = relativeXy(points[i], lat, lon)
        val (bx, by) = relativeXy(points[i + 1], lat, lon)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val lateral = hypot(ax + t * dx, ay + t * dy)
        return cumulative[i] + t * (cumulative[i + 1] - cumulative[i]) to lateral
    }

    private fun relativeXy(
        p: Pair<Double, Double>,
        lat: Double,
        lon: Double,
    ): Pair<Double, Double> {
        val k = cos(Math.toRadians(lat))
        return (p.second - lon) * METERS_PER_DEGREE * k to (p.first - lat) * METERS_PER_DEGREE
    }

    private fun lengthM(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val k = cos(Math.toRadians((a.first + b.first) / 2))
        return hypot((b.second - a.second) * k, b.first - a.first) * METERS_PER_DEGREE
    }
}
