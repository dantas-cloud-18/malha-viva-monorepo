package br.malhaviva.auth

import br.malhaviva.auth.recovery.TestRecovery
import br.malhaviva.tracking.ingest.RateLimiter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val NOW_MS = 1_800_000_000_000L
private val enc = Base64.getUrlEncoder().withoutPadding()
private const val ADMIN_CODE = "codigo-admin-de-teste-fedcba9876543210"

/** Forja um JWT HS256 com o segredo de teste, para provar que `iss`, `aud` e `exp` sao verificados. */
private fun forge(payload: String): String {
    val head = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
    val body = enc.encodeToString(payload.toByteArray())
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(TEST_JWT_SECRET, "HmacSHA256")) }
    return "$head.$body.${enc.encodeToString(mac.doFinal("$head.$body".toByteArray()))}"
}

class SessionHardeningTest {
    private val access = AccessTokenService(TEST_JWT_SECRET)
    private val id = UUID.randomUUID()
    private val sid = UUID.randomUUID()
    private val iat = NOW_MS / 1000

    private fun claims(
        iss: String? = TOKEN_ISSUER,
        aud: String? = TOKEN_AUDIENCE,
        exp: Long = iat + 900,
        sid: String? = null,
    ): String {
        val parts =
            listOfNotNull(
                iss?.let { """"iss":"$it"""" },
                aud?.let { """"aud":"$it"""" },
                """"sub":"$id","iat":$iat,"exp":$exp,"amr":["pwd"]""",
                sid?.let { """"sid":"$it"""" },
            )
        return "{${parts.joinToString(",")}}"
    }

    @Test
    fun `access token carries issuer, audience and the session id`() {
        val token = access.issue(id, NOW_MS, sessionId = sid)
        val principal = assertNotNull(access.verifyPrincipal(token, NOW_MS))
        assertEquals(sid, principal.sessionId)
        assertEquals(id, principal.accountId)
    }

    @Test
    fun `tokens with the wrong or missing issuer or audience are refused`() {
        assertNotNull(access.verifyPrincipal(forge(claims()), NOW_MS), "controle: forjado com os valores certos passa")
        assertNull(access.verifyPrincipal(forge(claims(iss = "outro-emissor")), NOW_MS))
        assertNull(access.verifyPrincipal(forge(claims(aud = "outro-publico")), NOW_MS))
        assertNull(access.verifyPrincipal(forge(claims(iss = null)), NOW_MS))
        assertNull(access.verifyPrincipal(forge(claims(aud = null)), NOW_MS))
        assertNull(access.verifyPrincipal(forge(claims(sid = "nao-e-uuid")), NOW_MS))
        assertNull(access.verifyPrincipal(forge(claims(exp = iat - 1)), NOW_MS), "vencido")
    }

    @Test
    fun `sensitive routes refuse an access token whose session was revoked, other routes wait for expiry`() {
        val r = TestRecovery(TestAuth(limiters = AuthLimiters(ip = RateLimiter(1000.0, 1.0))))
        authApp(r.components) {
            val tokens = signupAndLogin("@sessao_1")
            val bearer = tokens.text("access_token")
            val deviceId = httpGet("/me/devices", bearer).bodyAsArray().first().text("id")
            assertEquals(HttpStatusCode.OK, httpGet("/me/contacts", bearer).status)
            assertEquals(HttpStatusCode.OK, httpGet("/me/devices", bearer).status)

            // logout revoga a familia: o access de 15 min ainda esta "valido" por assinatura e validade
            assertEquals(
                HttpStatusCode.NoContent,
                httpPost("/auth/logout", """{"refresh_token":"${tokens.text("refresh_token")}"}""").status,
            )
            assertEquals(HttpStatusCode.Unauthorized, httpGet("/me/contacts", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/me/contacts", "{}", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/me/contacts/confirm", "{}", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/me/mfa/totp", "{}", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/me/mfa/totp/confirm", "{}", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/me/mfa/totp/disable", "{}", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpGet("/admin/ping", bearer).status)
            assertEquals(HttpStatusCode.Unauthorized, httpDelete("/me/devices/$deviceId", bearer).status)
            assertEquals(HttpStatusCode.OK, httpGet("/me/devices", bearer).status, "rota comum segue ate vencer")
        }
    }

    @Test
    fun `revoking every session after a password reset also kills the in-flight access on sensitive routes`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        authApp(r.components) {
            val bearer = signupAndLogin("@sessao_2").text("access_token")
            assertEquals(HttpStatusCode.OK, httpGet("/me/contacts", bearer).status)
            t.sessions.revokeAll(t.accounts.findByHandle("@sessao_2")!!.id)
            assertEquals(HttpStatusCode.Unauthorized, httpGet("/me/contacts", bearer).status)
        }
    }

    @Test
    fun `an access token without a session id is refused on sensitive routes`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        authApp(r.components) {
            signupAndLogin("@sessao_3")
            val noSid = t.access.issue(t.accounts.findByHandle("@sessao_3")!!.id, t.now)
            assertEquals(HttpStatusCode.Unauthorized, httpGet("/me/contacts", noSid).status)
        }
    }

    private fun registeredAdmin(
        t: TestAuth,
        withCode: Boolean = true,
    ) {
        runBlocking { t.components.auth.register("@chefe_x", STRONG_PASSWORD) }
        val id = t.accounts.findByHandle("@chefe_x")!!.id
        t.accounts.setRole(id, ROLE_ADMIN)
        if (withCode) t.accounts.setAdminEnrollCode(id, ADMIN_CODE)
    }

    private suspend fun ApplicationTestBuilder.restrictedToken(): String =
        httpPost("/auth/token", """{"handle":"@chefe_x","password":"$STRONG_PASSWORD"}""").json().text("access_token")

    @Test
    fun `admin first enrollment needs the out of band code, the password alone is not enough`() {
        val t = TestAuth()
        registeredAdmin(t)
        authApp(t.components) {
            val restricted = restrictedToken()
            val bare = httpPost("/me/mfa/totp", "{}", restricted)
            assertEquals(HttpStatusCode.Forbidden, bare.status)
            assertEquals("enrollment_code_required", bare.json().text("detail"))
            val wrong = httpPost("/me/mfa/totp", """{"enrollment_code":"codigo-errado"}""", restricted)
            assertEquals(HttpStatusCode.Forbidden, wrong.status)

            val ok = httpPost("/me/mfa/totp", """{"enrollment_code":"$ADMIN_CODE"}""", restricted)
            assertEquals(HttpStatusCode.Created, ok.status)
            // uso unico: o mesmo codigo nao inicia outro enrolamento (o segredo pendente nao e substituido)
            val again = httpPost("/me/mfa/totp", """{"enrollment_code":"$ADMIN_CODE"}""", restricted)
            assertEquals(HttpStatusCode.Forbidden, again.status)
        }
    }

    @Test
    fun `an admin promoted without a code cannot enroll at all (fail closed)`() {
        val t = TestAuth()
        registeredAdmin(t, withCode = false)
        authApp(t.components) {
            val restricted = restrictedToken()
            val res = httpPost("/me/mfa/totp", """{"enrollment_code":"$ADMIN_CODE"}""", restricted)
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }
    }

    @Test
    fun `a regular user enrolls without any code`() {
        val t = TestAuth()
        authApp(t.components) {
            val bearer = signupAndLogin("@comum_x").text("access_token")
            assertEquals(HttpStatusCode.Created, httpPost("/me/mfa/totp", "{}", bearer).status)
        }
    }
}

private suspend fun HttpResponse.bodyAsArray() = Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject }
