package br.malhaviva.tracking.live

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveRoutesTest {
    private val now = 1_800_000_000_000L
    private val key = LineKey("RAMAL_SANTA_CRUZ", Direction.SANTA_CRUZ_TO_CENTRAL)
    private val hub = LiveHub(clock = { now })
    private val url = "/v1/corridors/RAMAL_SANTA_CRUZ/trains?direction=SANTA_CRUZ_TO_CENTRAL"

    private fun ApplicationTestBuilder.setup() =
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { route("/v1") { liveRoutes(hub) } }
        }

    private fun feed() {
        hub.update(key, "eph-a", PassengerPosition(1000.0, 12.0, now))
        hub.update(key, "eph-b", PassengerPosition(1050.0, 14.0, now - 1_000))
        hub.update(key, "eph-c", PassengerPosition(1100.0, 13.0, now - 2_000))
    }

    @Test
    fun `polling devolve snapshot json`() =
        testApplication {
            setup()
            feed()
            val res = client.get(url)
            assertEquals(HttpStatusCode.OK, res.status)
            val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            assertEquals("SANTA_CRUZ_TO_CENTRAL", body["direction"]?.toString()?.trim('"'))
            val train = body["trains"]!!.jsonArray.single().jsonObject
            assertEquals("\"low\"", train["confidence"].toString())
            assertFalse(train.containsKey("signals"))
            assertEquals(1050.0, train["along_m"].toString().toDouble())
        }

    @Test
    fun `trem com k-1 ids distintos fica escondido no polling e no hub`() =
        testApplication {
            setup()
            hub.update(key, "eph-a", PassengerPosition(1000.0, 12.0, now))
            hub.update(key, "eph-b", PassengerPosition(1050.0, 14.0, now))
            val body = Json.parseToJsonElement(client.get(url).bodyAsText()).jsonObject
            assertEquals(0, body["trains"]!!.jsonArray.size)
            hub.update(key, "eph-c", PassengerPosition(1100.0, 13.0, now))
            val shown = Json.parseToJsonElement(client.get(url).bodyAsText()).jsonObject
            assertEquals(1, shown["trains"]!!.jsonArray.size)
        }

    @Test
    fun `mesma viagem enviando varios pontos conta uma vez so`() {
        repeat(10) { hub.update(key, "eph-a", PassengerPosition(1000.0 + it, 12.0, now)) }
        hub.update(key, "eph-b", PassengerPosition(1050.0, 14.0, now))
        assertEquals(0, hub.snapshot(key).size)
    }

    @Test
    fun `stream do hub so emite o trem quando atinge k`() =
        runBlocking {
            val emitted = mutableListOf<TrainChange>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { hub.changes.collect { emitted += it } }
            hub.update(key, "eph-a", PassengerPosition(1000.0, 12.0, now))
            hub.update(key, "eph-b", PassengerPosition(1050.0, 14.0, now))
            yield()
            assertEquals(0, emitted.size)
            hub.update(key, "eph-c", PassengerPosition(1100.0, 13.0, now))
            yield()
            assertEquals(1, emitted.size)
            job.cancel()
        }

    @Test
    fun `parametros invalidos retornam 400`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/corridors/RAMAL_SANTA_CRUZ/trains").status)
            val badCorridor = client.get("/v1/corridors/XXX/trains?direction=CENTRAL_TO_SANTA_CRUZ")
            assertEquals(HttpStatusCode.BadRequest, badCorridor.status)
        }

    @Test
    fun `TrainState nao expoe campos de usuario ou dispositivo`() {
        feed()
        val json = Json.encodeToJsonElement(TrainState.serializer(), hub.snapshot(key).single()) as JsonObject
        val expected = setOf("id", "along_m", "speed_mps", "confidence", "updated_at_ms")
        assertEquals(expected, json.keys)
        val text = json.toString()
        listOf("eph-a", "eph-b", "eph-c", "user", "device", "account").forEach { assertFalse(text.contains(it), it) }
    }
}
