package br.malhaviva.network

import br.malhaviva.module
import br.malhaviva.platform.Database
import br.malhaviva.platform.DatabaseConfig
import br.malhaviva.tracking.ingest.JdbcCorridorGeometry
import br.malhaviva.tracking.ingest.PolylineCorridor
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val STATION_COUNT = 23
private const val MAX_STATION_OFFSET_M = 50.0
private const val SEGMENT_JOIN_TOLERANCE = 1e-9

/** Rede semeada pela V11 (OSM) num PostGIS 17 recem-migrado: contrato, ordem, along crescente e projecao. */
class NetworkSeedIntegrationTest {
    private val image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres")
    private val container = PostgreSQLContainer(image)
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        container.start()
        dataSource = Database.connect(DatabaseConfig(container.jdbcUrl, container.username, container.password))
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
        container.stop()
    }

    private fun JsonObject.points(key: String): List<Pair<Double, Double>> =
        getValue(key)
            .jsonObject
            .getValue("coordinates")
            .jsonArray
            .let(::lonLatToLatLon)

    private fun lonLatToLatLon(coordinates: JsonArray): List<Pair<Double, Double>> =
        coordinates.map { p ->
            p.jsonArray[1].jsonPrimitive.doubleOrNull!! to
                p.jsonArray[0].jsonPrimitive.doubleOrNull!!
        }

    /** Centro do anel externo do poligono, sem o ponto de fechamento (igual ao CorridorParser do app). */
    private fun JsonObject.platformCentre(): Pair<Double, Double> {
        val ring =
            getValue("platform")
                .jsonObject
                .getValue("coordinates")
                .jsonArray[0]
                .jsonArray
                .let(::lonLatToLatLon)
        val open = ring.dropLast(1)
        return open.sumOf { it.first } / open.size to open.sumOf { it.second } / open.size
    }

    @Test
    fun `corridor endpoint returns the seeded stations in order and the track projects onto them`() =
        testApplication {
            application { module(dataSource) }
            val response = client.get("/v1/network/corridors/RAMAL_SANTA_CRUZ")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("RAMAL_SANTA_CRUZ", body.getValue("corridor").jsonPrimitive.content)

            val stations = body.getValue("stations").jsonArray.map { it.jsonObject }
            assertEquals(STATION_COUNT, stations.size)
            assertEquals((1..STATION_COUNT).toList(), stations.map { it.getValue("seq_order").jsonPrimitive.int })
            assertEquals(
                "Central do Brasil",
                stations
                    .first()
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "Santa Cruz",
                stations
                    .last()
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            val km = stations.map { it.getValue("km").jsonPrimitive.doubleOrNull!! }
            assertEquals(0.0, km.first())
            assertTrue(km.zipWithNext().all { (a, b) -> b > a }, "km estritamente crescente: $km")

            val segments = body.getValue("segments").jsonArray.map { it.jsonObject }
            assertEquals(STATION_COUNT - 1, segments.size)
            val ids = stations.map { it.getValue("station_id").jsonPrimitive.content }
            segments.forEachIndexed { i, s ->
                assertEquals(ids[i], s.getValue("from_station_id").jsonPrimitive.content)
                assertEquals(ids[i + 1], s.getValue("to_station_id").jsonPrimitive.content)
            }
            val lines = segments.map { it.points("line") }
            lines.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    kotlin.math.abs(a.last().first - b.first().first) < SEGMENT_JOIN_TOLERANCE &&
                        kotlin.math.abs(a.last().second - b.first().second) < SEGMENT_JOIN_TOLERANCE,
                    "trechos contiguos",
                )
            }

            // along_m do tracado inteiro (Central -> Santa Cruz) e projecao do centro de cada plataforma
            val track = PolylineCorridor(lines.flatten())
            val projected = stations.map { track.alongTrack(it.platformCentre().first, it.platformCentre().second) }
            assertTrue(projected.all { it.second < MAX_STATION_OFFSET_M }, "estacao a mais de 50 m: $projected")
            assertTrue(projected.zipWithNext().all { (a, b) -> b.first > a.first }, "along_m monotonico: $projected")
            assertTrue(projected.first().first < MAX_STATION_OFFSET_M, "primeira estacao em along ~0")

            val missing = client.get("/v1/network/corridors/OUTRO")
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    @Test
    fun `database geometry of the seeded corridor measures stations within 50 m and in increasing along`() {
        Database.migrate(dataSource)
        val geometry = JdbcCorridorGeometry(dataSource, "RAMAL_SANTA_CRUZ")
        val centres = mutableListOf<Pair<Double, Double>>()
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT ST_Y(ST_Centroid(platform)) AS lat, ST_X(ST_Centroid(platform)) AS lon " +
                            "FROM stations WHERE corridor_id = 'RAMAL_SANTA_CRUZ' ORDER BY seq_order",
                    ).use { rs -> while (rs.next()) centres += rs.getDouble("lat") to rs.getDouble("lon") }
            }
        }
        assertEquals(STATION_COUNT, centres.size)
        val projected = centres.map { assertNotNull(geometry.alongTrack(it.first, it.second)) }
        assertTrue(projected.all { it.second < MAX_STATION_OFFSET_M }, "lateral > 50 m: $projected")
        assertTrue(projected.zipWithNext().all { (a, b) -> b.first > a.first }, "along crescente: $projected")
        assertTrue(centres.none { geometry.offCorridor(it.first, it.second) })
    }
}
