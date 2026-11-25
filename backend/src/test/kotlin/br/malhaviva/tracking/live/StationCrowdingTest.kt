package br.malhaviva.tracking.live

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StationCrowdingTest {
    private val now = 1_800_000_000_000L
    private val key = LineKey("RAMAL_SANTA_CRUZ", Direction.CENTRAL_TO_SANTA_CRUZ)
    private val bangu = StationRef("bangu", 28_978.0)
    private val madureira = StationRef("madureira", 15_383.0)
    private val stations = listOf(madureira, bangu)
    private val estimator = StationCrowdingEstimator()

    private fun dwell(
        at: Double,
        speed: Double = 0.0,
    ) = PassengerPosition(at, speed, now)

    private fun level(
        positions: List<PassengerPosition>,
        station: StationRef = bangu,
    ) = estimator.estimate(positions, stations).first { it.stationId == station.id }.level

    @Test
    fun `k minus 1 is hidden and k is shown as the lowest band`() {
        assertNull(level(List(4) { dwell(28_980.0) }))
        assertEquals(CrowdingLevel.LOW, level(List(5) { dwell(28_980.0) }))
    }

    @Test
    fun `band boundaries`() {
        assertEquals(CrowdingLevel.LOW, level(List(9) { dwell(28_978.0) }))
        assertEquals(CrowdingLevel.MEDIUM, level(List(10) { dwell(28_978.0) }))
        assertEquals(CrowdingLevel.MEDIUM, level(List(19) { dwell(28_978.0) }))
        assertEquals(CrowdingLevel.HIGH, level(List(20) { dwell(28_978.0) }))
    }

    @Test
    fun `moving or far trips do not count`() {
        val moving = List(5) { dwell(28_978.0, speed = 8.0) }
        val far = List(5) { dwell(28_978.0 + CrowdingPolicy.STATION_RADIUS_M + 1) }
        assertNull(level(moving))
        assertNull(level(far))
        assertNull(level(moving.take(3) + List(2) { dwell(28_978.0) }))
    }

    @Test
    fun `stations are counted independently`() {
        val positions = List(5) { dwell(15_383.0) } + List(4) { dwell(28_978.0) }
        assertEquals(CrowdingLevel.LOW, level(positions, madureira))
        assertNull(level(positions, bangu))
    }

    @Test
    fun `same trip sending many points counts once`() {
        val hub = LiveHub(clock = { now })
        repeat(10) { hub.update(key, "eph-a", dwell(28_978.0)) }
        listOf("b", "c", "d").forEach { hub.update(key, "eph-$it", dwell(28_978.0)) }
        assertEquals(4, hub.freshPositions(key).size)
        assertNull(level(hub.freshPositions(key)))
        hub.update(key, "eph-e", dwell(28_978.0))
        assertEquals(CrowdingLevel.LOW, level(hub.freshPositions(key)))
    }

    @Test
    fun `stale positions are dropped`() {
        val hub = LiveHub(clock = { now })
        repeat(5) { hub.update(key, "eph-$it", PassengerPosition(28_978.0, 0.0, now - ClusterPolicy.MAX_AGE_MS - 1)) }
        assertEquals(0, hub.freshPositions(key).size)
    }

    private var t = 0L
    private val smoother = StationCrowdingSmoother(clock = { t })

    private fun pub(n: Int) = smoother.publish(key, listOf("bangu" to n)).single().level

    private fun settleLow() {
        t = 0
        pub(5)
        t += CrowdingPolicy.HOLD_MS
        assertEquals(CrowdingLevel.LOW, pub(5))
    }

    @Test
    fun `null to visible waits the hold`() {
        assertNull(pub(5))
        t += CrowdingPolicy.HOLD_MS - 1
        assertNull(pub(5))
        t += 1
        assertEquals(CrowdingLevel.LOW, pub(5))
    }

    @Test
    fun `flapping around a boundary keeps the published level`() {
        settleLow()
        repeat(10) {
            t += 5_000
            assertEquals(CrowdingLevel.LOW, pub(if (it % 2 == 0) 12 else 9))
        }
        t += CrowdingPolicy.HOLD_MS * 2
        assertEquals(CrowdingLevel.LOW, pub(11)) // dentro da margem (9 + 2)
    }

    @Test
    fun `sustained change publishes after hold and needs margin to leave`() {
        settleLow()
        assertEquals(CrowdingLevel.LOW, pub(12))
        t += CrowdingPolicy.HOLD_MS - 1
        assertEquals(CrowdingLevel.LOW, pub(12))
        t += 1
        assertEquals(CrowdingLevel.MEDIUM, pub(12))
        t += CrowdingPolicy.HOLD_MS * 2
        assertEquals(CrowdingLevel.MEDIUM, pub(8)) // 10 - 2: ainda medium
        t += CrowdingPolicy.HOLD_MS * 2
        assertEquals(CrowdingLevel.MEDIUM, pub(7)) // fora da margem: candidato LOW comeca
        t += CrowdingPolicy.HOLD_MS
        assertEquals(CrowdingLevel.LOW, pub(7))
    }

    @Test
    fun `dropping below k publishes null immediately and re-entry waits`() {
        settleLow()
        assertNull(pub(4))
        assertNull(pub(6))
        t += CrowdingPolicy.HOLD_MS
        assertEquals(CrowdingLevel.LOW, pub(6))
    }

    @Test
    fun `env floor is 5 and invalid falls back`() {
        fun k(v: String?) = CrowdingPolicy.minContributorsFromEnv { v }
        assertEquals(5, k(null))
        assertEquals(5, k("1"))
        assertEquals(5, k("abc"))
        assertEquals(8, k("8"))
    }

    @Test
    fun `route lists every station with null level when hidden and no counts in json`() =
        testApplication {
            val hub = LiveHub(clock = { now })
            repeat(5) { hub.update(key, "eph-$it", dwell(28_978.0)) }
            repeat(4) { hub.update(key, "eph-m$it", dwell(15_383.0)) }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/v1") {
                        crowdingRoutes(
                            hub,
                            { c -> if (c == key.corridor) stations else null },
                            smoother = StationCrowdingSmoother(holdMs = 0),
                        )
                    }
                }
            }
            val res = client.get("/v1/corridors/RAMAL_SANTA_CRUZ/stations/crowding?direction=CENTRAL_TO_SANTA_CRUZ")
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            val rows =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("stations")
                    .jsonArray
            assertEquals(
                listOf("madureira", "bangu"),
                rows.map {
                    it.jsonObject
                        .getValue("station_id")
                        .jsonPrimitive.content
                },
            )
            assertEquals("null", rows[0].jsonObject.getValue("level").toString())
            assertEquals(
                "low",
                rows[1]
                    .jsonObject
                    .getValue("level")
                    .jsonPrimitive.content,
            )
            assertEquals(setOf("station_id", "level"), rows[1].jsonObject.keys)
            assertFalse(body.contains("eph-"))
        }

    @Test
    fun `route rejects bad line and unknown network`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { route("/v1") { crowdingRoutes(LiveHub(clock = { now }), { null }) } }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/corridors/X/stations/crowding?direction=CENTRAL_TO_SANTA_CRUZ").status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/v1/corridors/RAMAL_SANTA_CRUZ/stations/crowding?direction=CENTRAL_TO_SANTA_CRUZ").status,
            )
        }
}
