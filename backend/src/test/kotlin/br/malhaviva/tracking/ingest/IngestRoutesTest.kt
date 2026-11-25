package br.malhaviva.tracking.ingest

import br.malhaviva.tracking.live.Direction
import br.malhaviva.tracking.live.LineKey
import br.malhaviva.tracking.live.LiveHub
import br.malhaviva.tracking.live.liveRoutes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IngestRoutesTest {
    private val now = 1_800_000_000_000L
    private val key = "chave-de-teste-com-32-bytes-ok!!".toByteArray()
    private val line = LineKey("RAMAL_SANTA_CRUZ", Direction.CENTRAL_TO_SANTA_CRUZ)
    private val hub = LiveHub(clock = { now })
    private val store = InMemoryTelemetryTokenStore()
    private val corridor = PolylineCorridor(listOf(-22.9 to -43.2, -22.9 to -43.1))
    private var nonceCount = 0

    private fun token(n: Int) = TelemetryToken("tok-$n", key, "epoch-$n", now + 600_000)

    private fun ApplicationTestBuilder.setup(limiters: IngestLimiters = IngestLimiters()) {
        (1..3).forEach { store.put(token(it)) }
        val ingestor = BatchIngestor(store, corridor, hub, limiters = limiters, clock = { now })
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                route("/v1") {
                    liveRoutes(hub)
                    ingestRoutes(ingestor)
                }
            }
        }
    }

    private fun sample(
        seq: Int,
        lon: Double,
        lat: Double = -22.9,
        accuracy: Double = 5.0,
        extra: String = "",
    ): String {
        val ts = Instant.ofEpochMilli(now - 20_000 + seq * 5_000L)
        return """{"seq":$seq,"ts":"$ts","lat":$lat,"lon":$lon,"accuracy_m":$accuracy,"speed_mps":10.0$extra}"""
    }

    private fun moving(n: Int = 4) = (1..n).map { sample(it, -43.19 + it * 0.0005) }

    private fun batch(
        samples: List<String>,
        epoch: String = "epoch-1",
        extraField: String = "",
    ): String {
        val list = samples.joinToString(",")
        return """{"anon_epoch_id":"$epoch","corridor":"RAMAL_SANTA_CRUZ","samples":[$list]$extraField}"""
    }

    private suspend fun ApplicationTestBuilder.post(
        body: String,
        tokenN: Int = 1,
        nonce: String = "nonce-%016d".format(nonceCount++),
        timestamp: Long = now,
        signature: String? = null,
    ): HttpResponse {
        val bytes = body.toByteArray()
        return client.post("/v1/telemetry/batches") {
            header(HttpHeaders.Authorization, "Bearer tok-$tokenN")
            header("X-Timestamp", timestamp.toString())
            header("X-Nonce", nonce)
            header("X-Signature", signature ?: BatchSecurity.sign(key, timestamp, nonce, bytes))
            setBody(bytes)
        }
    }

    private suspend fun HttpResponse.rejected(reason: String) =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["rejected"]!!
            .jsonObject[reason]
            ?.jsonPrimitive
            ?.int

    @Test
    fun `lote valido responde 202 com contagens`() =
        testApplication {
            setup()
            val res = post(batch(moving()))
            assertEquals(HttpStatusCode.Accepted, res.status)
            val json = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            assertEquals(4, json["accepted"]!!.jsonPrimitive.int)
            assertEquals(0, json["rejected"]!!.jsonObject.size)
            assertFalse(json.toString().contains("-43"), "resposta nao pode ecoar posicoes")
        }

    @Test
    fun `replay do nonce responde 409`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.Accepted, post(batch(moving()), nonce = "nonce-repetido-0001").status)
            assertEquals(HttpStatusCode.Conflict, post(batch(moving()), nonce = "nonce-repetido-0001").status)
        }

    @Test
    fun `assinatura invalida responde 401`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.Unauthorized, post(batch(moving()), signature = "assinatura-errada").status)
        }

    @Test
    fun `token desconhecido responde 401`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.Unauthorized, post(batch(moving()), tokenN = 99).status)
        }

    @Test
    fun `timestamp velho responde 400`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.BadRequest, post(batch(moving()), timestamp = now - 120_000).status)
        }

    @Test
    fun `campo desconhecido responde 400`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.BadRequest, post(batch(moving(), extraField = ""","extra":1""")).status)
            val unknownInSample = batch(listOf(sample(1, -43.19, extra = ""","user_id":"x"""")))
            assertEquals(HttpStatusCode.BadRequest, post(unknownInSample).status)
        }

    @Test
    fun `mais de 120 amostras responde 400`() =
        testApplication {
            setup()
            val many = (1..121).map { sample(it, -43.19) }
            assertEquals(HttpStatusCode.BadRequest, post(batch(many)).status)
        }

    @Test
    fun `limite por token responde 429`() =
        testApplication {
            setup(IngestLimiters(perToken = RateLimiter(2.0, 0.0)))
            assertEquals(HttpStatusCode.Accepted, post(batch(moving(1))).status)
            assertEquals(HttpStatusCode.Accepted, post(batch(listOf(sample(2, -43.189)))).status)
            val limited = post(batch(listOf(sample(3, -43.188))))
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("5", limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `limite por IP responde 429 mesmo com tokens diferentes`() =
        testApplication {
            setup(IngestLimiters(perIp = RateLimiter(1.0, 0.0)))
            assertEquals(HttpStatusCode.Accepted, post(batch(moving()), tokenN = 1).status)
            val other = post(batch(moving(), epoch = "epoch-2"), tokenN = 2)
            assertEquals(HttpStatusCode.TooManyRequests, other.status)
        }

    @Test
    fun `fora do corredor e mock entram em rejected`() =
        testApplication {
            setup()
            val samples =
                listOf(
                    sample(1, -43.19),
                    sample(2, -43.1895, lat = -22.895),
                    sample(3, -43.189, extra = ""","mock_location":true"""),
                    sample(4, -43.1885),
                )
            val res = post(batch(samples))
            assertEquals(HttpStatusCode.Accepted, res.status)
            assertEquals(1, res.rejected("OFF_CORRIDOR"))
            assertEquals(1, res.rejected("MOCK"))
        }

    @Test
    fun `amostra aceita aparece no snapshot do hub`() =
        testApplication {
            setup()
            (1..3).forEach { n ->
                val samples = (1..4).map { sample(it, -43.19 + it * 0.0005 + n * 0.0001) }
                assertEquals(HttpStatusCode.Accepted, post(batch(samples, "epoch-$n"), tokenN = n).status)
            }
            val train = hub.snapshot(line).single()
            assertEquals(br.malhaviva.tracking.live.ConfidenceBand.LOW, train.confidence)
        }
}
