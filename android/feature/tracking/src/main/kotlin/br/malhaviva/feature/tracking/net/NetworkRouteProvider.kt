package br.malhaviva.feature.tracking.net

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Station
import br.malhaviva.core.domain.tracking.Route
import br.malhaviva.feature.tracking.RouteProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/** Converte `CorridorNetwork` do contrato em [Route]; null se o tracado tiver menos de 2 pontos. */
object CorridorParser {
    fun parse(json: String): Route? {
        val root = JSONObject(json)
        val stationsJson = root.getJSONArray("stations").objects()
        val order = stationsJson.associate { it.getString("station_id") to it.getInt("seq_order") }
        // segmentos em ordem de estacao de origem; sort estavel mantem a ordem original em empates
        val segments =
            root.getJSONArray("segments").objects().sortedBy {
                order[it.getString("from_station_id")]
                    ?: Int.MAX_VALUE
            }
        val path = mutableListOf<GeoPoint>()
        segments.forEach { segment ->
            val points = segment.getJSONObject("line").getJSONArray("coordinates").points()
            points.forEachIndexed { i, p -> if (i > 0 || path.lastOrNull() != p) path += p }
        }
        if (path.size < 2) return null
        val stations =
            stationsJson.mapNotNull { s ->
                centroid(s.getJSONObject("platform"))?.let { Station(s.getString("station_id"), it) }
            }
        return Route(path, stations)
    }

    /** Centro do anel externo do poligono (GeoJSON usa [lon, lat]); o ponto de fechamento nao conta. */
    private fun centroid(polygon: JSONObject): GeoPoint? {
        val ring = polygon.getJSONArray("coordinates").getJSONArray(0).points()
        val points = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
        if (points.isEmpty()) return null
        return GeoPoint(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONArray.points(): List<GeoPoint> =
        List(length()) { i -> getJSONArray(i).let { GeoPoint(lat = it.getDouble(1), lon = it.getDouble(0)) } }
}

/**
 * Busca a malha do corredor uma vez e guarda em memoria. [current] nunca bloqueia: sem cache, dispara
 * a busca em segundo plano e devolve null (o servico encerra com NoRoute e o usuario tenta de novo).
 */
class NetworkRouteProvider(
    baseUrl: String,
    private val transport: HttpTransport,
    private val scope: CoroutineScope,
    private val corridor: String = HttpBatchUploader.CORRIDOR,
) : RouteProvider {
    private val base = baseUrl.trimEnd('/')
    private val loading = AtomicBoolean(false)

    @Volatile private var route: Route? = null

    override fun current(): Route? {
        route?.let { return it }
        prefetch()
        return null
    }

    fun prefetch() {
        if (route == null && loading.compareAndSet(false, true)) {
            scope.launch {
                try {
                    refresh()
                } finally {
                    loading.set(false)
                }
            }
        }
    }

    suspend fun refresh(): Boolean {
        if (route != null) return true
        route =
            try {
                val response = transport.execute(HttpRequest("GET", "$base/v1/network/corridors/$corridor"))
                if (response.status == HttpURLConnection.HTTP_OK) CorridorParser.parse(response.body) else null
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        return route != null
    }
}
