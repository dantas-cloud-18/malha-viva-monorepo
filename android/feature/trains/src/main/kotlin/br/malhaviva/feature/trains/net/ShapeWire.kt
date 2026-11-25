package br.malhaviva.feature.trains.net

import br.malhaviva.feature.trains.LatLon
import br.malhaviva.feature.trains.LineShape
import br.malhaviva.feature.trains.distanceM
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Geometria da malha (centro das plataformas e traçado dos trechos) para o mapa. */
internal object ShapeWire {
    fun parse(json: String): LineShape? =
        try {
            val root = JSONObject(json)
            val stations = root.getJSONArray("stations")
            val centers = HashMap<String, LatLon>()
            for (i in 0 until stations.length()) {
                val s = stations.getJSONObject(i)
                val ring = s.optJSONObject("platform")?.optJSONArray("coordinates")?.optJSONArray(0)
                if (ring != null) centers[s.getString("station_id")] = centroid(ring)
            }
            val segments = root.getJSONArray("segments")
            val paths = HashMap<Pair<String, String>, List<LatLon>>()
            for (i in 0 until segments.length()) {
                val seg = segments.getJSONObject(i)
                val coords = seg.optJSONObject("line")?.optJSONArray("coordinates") ?: continue
                val from = seg.getString("from_station_id")
                val to = seg.getString("to_station_id")
                paths[from to to] = orient(positions(coords), centers[from], centers[to])
            }
            if (centers.isEmpty()) null else LineShape(centers, paths)
        } catch (_: JSONException) {
            null
        }

    private fun positions(array: JSONArray): List<LatLon> =
        (0 until array.length()).map { array.getJSONArray(it).let { c -> LatLon(c.getDouble(1), c.getDouble(0)) } }

    private fun centroid(ring: JSONArray): LatLon {
        val p = positions(ring)
        return LatLon(p.map { it.lat }.average(), p.map { it.lon }.average())
    }

    /** Garante o traçado de [from] para [to] (o servidor pode entregar o trecho invertido). */
    private fun orient(
        points: List<LatLon>,
        from: LatLon?,
        to: LatLon?,
    ): List<LatLon> {
        val first = points.firstOrNull()
        val reversed = first != null && from != null && to != null && distanceM(first, to) < distanceM(first, from)
        return if (reversed) points.reversed() else points
    }
}
