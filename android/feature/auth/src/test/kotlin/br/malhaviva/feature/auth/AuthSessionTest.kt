package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.LoginOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOGIN = "POST /auth/token"
private const val MFA = "POST /auth/login/mfa"
private const val REFRESH = "POST /auth/refresh"
private const val DEVICES = "GET /me/devices"
private val DEVICE_LIST = "[${deviceJson("d1")}]"

private fun <T> ApiResult<T>.error(): AuthError = (this as ApiResult.Fail).error

private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Ok).value

class AuthSessionTest {
    private val env = TestEnv()
    private val t = env.transport

    @Test
    fun `login with tokens stores refresh and keeps access in memory`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            val outcome = env.session.login("@ana", "senha-longa-1", "Pixel").value()
            assertTrue(outcome is LoginOutcome.Success)
            assertEquals(SessionState.LoggedIn, env.session.state.value)
            assertEquals("ref-1", env.secrets.get(AuthSession.REFRESH_KEY))
            assertFalse(env.secrets.get("auth.access_token") != null)
            assertEquals("Pixel", t.bodyOf(t.requests.single()).getString("device_label"))
        }

    @Test
    fun `login with mfa challenge stores nothing`() =
        runTest {
            t.on(LOGIN, json(200, MFA_JSON))
            val outcome = env.session.login("@ana", "senha-longa-1", null).value() as LoginOutcome.MfaRequired
            assertEquals("m-1", outcome.mfaToken)
            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
            assertEquals(SessionState.LoggedOut, env.session.state.value)
        }

    @Test
    fun `login with enrollment required gives restricted state without refresh`() =
        runTest {
            t.on(
                LOGIN,
                json(200, ENROLL_JSON),
            )
            val outcome = env.session.login("@adm", "senha-longa-1", null).value()
            assertTrue(outcome is LoginOutcome.EnrollmentRequired)
            assertEquals(SessionState.EnrollmentOnly, env.session.state.value)
            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
        }

    @Test
    fun `mfa with totp code sends code and not backup_code`() =
        runTest {
            t.on(MFA, json(200, tokenBody("acc-2", "ref-2")))
            env.session.loginMfa("m-1", code = "123456").value()
            val body = t.bodyOf(t.requests.single())
            assertEquals("123456", body.getString("code"))
            assertFalse(body.has("backup_code"))
            assertEquals("ref-2", env.secrets.get(AuthSession.REFRESH_KEY))
        }

    @Test
    fun `mfa with backup code sends backup_code and not code`() =
        runTest {
            t.on(MFA, json(200, tokenBody("acc-3", "ref-3")))
            env.session.loginMfa("m-1", backupCode = "ABCD-EFGH").value()
            val body = t.bodyOf(t.requests.single())
            assertEquals("ABCD-EFGH", body.getString("backup_code"))
            assertFalse(body.has("code"))
        }

    @Test
    fun `invalid mfa is reported as InvalidMfa`() =
        runTest {
            t.on(MFA, problem(401, "invalid_mfa"))
            assertEquals(AuthError.InvalidMfa, env.session.loginMfa("m-1", code = "000000").error())
        }

    @Test
    fun `login retries once with a captcha token when the server asks for it`() =
        runTest {
            t.on(LOGIN, problem(403, "captcha_required"), json(200, tokenBody("a", "r")))
            env.session.login("@ana", "senha-longa-1", null).value()
            val sent = t.sent(LOGIN)
            assertFalse(t.bodyOf(sent[0]).has("captcha_token"))
            assertEquals("captcha-ok", t.bodyOf(sent[1]).getString("captcha_token"))
        }

    @Test
    fun `login captcha required without provider token fails clearly`() =
        runTest {
            val release = TestEnv(captcha = null)
            release.transport.on(LOGIN, problem(403, "captcha_required"))
            assertEquals(AuthError.CaptchaUnavailable, release.session.login("@ana", "senha-longa-1", null).error())
            assertEquals(1, release.transport.requests.size)
        }

    @Test
    fun `signup with contact is blocked without captcha provider and sends nothing`() =
        runTest {
            val release = TestEnv(captcha = null)
            val result = release.session.signup("@ana", "senha-longa-1", email = "ana@example.com")
            assertEquals(AuthError.CaptchaUnavailable, result.error())
            assertTrue(release.transport.requests.isEmpty())
        }

    @Test
    fun `signup without contact needs no captcha even in release`() =
        runTest {
            val release = TestEnv(captcha = null)
            release.transport.on(
                "POST /auth/accounts",
                json(201, ACCOUNT_JSON),
            )
            release.session.signup("@ana", "senha-longa-1").value()
            assertFalse(release.transport.bodyOf(release.transport.requests.single()).has("captcha_token"))
        }

    @Test
    fun `signup with contact sends captcha_token`() =
        runTest {
            t.on(
                "POST /auth/accounts",
                json(201, ACCOUNT_JSON),
            )
            env.session.signup("@ana", "senha-longa-1", email = "ana@example.com").value()
            val body = t.bodyOf(t.requests.single())
            assertEquals("captcha-ok", body.getString("captcha_token"))
            assertEquals("ana@example.com", body.getString("email"))
        }

    @Test
    fun `refresh on 401 rotates the stored token and retries once`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            env.session.login("@ana", "senha-longa-1", null)
            t.on(DEVICES, problem(401), json(200, DEVICE_LIST))
            t.on(REFRESH, json(200, tokenBody("acc-2", "ref-2")))

            val devices = env.session.authed { env.account.listDevices(it) }.value()

            assertEquals("d1", devices.single().id)
            assertEquals("ref-2", env.secrets.get(AuthSession.REFRESH_KEY))
            assertEquals("ref-1", t.bodyOf(t.sent(REFRESH).single()).getString("refresh_token"))
            val auth = t.sent(DEVICES).map { it.headers["Authorization"] }
            assertEquals(listOf("Bearer acc-1", "Bearer acc-2"), auth)
        }

    @Test
    fun `refresh reuse or rejection clears the session and logs out`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            env.session.login("@ana", "senha-longa-1", null)
            t.on(DEVICES, problem(401))
            t.on(REFRESH, problem(401))

            assertEquals(AuthError.Unauthorized, env.session.authed { env.account.listDevices(it) }.error())

            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
            assertEquals(SessionState.LoggedOut, env.session.state.value)
            assertEquals(1, t.sent(DEVICES).size)
        }

    @Test
    fun `refresh network failure keeps the stored token`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            env.session.login("@ana", "senha-longa-1", null)
            t.on(DEVICES, problem(401))
            t.failing += REFRESH
            assertEquals(AuthError.Network, env.session.authed { env.account.listDevices(it) }.error())
            assertEquals("ref-1", env.secrets.get(AuthSession.REFRESH_KEY))
        }

    @Test
    fun `restore uses the stored refresh token`() =
        runTest {
            env.secrets.put(AuthSession.REFRESH_KEY, "ref-old")
            t.on(REFRESH, json(200, tokenBody("acc-9", "ref-new")))
            assertTrue(env.session.restore())
            assertEquals("ref-new", env.secrets.get(AuthSession.REFRESH_KEY))
            assertEquals(SessionState.LoggedIn, env.session.state.value)
        }

    @Test
    fun `restore without stored token does nothing`() =
        runTest {
            assertFalse(env.session.restore())
            assertTrue(t.requests.isEmpty())
        }

    @Test
    fun `add contact sends current_password`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            env.session.login("@ana", "senha-longa-1", null)
            t.on("POST /me/contacts", json(202, """{"status":"verification_sent"}"""))
            env.session.authed { env.account.setContact(it, "email", "ana@example.com", "senha-longa-1") }.value()
            val body = t.bodyOf(t.sent("POST /me/contacts").single())
            assertEquals("senha-longa-1", body.getString("current_password"))
            assertEquals("email", body.getString("channel"))
        }

    @Test
    fun `logout revokes server side and clears local state`() =
        runTest {
            t.on(LOGIN, json(200, tokenBody("acc-1", "ref-1")))
            env.session.login("@ana", "senha-longa-1", null)
            t.on("POST /auth/logout", json(204))
            env.session.logout()
            assertEquals("ref-1", t.bodyOf(t.sent("POST /auth/logout").single()).getString("refresh_token"))
            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
            assertEquals(SessionState.LoggedOut, env.session.state.value)
        }
}
