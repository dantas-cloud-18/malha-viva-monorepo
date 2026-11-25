package br.malhaviva.auth

import br.malhaviva.auth.recovery.Channel
import br.malhaviva.auth.recovery.FAKE_PHONE
import br.malhaviva.auth.recovery.NEW_PASSWORD
import br.malhaviva.auth.recovery.RecoveryCodes
import br.malhaviva.auth.recovery.RecoveryCompleteResult
import br.malhaviva.auth.recovery.TestRecovery
import br.malhaviva.auth.recovery.Totp
import br.malhaviva.tracking.ingest.RateLimiter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val STEP_MS = 30_000L
private const val ADMIN_ENROLL_CODE = "codigo-admin-de-teste-0123456789abcdef"
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** Base32 sem padding, so para gerar codigos nos testes. */
private fun base32Decode(s: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (ch in s) {
        buffer = (buffer shl 5) or BASE32.indexOf(ch)
        bits += 5
        if (bits >= 8) {
            out.write((buffer shr (bits - 8)) and 0xff)
            bits -= 8
        }
    }
    return out.toByteArray()
}

class MfaRoutesTest {
    private fun app(
        c: AuthComponents,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { route("/v1") { authRoutes(c) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        bearer: String? = null,
    ): HttpResponse =
        client.post("/v1$path") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.get(
        path: String,
        bearer: String,
    ): HttpResponse = client.get("/v1$path") { header(HttpHeaders.Authorization, "Bearer $bearer") }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.login(
        handle: String,
        password: String = STRONG_PASSWORD,
    ) = post("/auth/token", """{"handle":"$handle","password":"$password","device_label":"Pixel"}""")

    private suspend fun ApplicationTestBuilder.mfa(
        token: String,
        code: String,
    ) = post("/auth/login/mfa", """{"mfa_token":"$token","code":"$code"}""")

    private suspend fun ApplicationTestBuilder.registered(
        t: TestAuth,
        handle: String,
        role: String = ROLE_USER,
    ) {
        val created = post("/auth/accounts", """{"handle":"$handle","password":"$STRONG_PASSWORD"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = t.accounts.findByHandle(handle)!!.id
        t.accounts.setRole(id, role)
        // passo manual do operador: o codigo de enrolamento sai junto com a promocao
        if (role == ROLE_ADMIN) t.accounts.setAdminEnrollCode(id, ADMIN_ENROLL_CODE)
    }

    /** Enrola e confirma com o codigo do passo atual; devolve o segredo. */
    private suspend fun ApplicationTestBuilder.enable(
        t: TestAuth,
        access: String,
        enrollmentCode: String? = null,
    ): ByteArray {
        val started = post("/me/mfa/totp", enrollmentCode?.let { """{"enrollment_code":"$it"}""" } ?: "{}", access)
        assertEquals(HttpStatusCode.Created, started.status)
        val body = started.obj()
        assertTrue(body.str("otpauth_uri").startsWith("otpauth://totp/"))
        val secret = base32Decode(body.str("secret"))
        val confirmed = post("/me/mfa/totp/confirm", """{"code":"${code(secret, t.now)}"}""", access)
        assertEquals(HttpStatusCode.NoContent, confirmed.status)
        return secret
    }

    private fun code(
        secret: ByteArray,
        nowMs: Long,
    ) = Totp.generate(secret, Totp.step(nowMs))

    private fun amr(access: String): List<String> {
        val payload = String(Base64.getUrlDecoder().decode(access.split('.')[1]))
        return Json
            .parseToJsonElement(payload)
            .jsonObject
            .getValue("amr")
            .jsonArray
            .map { it.jsonPrimitive.content }
    }

    @Test
    fun `enroll, confirm and disable TOTP for a user`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@maria")
            val access = login("@maria").obj().str("access_token")
            val first = post("/me/mfa/totp", "{}", access)
            assertEquals(HttpStatusCode.Created, first.status)
            val secret = base32Decode(first.obj().str("secret"))
            val wrong = post("/me/mfa/totp/confirm", """{"code":"000000"}""", access)
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals(
                HttpStatusCode.NoContent,
                post("/me/mfa/totp/confirm", """{"code":"${code(secret, t.now)}"}""", access).status,
            )
            assertEquals(HttpStatusCode.Conflict, post("/me/mfa/totp", "{}", access).status)

            t.now += STEP_MS
            val next = code(secret, t.now)
            val badPassword = post("/me/mfa/totp/disable", """{"password":"errada","code":"$next"}""", access)
            assertEquals(HttpStatusCode.Forbidden, badPassword.status)
            val disabled = post("/me/mfa/totp/disable", """{"password":"$STRONG_PASSWORD","code":"$next"}""", access)
            assertEquals(HttpStatusCode.NoContent, disabled.status)
            assertNotNull(login("@maria").obj()["refresh_token"], "sem TOTP o login volta a emitir tokens direto")
        }
    }

    @Test
    fun `login with TOTP needs the code, rejects wrong code, token reuse and same-step replay`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@joao")
            val secret = enable(t, login("@joao").obj().str("access_token"))

            val challenge = login("@joao").obj()
            assertEquals("true", challenge.str("mfa_required"))
            assertFalse(challenge.containsKey("access_token"))
            val token = challenge.str("mfa_token")
            assertEquals(HttpStatusCode.Unauthorized, mfa(token, code(secret, t.now)).status, "passo da confirmacao")

            t.now += STEP_MS
            val good = code(secret, t.now)
            assertEquals(HttpStatusCode.Unauthorized, mfa(token, "123456").status)
            val ok = mfa(token, good)
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(listOf(AMR_PASSWORD, AMR_OTP), amr(ok.obj().str("access_token")))
            assertEquals(HttpStatusCode.Unauthorized, mfa(token, good).status, "mfa_token e de uso unico")

            val again = login("@joao").obj().str("mfa_token")
            val replay = mfa(again, good)
            assertEquals(HttpStatusCode.Unauthorized, replay.status, "mesmo passo nao vale duas vezes")
            assertEquals("invalid_mfa", replay.obj().str("detail"))
        }
    }

    @Test
    fun `user without TOTP gets tokens with pwd only`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@ana")
            assertEquals(listOf(AMR_PASSWORD), amr(login("@ana").obj().str("access_token")))
        }
    }

    @Test
    fun `mfa token expires after five minutes`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@pedro")
            val secret = enable(t, login("@pedro").obj().str("access_token"))
            t.now += STEP_MS
            val token = login("@pedro").obj().str("mfa_token")
            t.now += 5 * 60_000L + 1
            assertEquals(HttpStatusCode.Unauthorized, mfa(token, code(secret, t.now)).status)
        }
    }

    @Test
    fun `wrong MFA codes count in the login guard`() {
        val t = TestAuth(limiters = AuthLimiters(ip = RateLimiter(1000.0, 1.0), handle = RateLimiter(1000.0, 1.0)))
        app(t.components) {
            registered(t, "@lucas")
            enable(t, login("@lucas").obj().str("access_token"))
            t.now += STEP_MS
            val token = login("@lucas").obj().str("mfa_token")
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, mfa(token, "000000").status) }
            val blocked = mfa(token, "000000")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertNotNull(blocked.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `admin without TOTP only gets an enroll-only token, then needs the code to reach admin routes`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@chefe", ROLE_ADMIN)
            val first = login("@chefe").obj()
            assertEquals("true", first.str("mfa_enrollment_required"))
            assertFalse(first.containsKey("refresh_token"))
            val restricted = first.str("access_token")
            assertEquals(HttpStatusCode.Unauthorized, get("/me/devices", restricted).status)
            assertEquals(HttpStatusCode.Unauthorized, get("/admin/ping", restricted).status)

            val secret = enable(t, restricted, ADMIN_ENROLL_CODE)
            t.now += STEP_MS
            val token = login("@chefe").obj().str("mfa_token")
            val tokens = mfa(token, code(secret, t.now)).obj()
            assertEquals(HttpStatusCode.OK, get("/admin/ping", tokens.str("access_token")).status)

            val refreshed = post("/auth/refresh", """{"refresh_token":"${tokens.str("refresh_token")}"}""").obj()
            val afterRefresh = get("/admin/ping", refreshed.str("access_token"))
            assertEquals(HttpStatusCode.OK, afterRefresh.status, "refresh mantem amr")

            t.now += STEP_MS
            val disable =
                post(
                    "/me/mfa/totp/disable",
                    """{"password":"$STRONG_PASSWORD","code":"${code(secret, t.now)}"}""",
                    tokens.str("access_token"),
                )
            assertEquals(HttpStatusCode.Forbidden, disable.status)
            assertEquals("admin_mfa_mandatory", disable.obj().str("detail"))
        }
    }

    @Test
    fun `user cannot reach admin route even with TOTP`() {
        val t = TestAuth()
        app(t.components) {
            registered(t, "@comum")
            val plain = login("@comum").obj().str("access_token")
            assertEquals(HttpStatusCode.Forbidden, get("/admin/ping", plain).status)
            val secret = enable(t, plain)
            t.now += STEP_MS
            val token = login("@comum").obj().str("mfa_token")
            val withOtp = mfa(token, code(secret, t.now)).obj().str("access_token")
            val r = get("/admin/ping", withOtp)
            assertEquals(HttpStatusCode.Forbidden, r.status)
            assertEquals("admin_mfa_required", r.obj().str("detail"))
        }
    }

    @Test
    fun `password recovery does not bypass admin TOTP`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        runBlocking {
            assertEquals(RegisterResult.Created, t.components.auth.register("@admin_rec", STRONG_PASSWORD))
            val id = t.accounts.findByHandle("@admin_rec")!!.id
            t.accounts.setRole(id, ROLE_ADMIN)
            t.accounts.setAdminEnrollCode(id, ADMIN_ENROLL_CODE)
            r.contacts.setVerified(id, Channel.PHONE, FAKE_PHONE, Instant.EPOCH)
            val started = assertIs<TotpEnrollResult.Started>(t.mfa.enroll(id, ADMIN_ENROLL_CODE))
            val secret = base32Decode(started.enrollment.secretBase32)
            assertTrue(t.mfa.confirm(id, code(secret, t.now)))

            r.service.request("10.0.0.9", "@admin_rec", null)
            val otp = r.fake.lastCode(Channel.PHONE)
            val done = r.service.complete("10.0.0.9", "@admin_rec", RecoveryCodes(phone = otp), NEW_PASSWORD)
            assertEquals(RecoveryCompleteResult.Done, done)

            val after = t.components.auth.login("10.0.0.9", "@admin_rec", NEW_PASSWORD, null, null)
            assertIs<LoginResult.MfaRequired>(after)
        }
    }

    @Test
    fun `secret and codes never reach the logs`() {
        val t = TestAuth()
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger("br.malhaviva") as Logger
        root.addAppender(appender)
        val seen = mutableListOf<String>()
        try {
            app(t.components) {
                registered(t, "@sigilo")
                val access = login("@sigilo").obj().str("access_token")
                val started = post("/me/mfa/totp", "{}", access).obj().str("secret")
                seen += started
                val secret = base32Decode(started)
                seen += code(secret, t.now)
                post("/me/mfa/totp/confirm", """{"code":"${seen.last()}"}""", access)
                t.now += STEP_MS
                val token = login("@sigilo").obj().str("mfa_token")
                seen += token
                seen += code(secret, t.now)
                mfa(token, "999999")
                mfa(token, seen.last())
            }
        } finally {
            root.detachAppender(appender)
        }
        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        (seen + "999999").forEach { assertFalse(logs.contains(it), "valor sensivel no log") }
    }
}
