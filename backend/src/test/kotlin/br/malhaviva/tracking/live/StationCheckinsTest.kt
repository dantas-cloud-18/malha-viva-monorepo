package br.malhaviva.tracking.live

import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StationCheckinsTest {
    private var now = 1_800_000_000_000L
    private val key = LineKey("RAMAL_SANTA_CRUZ", Direction.CENTRAL_TO_SANTA_CRUZ)
    private val bangu = StationRef("bangu", 28_978.0)
    private val checkins = StationCheckins(clock = { now })
    private val estimator = StationCrowdingEstimator()

    private fun level(
        dwellingTrips: Int = 0,
        stationCheckins: Int,
    ): CrowdingLevel? {
        val dwell = List(dwellingTrips) { PassengerPosition(bangu.alongM, 0.0, now) }
        repeat(stationCheckins) { checkins.add(key, bangu.id, UUID.randomUUID().toString()) }
        return estimator.estimate(dwell, listOf(bangu), checkins.counts(key)).single().level
    }

    @Test
    fun `k-1 check-ins hidden and k shown`() {
        assertNull(level(stationCheckins = 4))
        assertEquals(CrowdingLevel.LOW, level(stationCheckins = 1))
    }

    @Test
    fun `check-ins add to dwelling trips`() {
        assertEquals(CrowdingLevel.LOW, level(dwellingTrips = 3, stationCheckins = 2))
    }

    @Test
    fun `same id counts once and cancel removes it`() {
        val id = UUID.randomUUID().toString()
        repeat(3) { checkins.add(key, bangu.id, id) }
        assertEquals(1, checkins.counts(key)[bangu.id])
        checkins.cancel(key, bangu.id, id)
        assertEquals(0, checkins.counts(key)[bangu.id])
    }

    @Test
    fun `check-in expires after 10 minutes`() {
        repeat(5) { checkins.add(key, bangu.id, UUID.randomUUID().toString()) }
        now += CheckinPolicy.TTL_MS - 1
        assertEquals(5, checkins.counts(key)[bangu.id])
        now += 1
        assertEquals(0, checkins.counts(key)[bangu.id])
    }

    @Test
    fun `routes accept, validate and cancel`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/v1") {
                        checkinRoutes(checkins, { c ->
                            if (c ==
                                key.corridor
                            ) {
                                listOf(bangu)
                            } else {
                                null
                            }
                        })
                    }
                }
            }
            val base = "/v1/corridors/RAMAL_SANTA_CRUZ/stations"
            val id = UUID.randomUUID().toString()

            suspend fun post(
                station: String,
                body: String,
            ) = client
                .post("$base/$station/checkins") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status

            val ok = """{"direction":"CENTRAL_TO_SANTA_CRUZ","checkin_id":"$id"}"""
            assertEquals(HttpStatusCode.NoContent, post("bangu", ok))
            assertEquals(1, checkins.counts(key)["bangu"])
            assertEquals(HttpStatusCode.NotFound, post("nowhere", ok))
            assertEquals(
                HttpStatusCode.BadRequest,
                post("bangu", """{"direction":"CENTRAL_TO_SANTA_CRUZ","checkin_id":"x"}"""),
            )
            val cancel = client.delete("$base/bangu/checkins/$id?direction=CENTRAL_TO_SANTA_CRUZ")
            assertEquals(HttpStatusCode.NoContent, cancel.status)
            assertEquals("", cancel.bodyAsText())
            assertEquals(0, checkins.counts(key)["bangu"])
        }

    @Test
    fun `rate limited per ip`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { route("/v1") { checkinRoutes(checkins, { listOf(bangu) }) } }
            }
            val statuses =
                List(6) {
                    client
                        .post("/v1/corridors/RAMAL_SANTA_CRUZ/stations/bangu/checkins") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"direction":"CENTRAL_TO_SANTA_CRUZ","checkin_id":"${UUID.randomUUID()}"}""")
                        }.status
                }
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }
}
