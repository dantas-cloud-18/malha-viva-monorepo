package br.malhaviva.network

import br.malhaviva.Problem
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

private const val CORRIDOR = "RAMAL_SANTA_CRUZ"
private const val CACHE_SECONDS = 3600

private const val STATIONS_SQL = """
    SELECT station_id, name, seq_order, km::float8 AS km, ST_AsGeoJSON(platform) AS platform
    FROM stations WHERE corridor_id = ? ORDER BY seq_order
"""

private const val SEGMENTS_SQL = """
    SELECT segment_id, from_station_id, to_station_id, ST_AsGeoJSON(line) AS line
    FROM track_segments WHERE corridor_id = ? ORDER BY km_start
"""

@Serializable
data class NetworkStation(
    @SerialName("station_id")
    val stationId: String,
    val name: String,
    @SerialName("seq_order")
    val seqOrder: Int,
    val km: Double,
    val platform: JsonElement,
)

@Serializable
data class NetworkSegment(
    @SerialName("segment_id")
    val segmentId: String,
    @SerialName("from_station_id")
    val fromStationId: String,
    @SerialName("to_station_id")
    val toStationId: String,
    val line: JsonElement,
)

@Serializable
data class CorridorNetwork(
    val corridor: String,
    val stations: List<NetworkStation>,
    val segments: List<NetworkSegment>,
)

/** Malha estatica (muda so por migracao): carregada do banco uma vez por corredor e guardada em memoria. */
class NetworkRepository(
    private val dataSource: DataSource,
) {
    private val cache = ConcurrentHashMap<String, CorridorNetwork>()

    fun find(corridor: String): CorridorNetwork? = cache[corridor] ?: load(corridor)?.also { cache[corridor] = it }

    private fun load(corridor: String): CorridorNetwork? =
        dataSource.connection.use { c ->
            val stations =
                c.query(STATIONS_SQL, corridor) { rs ->
                    NetworkStation(
                        rs.getString("station_id"),
                        rs.getString("name"),
                        rs.getInt("seq_order"),
                        rs.getDouble("km"),
                        Json.parseToJsonElement(rs.getString("platform")),
                    )
                }
            val segments =
                c.query(SEGMENTS_SQL, corridor) { rs ->
                    NetworkSegment(
                        rs.getString("segment_id"),
                        rs.getString("from_station_id"),
                        rs.getString("to_station_id"),
                        Json.parseToJsonElement(rs.getString("line")),
                    )
                }
            if (stations.isEmpty()) null else CorridorNetwork(corridor, stations, segments)
        }

    private fun <T> java.sql.Connection.query(
        sql: String,
        corridor: String,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        prepareStatement(sql).use { st ->
            st.setString(1, corridor)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}

/** `GET /v1/network/corridors/{corridor}`: estacoes em ordem (Central -> Santa Cruz) e trechos em GeoJSON. */
fun Route.networkRoutes(repository: NetworkRepository) {
    get("/network/corridors/{corridor}") {
        val network = call.parameters["corridor"]?.takeIf { it == CORRIDOR }?.let(repository::find)
        if (network == null) {
            val status = HttpStatusCode.NotFound
            call.respond(status, Problem("about:blank", "Corridor not found", status.value))
        } else {
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = CACHE_SECONDS))
            call.respond(network)
        }
    }
}
