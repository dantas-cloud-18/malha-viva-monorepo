package br.malhaviva.auth

import br.malhaviva.tracking.ingest.RateLimiter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRoutesTest {
    private fun app(
        t: TestAuth,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { route("/v1") { authRoutes(t.components) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
    ): HttpResponse =
        client.post("/v1$path") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

    private fun creds(
        handle: String,
        password: String = STRONG_PASSWORD,
        extra: String = "",
    ) = """{"handle":"$handle","password":"$password"$extra}"""

    private suspend fun ApplicationTestBuilder.register(
        handle: String,
        password: String = STRONG_PASSWORD,
    ) = post("/auth/accounts", creds(handle, password))

    private suspend fun ApplicationTestBuilder.login(
        handle: String,
        password: String = STRONG_PASSWORD,
        extra: String = "",
    ) = post("/auth/token", creds(handle, password, extra))

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.session(handle: String): JsonObject {
        assertEquals(HttpStatusCode.Created, register(handle).status)
        return login(handle, extra = ""","device_label":"Pixel"""").obj()
    }

    private suspend fun ApplicationTestBuilder.devices(access: String): HttpResponse =
        client.get("/v1/me/devices") { header(HttpHeaders.Authorization, "Bearer $access") }

    @Test
    fun `creates an account and rejects duplicates ignoring case`() =
        app(TestAuth()) {
            val created = register("@Maria_RJ")
            assertEquals(HttpStatusCode.Created, created.status)
            val body = created.obj()
            assertEquals("@Maria_RJ", body.str("handle"))
            assertFalse(
                body
                    .getValue("contacts_verified")
                    .jsonObject
                    .getValue("phone")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(HttpStatusCode.Conflict, register("@maria_rj").status)
        }

    @Test
    fun `create account validates handle and password policy`() =
        app(TestAuth()) {
            assertEquals(HttpStatusCode.BadRequest, register("sem-arroba").status)
            assertEquals(HttpStatusCode.BadRequest, post("/auth/accounts", "{").status)
            val pwned = register("@joao_01", PWNED_BUT_STRONG)
            assertEquals(HttpStatusCode.BadRequest, pwned.status)
            assertEquals("password_pwned", pwned.obj().str("detail"))
            assertEquals("password_weak", register("@joao_01", "password1234").obj().str("detail"))
            assertEquals("password_too_short", register("@joao_01", "abc").obj().str("detail"))
        }

    @Test
    fun `login succeeds and access token has no personal data`() =
        app(TestAuth()) {
            register("@Ana_Trem")
            val ok = login("@ana_trem", extra = ""","device_label":"Meu Pixel"""")
            assertEquals(HttpStatusCode.OK, ok.status)
            val tokens = ok.obj()
            assertEquals(
                900,
                tokens
                    .getValue("expires_in")
                    .jsonPrimitive.content
                    .toInt(),
            )
            val payloadJson = String(Base64.getUrlDecoder().decode(tokens.str("access_token").split('.')[1]))
            val claims = Json.parseToJsonElement(payloadJson).jsonObject
            assertEquals(setOf("sub", "iat", "exp"), claims.keys)
            assertFalse(payloadJson.contains("ana", ignoreCase = true))
        }

    @Test
    fun `wrong password and unknown handle give the identical 401 and both run one hash verification`() {
        val t = TestAuth()
        app(t) {
            register("@existente")
            val before = t.hasher.verifyCalls
            val wrong = login("@existente", "outra senha qualquer forte 77")
            assertEquals(1, t.hasher.verifyCalls - before)
            val unknown = login("@fantasma", "outra senha qualquer forte 77")
            assertEquals(2, t.hasher.verifyCalls - before)
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
        }
    }

    @Test
    fun `rehashes on login when parameters changed`() {
        val t = TestAuth()
        val old = PasswordHasher(TEST_PEPPER, Argon2Params(32, 1, 1)).hash(STRONG_PASSWORD)
        t.accounts.create("@antigo", old)
        app(t) {
            assertEquals(HttpStatusCode.OK, login("@antigo").status)
        }
        assertFalse(t.hasher.needsRehash(assertNotNull(t.accounts.findByHandle("@antigo")).passwordHash))
    }

    @Test
    fun `refresh rotates and reuse of the old token returns 401 and kills the new one`() =
        app(TestAuth()) {
            val first = session("@rot_user")
            val r1 = first.str("refresh_token")
            val second = post("/auth/refresh", """{"refresh_token":"$r1"}""")
            assertEquals(HttpStatusCode.OK, second.status)
            val r2 = second.obj().str("refresh_token")
            assertEquals(HttpStatusCode.Unauthorized, post("/auth/refresh", """{"refresh_token":"$r1"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, post("/auth/refresh", """{"refresh_token":"$r2"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, post("/auth/refresh", """{"x":1}""").status)
        }

    @Test
    fun `logout revokes the session and always answers 204`() =
        app(TestAuth()) {
            val r = session("@sai_user").str("refresh_token")
            assertEquals(HttpStatusCode.NoContent, post("/auth/logout", """{"refresh_token":"$r"}""").status)
            assertEquals(HttpStatusCode.NoContent, post("/auth/logout", """{"refresh_token":"$r"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, post("/auth/refresh", """{"refresh_token":"$r"}""").status)
        }

    @Test
    fun `devices list requires a valid token and shows only own devices`() =
        app(TestAuth()) {
            val a = session("@dev_a")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me/devices").status)
            assertEquals(HttpStatusCode.Unauthorized, devices("lixo").status)
            val list = devices(a.str("access_token"))
            assertEquals(HttpStatusCode.OK, list.status)
            val items: JsonArray = Json.parseToJsonElement(list.bodyAsText()).jsonArray
            assertEquals(1, items.size)
            assertEquals("Pixel", items[0].jsonObject.str("label"))
        }

    @Test
    fun `account A cannot revoke account B device (IDOR)`() =
        app(TestAuth()) {
            val a = session("@idor_a")
            val b = session("@idor_b")
            val bDevice =
                Json
                    .parseToJsonElement(
                        devices(b.str("access_token")).bodyAsText(),
                    ).jsonArray[0]
                    .jsonObject
                    .str("id")
            val attack =
                client.delete(
                    "/v1/me/devices/$bDevice",
                ) { header(HttpHeaders.Authorization, "Bearer ${a.str("access_token")}") }
            assertEquals(HttpStatusCode.NotFound, attack.status)
            assertEquals(1, Json.parseToJsonElement(devices(b.str("access_token")).bodyAsText()).jsonArray.size)
            assertEquals(
                HttpStatusCode.OK,
                post("/auth/refresh", """{"refresh_token":"${b.str("refresh_token")}"}""").status,
            )
            val own =
                client.delete(
                    "/v1/me/devices/$bDevice",
                ) { header(HttpHeaders.Authorization, "Bearer ${b.str("access_token")}") }
            assertEquals(HttpStatusCode.NoContent, own.status)
            assertEquals(0, Json.parseToJsonElement(devices(b.str("access_token")).bodyAsText()).jsonArray.size)
            val missing =
                client.delete(
                    "/v1/me/devices/not-a-uuid",
                ) { header(HttpHeaders.Authorization, "Bearer ${a.str("access_token")}") }
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    @Test
    fun `revoking a device kills its refresh token`() =
        app(TestAuth()) {
            val a = session("@revoga")
            val id =
                Json
                    .parseToJsonElement(
                        devices(a.str("access_token")).bodyAsText(),
                    ).jsonArray[0]
                    .jsonObject
                    .str("id")
            client.delete("/v1/me/devices/$id") { header(HttpHeaders.Authorization, "Bearer ${a.str("access_token")}") }
            assertEquals(
                HttpStatusCode.Unauthorized,
                post("/auth/refresh", """{"refresh_token":"${a.str("refresh_token")}"}""").status,
            )
        }

    @Test
    fun `per IP rate limit answers 429 with Retry-After`() =
        app(TestAuth(limiters = AuthLimiters(ip = RateLimiter(2.0, 0.0001)))) {
            assertEquals(HttpStatusCode.Unauthorized, login("@a_b_c").status)
            assertEquals(HttpStatusCode.Unauthorized, login("@a_b_c").status)
            val limited = login("@a_b_c")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `per handle rate limit answers 429`() =
        app(TestAuth(limiters = AuthLimiters(handle = RateLimiter(2.0, 0.0001)))) {
            login("@alvo_x")
            login("@alvo_x")
            assertEquals(HttpStatusCode.TooManyRequests, login("@alvo_x").status)
            assertEquals(HttpStatusCode.Unauthorized, login("@outro_y").status)
        }

    @Test
    fun `progressive delay blocks quick retries after repeated failures`() {
        val t = TestAuth()
        app(t) {
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, login("@lento_x").status) }
            val blocked = login("@lento_x")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertTrue(blocked.headers[HttpHeaders.RetryAfter]!!.toLong() >= 1)
            t.now += 60_000
            assertEquals(HttpStatusCode.Unauthorized, login("@lento_x").status)
        }
    }

    @Test
    fun `captcha is required after 5 failures`() {
        val t = TestAuth()
        app(t) {
            register("@captcha_user")
            repeat(CAPTCHA_AFTER_FAILURES) {
                t.now += 10_000
                assertEquals(HttpStatusCode.Unauthorized, login("@captcha_user", "senha errada numero $it").status)
            }
            t.now += 60_000
            val needed = login("@captcha_user")
            assertEquals(HttpStatusCode.Forbidden, needed.status)
            assertEquals("captcha_required", needed.obj().str("detail"))
            val wrongWithCaptcha = login("@captcha_user", "senha errada de novo", ""","captcha_token":"ok"""")
            assertEquals(HttpStatusCode.Unauthorized, wrongWithCaptcha.status)
            t.now += 600_000
            assertEquals(HttpStatusCode.OK, login("@captcha_user", extra = ""","captcha_token":"ok"""").status)
        }
    }

    @Test
    fun `many distinct handles from one IP triggers a block without PII in logs`() {
        val t = TestAuth(limiters = AuthLimiters(ip = RateLimiter(1000.0, 1.0)))
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger("br.malhaviva.auth") as Logger
        root.addAppender(appender)
        try {
            app(t) {
                register("@legitimo")
                repeat(25) { login("@alvo_$it") }
                val blocked = login("@legitimo")
                assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            }
        } finally {
            root.detachAppender(appender)
        }
        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(logs.contains("Credential stuffing"), logs)
        listOf(
            "alvo_",
            "legitimo",
            "127.0.0.1",
            "localhost",
            STRONG_PASSWORD,
        ).forEach { assertFalse(logs.contains(it), it) }
    }
}
