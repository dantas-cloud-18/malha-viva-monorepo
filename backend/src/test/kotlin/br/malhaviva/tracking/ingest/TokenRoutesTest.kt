package br.malhaviva.tracking.ingest

import br.malhaviva.tracking.live.LiveHub
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenRoutesTest {
    private val now = 1_800_000_000_000L
    private val hub = LiveHub(clock = { now })
    private val store = InMemoryTelemetryTokenStore()
    private val corridor = PolylineCorridor(listOf(-22.9 to -43.2, -22.9 to -43.1))
    private val devBody = """{"platform":"android","integrity_token":"dev-integrity"}"""

    private fun ApplicationTestBuilder.setup(
        verifier: IntegrityVerifier = DevIntegrityVerifier,
        limiter: RateLimiter = tokenIssueLimiter(),
    ) {
        val ingestor = BatchIngestor(store, corridor, hub, clock = { now })
        application {
            install(ContentNegotiation) { json() }
            routing {
                route("/v1") {
                    ingestRoutes(ingestor)
                    tokenRoutes(TokenIssuer(store, { now }), verifier, limiter, { now })
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.issue(body: String = devBody): HttpResponse =
        client.post("/v1/telemetry/tokens") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `verificador dev emite token e lote assinado com a chave devolvida e aceito`() =
        testApplication {
            setup()
            val res = issue()
            assertEquals(HttpStatusCode.Created, res.status)
            val json = res.json()
            val key = Base64.getUrlDecoder().decode(json["signing_key"]!!.jsonPrimitive.content)
            assertEquals(32, key.size)
            assertEquals(
                Instant.ofEpochMilli(now + TOKEN_TTL_MS).toString(),
                json["expires_at"]!!.jsonPrimitive.content,
            )

            val epoch = json["anon_epoch_id"]!!.jsonPrimitive.content
            val ts = Instant.ofEpochMilli(now - 5_000)
            val sample = """{"seq":1,"ts":"$ts","lat":-22.9,"lon":-43.19,"accuracy_m":5.0,"speed_mps":10.0}"""
            val body = """{"anon_epoch_id":"$epoch","corridor":"RAMAL_SANTA_CRUZ","samples":[$sample]}""".toByteArray()
            val nonce = "nonce-0000000000001"
            val batch =
                client.post("/v1/telemetry/batches") {
                    header(HttpHeaders.Authorization, "Bearer ${json["token"]!!.jsonPrimitive.content}")
                    header("X-Timestamp", now.toString())
                    header("X-Nonce", nonce)
                    header("X-Signature", BatchSecurity.sign(key, now, nonce, body))
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Accepted, batch.status)
        }

    @Test
    fun `reject-all responde 401`() =
        testApplication {
            setup(RejectAllIntegrityVerifier)
            assertEquals(HttpStatusCode.Unauthorized, issue().status)
        }

    @Test
    fun `dev recusa token de integridade diferente`() =
        testApplication {
            setup()
            assertEquals(HttpStatusCode.Unauthorized, issue(devBody.replace("dev-integrity", "outro")).status)
        }

    @Test
    fun `plataforma ou corpo invalidos respondem 400`() =
        testApplication {
            setup(limiter = RateLimiter(100.0, 0.0))
            val bad =
                listOf(
                    """{"platform":"windows","integrity_token":"dev-integrity"}""",
                    """{"platform":"android"}""",
                    """{"integrity_token":"dev-integrity"}""",
                    """{"platform":"ios","integrity_token":"dev-integrity","extra":1}""",
                    "nao e json",
                    "",
                    """{"platform":"android","integrity_token":"${"x".repeat(5_000)}"}""",
                )
            bad.forEach { assertEquals(HttpStatusCode.BadRequest, issue(it).status, it.take(40)) }
        }

    @Test
    fun `limite por IP responde 429`() =
        testApplication {
            setup(limiter = RateLimiter(2.0, 0.0))
            assertEquals(HttpStatusCode.Created, issue().status)
            assertEquals(HttpStatusCode.Created, issue().status)
            val limited = issue()
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `dois tokens tem token e epoca diferentes e a resposta nao ecoa o token de integridade`() =
        testApplication {
            setup()
            val first = issue()
            val second = issue()
            val a = first.bodyAsText()
            val b = second.bodyAsText()
            assertFalse(a.contains("dev-integrity") || b.contains("dev-integrity"))
            val ja = Json.parseToJsonElement(a).jsonObject
            val jb = Json.parseToJsonElement(b).jsonObject
            listOf("token", "anon_epoch_id", "signing_key").forEach {
                assertNotEquals(ja[it]!!.jsonPrimitive.content, jb[it]!!.jsonPrimitive.content, it)
            }
            assertEquals(43, ja["token"]!!.jsonPrimitive.content.length)
        }

    @Test
    fun `verificador vem do ambiente e e recusa por padrao`() {
        assertTrue(integrityVerifierFromEnv { null } === RejectAllIntegrityVerifier)
        assertTrue(integrityVerifierFromEnv { "false" } === RejectAllIntegrityVerifier)
        assertTrue(integrityVerifierFromEnv { "true" } === DevIntegrityVerifier)
    }
}
