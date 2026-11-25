package br.malhaviva.feature.trains

import kotlin.math.cos
import kotlin.math.hypot

private const val M_PER_DEG = 111_320.0

data class LatLon(
    val lat: Double,
    val lon: Double,
)

/** Centro da plataforma de cada estacao e o traçado de cada trecho (de uma estacao a seguinte, na ordem da linha). */
class LineShape(
    val stationPos: Map<String, LatLon>,
    val segments: Map<Pair<String, String>, List<LatLon>>,
)

/** Distancia plana (equirretangular), suficiente para trechos de poucos km. */
fun distanceM(
    a: LatLon,
    b: LatLon,
): Double = hypot((b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)), b.lat - a.lat) * M_PER_DEG

/** Traçado de [a] ate [b]: o do trecho, se houver; senao reta entre as estacoes. */
private fun legPath(
    shape: LineShape,
    a: LineStation,
    b: LineStation,
): List<LatLon> =
    shape.segments[a.id to b.id]?.takeIf { it.size >= 2 }
        ?: listOfNotNull(shape.stationPos[a.id], shape.stationPos[b.id])

/** Linha completa Central -> Santa Cruz, para desenhar. */
fun LineNetwork.path(): List<LatLon> {
    val shape = shape ?: return emptyList()
    val points = ArrayList<LatLon>()
    stations.zipWithNext().forEach { (a, b) -> points += legPath(shape, a, b) }
    return points
}

/**
 * Posicao geografica de [alongM]: entre duas estacoes, a fracao da distancia (pelas estacoes, que e a escala do
 * servidor) vira a mesma fracao do comprimento do traçado do trecho. Fora dos extremos prende na ponta.
 */
fun alongToLatLon(
    line: LineNetwork,
    alongM: Double,
): LatLon? {
    val shape = line.shape
    val s = line.stations
    if (shape == null || s.size < 2) return s.firstOrNull()?.let { line.shape?.stationPos?.get(it.id) }
    val first = s.indexOfFirst { it.alongM > alongM }
    val upper = if (first < 0) s.lastIndex else first.coerceAtLeast(1)
    val a = s[upper - 1]
    val b = s[upper]
    val span = b.alongM - a.alongM
    val fraction = if (span <= 0.0) 0.0 else ((alongM - a.alongM) / span).coerceIn(0.0, 1.0)
    return pointAt(legPath(shape, a, b), fraction)
}

private fun pointAt(
    path: List<LatLon>,
    fraction: Double,
): LatLon? {
    if (path.size < 2) return path.firstOrNull()
    val lengths = path.zipWithNext { p, q -> distanceM(p, q) }
    var left = lengths.sum() * fraction
    var i = 0
    while (i < lengths.lastIndex && left > lengths[i]) left -= lengths[i++]
    val t = if (lengths[i] <= 0.0) 0.0 else (left / lengths[i]).coerceIn(0.0, 1.0)
    return LatLon(
        path[i].lat + (path[i + 1].lat - path[i].lat) * t,
        path[i].lon + (path[i + 1].lon - path[i].lon) * t,
    )
}
