package br.malhaviva.auth.recovery

import br.malhaviva.auth.AuthLimiters
import br.malhaviva.auth.CaptchaVerifier
import br.malhaviva.auth.STRONG_PASSWORD
import br.malhaviva.auth.TestAuth
import br.malhaviva.auth.authApp
import br.malhaviva.auth.httpPost
import br.malhaviva.auth.json
import br.malhaviva.auth.signupAndLogin
import br.malhaviva.auth.text
import br.malhaviva.tracking.ingest.RateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object RejectingCaptcha : CaptchaVerifier {
    override suspend fun verify(
        token: String?,
        ip: String,
    ) = false
}

class ContactHardeningTest {
    private fun roomy() = AuthLimiters(ip = RateLimiter(1000.0, 1.0), handle = RateLimiter(1000.0, 1.0))

    private fun set(
        channel: Channel,
        value: String,
        password: String? = null,
    ): String {
        val pw = password?.let { ""","current_password":"$it"""" }.orEmpty()
        return """{"channel":"${channel.wire}","value":"$value"$pw}"""
    }

    private suspend fun ApplicationTestBuilder.confirm(
        access: String,
        channel: Channel,
        code: String,
        password: String? = null,
    ) = httpPost(
        "/me/contacts/confirm",
        """{"channel":"${channel.wire}","code":"$code"${password?.let {
            ""","current_password":"$it""""
        }.orEmpty()}}""",
        access,
    )

    private fun TestRecovery.accountId(handle: String): UUID = auth.accounts.findByHandle(handle)!!.id

    @Test
    fun `the first recovery contact cannot be planted with a bare access token`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            val access = signupAndLogin("@sem_contato").text("access_token")
            val bare = httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE), access)
            assertEquals(HttpStatusCode.Forbidden, bare.status)
            assertEquals("invalid_password", bare.json().text("detail"))
            val wrong = httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE, "senha errada qualquer 123"), access)
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
            assertTrue(r.fake.sent.isEmpty(), "nada enviado sem a senha")
            assertNull(r.contacts.find(r.accountId("@sem_contato")).phone, "nada gravado sem a senha")

            val ok = httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE, STRONG_PASSWORD), access)
            assertEquals(HttpStatusCode.Accepted, ok.status)
            assertEquals(1, r.fake.sent.size)
        }
    }

    @Test
    fun `verifying the stored contact needs the current password too`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            val access = signupAndLogin("@verifica_1").text("access_token")
            httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE, STRONG_PASSWORD), access)
            val code = r.fake.lastCode(Channel.PHONE)
            assertEquals(HttpStatusCode.Forbidden, confirm(access, Channel.PHONE, code).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                confirm(access, Channel.PHONE, code, "senha errada qualquer 123").status,
            )
            assertFalse(r.contacts.find(r.accountId("@verifica_1")).phoneVerified)
            assertEquals(HttpStatusCode.NoContent, confirm(access, Channel.PHONE, code, STRONG_PASSWORD).status)
            assertTrue(r.contacts.find(r.accountId("@verifica_1")).phoneVerified)
        }
    }

    @Test
    fun `adding a contact warns the channels that are already verified`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            val access = signupAndLogin("@aviso_1").text("access_token")
            httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE, STRONG_PASSWORD), access)
            confirm(access, Channel.PHONE, r.fake.lastCode(Channel.PHONE), STRONG_PASSWORD)
            assertEquals(1, r.fake.to(FAKE_PHONE).size, "so o OTP: ainda nao havia canal verificado para avisar")

            httpPost("/me/contacts", set(Channel.EMAIL, FAKE_EMAIL, STRONG_PASSWORD), access)
            assertTrue(r.fake.to(FAKE_PHONE).any { it.text.contains("novo contato") }, "canal verificado foi avisado")
            assertTrue(r.fake.to(FAKE_EMAIL).none { it.text.contains("novo contato") }, "o canal novo nao e avisado")
        }
    }

    @Test
    fun `starting a change notifies every verified channel, not only the old one`() {
        val r = TestRecovery(TestAuth(limiters = roomy()))
        authApp(r.components) {
            assertEquals(HttpStatusCode.Created, signup("@aviso_2").status)
            val access = httpPost("/auth/token", """{"handle":"@aviso_2","password":"$STRONG_PASSWORD"}""").json()
            val token = access.text("access_token")
            Channel.entries.forEach { confirm(token, it, r.fake.lastCode(it), STRONG_PASSWORD) }
            val emailBefore = r.fake.to(FAKE_EMAIL).size
            val res = httpPost("/me/contacts", set(Channel.PHONE, FAKE_PHONE_2, STRONG_PASSWORD), token)
            assertEquals(HttpStatusCode.Accepted, res.status)
            assertTrue(
                r.fake
                    .to(FAKE_EMAIL)
                    .drop(emailBefore)
                    .any { it.text.contains("troca de contato") },
            )
        }
    }

    @Test
    fun `signup with contacts needs a valid captcha before any message is sent`() {
        val r = TestRecovery(TestAuth(limiters = roomy()), captcha = RejectingCaptcha)
        authApp(r.components) {
            val body = """{"handle":"@sem_captcha","password":"$STRONG_PASSWORD","phone":"$FAKE_PHONE"}"""
            val refused = httpPost("/auth/accounts", body)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("captcha_required", refused.json().text("detail"))
            assertTrue(r.fake.sent.isEmpty())
            assertNull(r.auth.accounts.findByHandle("@sem_captcha"), "a conta nem e criada")
            // sem contato nada e enviado, entao o CAPTCHA nao e exigido
            val plain = """{"handle":"@sem_contato_2","password":"$STRONG_PASSWORD"}"""
            assertEquals(HttpStatusCode.Created, httpPost("/auth/accounts", plain).status)
        }
    }

    private suspend fun ApplicationTestBuilder.signup(handle: String) =
        httpPost(
            "/auth/accounts",
            """{"handle":"$handle","password":"$STRONG_PASSWORD","phone":"$FAKE_PHONE","email":"$FAKE_EMAIL",""" +
                """"captcha_token":"ok"}""",
        )
}
