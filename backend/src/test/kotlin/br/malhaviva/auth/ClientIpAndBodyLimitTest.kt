package br.malhaviva.auth

import br.malhaviva.auth.recovery.RecoveryComponents
import br.malhaviva.auth.recovery.RecoveryLimits
import br.malhaviva.auth.recovery.TestRecovery
import br.malhaviva.installForwardedHeaders
import br.malhaviva.platform.clientIp
import br.malhaviva.tracking.ingest.RateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val MAX_BODY = 8 * 1024
private const val REAL_CLIENT = "198.51.100.1"

class ClientIpAndBodyLimitTest {
    private fun roomy() = AuthLimiters(ip = RateLimiter(1000.0, 1.0), handle = RateLimiter(1000.0, 1.0))

    private fun json(
        passwordLength: Int,
        handle: String = "@zz_zz",
    ) = """{"handle":"$handle","password":"${"a".repeat(passwordLength)}"}"""

    /** Corpo JSON valido com exatamente [size] bytes (a senha gigante responde 400, nao 413). */
    private fun exactly(size: Int): String = json(size - json(0).length)

    @Test
    fun `client ip is the last X-Forwarded-For entry and a forged first entry changes nothing`() =
        testApplication {
            application {
                installForwardedHeaders()
                routing { get("/ip") { call.respondText(call.clientIp()) } }
            }
            val forged =
                client.get("/ip") { header("X-Forwarded-For", "6.6.6.6, 7.7.7.7, $REAL_CLIENT") }.bodyAsText()
            assertEquals(REAL_CLIENT, forged)
            assertEquals("203.0.113.9", client.get("/ip") { header("X-Forwarded-For", "203.0.113.9") }.bodyAsText())
        }

    @Test
    fun `per IP limit follows the real client, not the forged header or the shared proxy address`() {
        val t = TestAuth(limiters = AuthLimiters(ip = RateLimiter(2.0, 0.0001)))
        authApp(t.components) {
            val body = """{"handle":"@nao_existe","password":"$STRONG_PASSWORD"}"""
            val real = "6.6.6.6, $REAL_CLIENT"
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, httpPost("/auth/token", body, ip = real).status) }
            assertEquals(HttpStatusCode.TooManyRequests, httpPost("/auth/token", body, ip = real).status)
            // outro item forjado na frente, mesmo cliente real: continua no mesmo balde
            val forgedAgain = httpPost("/auth/token", body, ip = "8.8.8.8, $REAL_CLIENT")
            assertEquals(HttpStatusCode.TooManyRequests, forgedAgain.status)
            // outro cliente real atras do mesmo ALB tem balde proprio
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/auth/token", body, ip = "198.51.100.2").status)
        }
    }

    @Test
    fun `oversized bodies are rejected with 413 before parsing, even when they are not valid json`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            val junk = "{" + "x".repeat(MAX_BODY + 10)
            listOf("/auth/accounts", "/auth/token", "/auth/refresh", "/auth/logout", "/auth/login/mfa").forEach {
                assertEquals(HttpStatusCode.PayloadTooLarge, httpPost(it, junk).status, it)
            }
            listOf("/auth/recovery", "/auth/recovery/complete").forEach {
                assertEquals(HttpStatusCode.PayloadTooLarge, httpPost(it, junk).status, it)
            }
        }
    }

    @Test
    fun `authenticated routes also cap the body at 413`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            val access = signupAndLogin("@dono_x").text("access_token")
            val junk = "{" + "x".repeat(MAX_BODY + 10)
            listOf(
                "/me/contacts",
                "/me/contacts/confirm",
                "/me/mfa/totp",
                "/me/mfa/totp/confirm",
                "/me/mfa/totp/disable",
            ).forEach { assertEquals(HttpStatusCode.PayloadTooLarge, httpPost(it, junk, access).status, it) }
        }
    }

    @Test
    fun `the cap is exactly 8 KiB and also holds without Content-Length (chunked)`() {
        val t = TestAuth(limiters = roomy())
        authApp(t.components) {
            assertEquals(MAX_BODY, exactly(MAX_BODY).length)
            // no limite passa do leitor e cai na validacao (senha longa demais: 400)
            assertEquals(HttpStatusCode.BadRequest, httpPost("/auth/token", exactly(MAX_BODY)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge, httpPost("/auth/token", exactly(MAX_BODY + 1)).status)
            val chunked =
                client.post("/v1/auth/token") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ByteReadChannel(exactly(MAX_BODY + 1).toByteArray()))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        }
    }

    @Test
    fun `ip rate limits are consulted before the body is read`() {
        val t = TestAuth(limiters = AuthLimiters(ip = RateLimiter(1.0, 0.0001), signup = RateLimiter(1.0, 0.0001)))
        val r = TestRecovery(t, limits = RecoveryLimits(requestIp = RateLimiter(1.0, 0.0001)))
        authApp(r.components) {
            val junk = "{" + "x".repeat(MAX_BODY + 10)
            listOf("/auth/token", "/auth/accounts", "/auth/recovery").forEach { path ->
                assertEquals(HttpStatusCode.BadRequest, httpPost(path, "{}").status, "$path consome a unica ficha")
                val limited = httpPost(path, junk)
                // 429 e nao 413: o limite por IP vem antes de qualquer leitura do corpo
                assertEquals(HttpStatusCode.TooManyRequests, limited.status, path)
                assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            }
        }
    }

    @Test
    fun `account rate limit on contacts is consulted before the body is read`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        val limited =
            AuthComponents(
                r.auth.components.auth,
                r.auth.sessions,
                r.auth.access,
                r.auth.components.limiters,
                { r.auth.now },
                RecoveryComponents(r.service, r.contactService, RateLimiter(1.0, 0.0001)),
            )
        authApp(limited) {
            val access = signupAndLogin("@dono_y").text("access_token")
            assertEquals(HttpStatusCode.BadRequest, httpPost("/me/contacts", "{}", access).status)
            val junk = "{" + "x".repeat(MAX_BODY + 10)
            assertEquals(HttpStatusCode.TooManyRequests, httpPost("/me/contacts", junk, access).status)
        }
    }

    @Test
    fun `login limiter is keyed by ip and handle so another ip cannot lock the owner out`() {
        val t = TestAuth(limiters = AuthLimiters(ip = RateLimiter(1000.0, 1.0), handle = RateLimiter(2.0, 0.0001)))
        authApp(t.components) {
            signupAndLogin("@vitima_1", ip = "198.51.100.50")
            val wrong = """{"handle":"@vitima_1","password":"senha errada qualquer 123"}"""
            repeat(2) { httpPost("/auth/token", wrong, ip = "192.0.2.66") }
            assertEquals(HttpStatusCode.TooManyRequests, httpPost("/auth/token", wrong, ip = "192.0.2.66").status)
            val owner = """{"handle":"@vitima_1","password":"$STRONG_PASSWORD"}"""
            assertEquals(HttpStatusCode.OK, httpPost("/auth/token", owner, ip = "198.51.100.50").status)
        }
    }

    @Test
    fun `invalid handle formats answer 401 and create no per key state`() {
        val t = TestAuth(limiters = roomy())
        authApp(t.components) {
            listOf("sem-arroba", "@a", "@com espaco", "@" + "x".repeat(30)).forEach {
                val body = """{"handle":"$it","password":"$STRONG_PASSWORD"}"""
                assertEquals(HttpStatusCode.Unauthorized, httpPost("/auth/token", body).status, it)
            }
            assertEquals(0L, t.guard.trackedKeys())
            assertEquals(
                0L,
                t.components.limiters.handle
                    .trackedKeys(),
            )
        }
    }

    @Test
    fun `recovery limiter is keyed by ip and handle so another ip is not throttled for the same handle`() {
        val t = TestAuth(limiters = roomy())
        val r = TestRecovery(t, limits = RecoveryLimits(requestHandle = RateLimiter(2.0, 0.0001)))
        authApp(r.components) {
            val body = """{"handle":"@vitima_2"}"""
            repeat(
                2,
            ) { assertEquals(HttpStatusCode.Accepted, httpPost("/auth/recovery", body, ip = "192.0.2.66").status) }
            assertEquals(HttpStatusCode.TooManyRequests, httpPost("/auth/recovery", body, ip = "192.0.2.66").status)
            assertEquals(HttpStatusCode.Accepted, httpPost("/auth/recovery", body, ip = "198.51.100.50").status)
        }
    }
}
