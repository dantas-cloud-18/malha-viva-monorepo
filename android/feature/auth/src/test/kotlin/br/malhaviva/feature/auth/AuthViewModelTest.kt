package br.malhaviva.feature.auth

import app.cash.turbine.test
import br.malhaviva.feature.auth.net.AuthError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val env = TestEnv()
    private val t = env.transport

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AuthViewModel(env.session, env.account, "Pixel")

    private fun AuthViewModel.fillLogin(
        handle: String = "ana",
        password: String = "senha-longa-1",
    ) {
        act(AuthIntent.HandleChanged(handle))
        act(AuthIntent.PasswordChanged(password))
        act(AuthIntent.BirthDateChanged("01/01/1990"))
    }

    @Test
    fun `login with mfa goes to the code step and then to devices`() =
        runTest {
            t.on("POST /auth/token", json(200, MFA_JSON))
            t.on("POST /auth/login/mfa", json(200, tokenBody("acc", "ref")))
            t.on("GET /me/devices", json(200, "[]"))
            val vm = viewModel()
            vm.state.test {
                assertEquals(AuthScreen.Login, awaitItem().screen)
                vm.fillLogin()
                vm.act(AuthIntent.SubmitLogin)
                val mfa = expectMostRecentItem()
                assertEquals(AuthScreen.Mfa, mfa.screen)
                assertEquals("", mfa.password)
                vm.act(AuthIntent.MfaCodeChanged("123456"))
                vm.act(AuthIntent.SubmitMfa)
                assertEquals(AuthScreen.Devices, expectMostRecentItem().screen)
            }
            assertEquals("@ana", t.bodyOf(t.sent("POST /auth/token").single()).getString("handle"))
        }

    @Test
    fun `backup code toggle sends backup_code`() =
        runTest {
            t.on("POST /auth/token", json(200, MFA_JSON))
            t.on("POST /auth/login/mfa", json(200, tokenBody("acc", "ref")))
            t.on("GET /me/devices", json(200, "[]"))
            val vm = viewModel()
            vm.fillLogin()
            vm.act(AuthIntent.SubmitLogin)
            vm.act(AuthIntent.ToggleBackupCode)
            vm.act(AuthIntent.MfaCodeChanged("ABCD-EFGH"))
            vm.act(AuthIntent.SubmitMfa)
            val body = t.bodyOf(t.sent("POST /auth/login/mfa").single())
            assertEquals("ABCD-EFGH", body.getString("backup_code"))
            assertFalse(body.has("code"))
            assertEquals(AuthScreen.Devices, vm.state.value.screen)
        }

    @Test
    fun `totp code needs exactly six digits`() {
        val mfa = AuthState(screen = AuthScreen.Mfa, mfaToken = "m", mfaCode = "12345")
        assertFalse(mfa.canSubmitMfa)
        assertTrue(mfa.copy(mfaCode = "123456").canSubmitMfa)
        assertTrue(mfa.copy(useBackupCode = true).canSubmitMfa)
        assertEquals(mfa, reduceAuth(mfa, AuthIntent.SubmitMfa))
    }

    @Test
    fun `enrollment required lands in restricted state`() =
        runTest {
            t.on(
                "POST /auth/token",
                json(200, ENROLL_JSON),
            )
            val vm = viewModel()
            vm.fillLogin()
            vm.act(AuthIntent.SubmitLogin)
            assertEquals(AuthScreen.EnrollmentOnly, vm.state.value.screen)
            assertTrue(t.sent("GET /me/devices").isEmpty())
        }

    @Test
    fun `rate limit shows retry after and re-enables the button`() =
        runTest {
            t.on("POST /auth/token", problem(429, headers = mapOf("retry-after" to "30")))
            val vm = viewModel()
            vm.fillLogin()
            vm.state.test {
                skipItems(1)
                vm.act(AuthIntent.SubmitLogin)
                val failed = expectMostRecentItem()
                assertEquals(AuthMessage.RateLimited, failed.message)
                assertEquals(30L, failed.retryAfterSeconds)
                assertFalse(failed.busy)
            }
        }

    @Test
    fun `short password never leaves the device`() =
        runTest {
            val vm = viewModel()
            vm.fillLogin(password = "curta")
            vm.act(AuthIntent.SubmitLogin)
            assertTrue(t.requests.isEmpty())
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `password policy is length only`() {
        assertTrue(isPasswordValid("a".repeat(8)))
        assertTrue(isPasswordValid("so letras minusculas e espacos"))
        assertFalse(isPasswordValid("a".repeat(7)))
        assertFalse(isPasswordValid("a".repeat(129)))
        assertTrue(isPasswordValid("a".repeat(128)))
    }

    @Test
    fun `handle gets the at sign and is validated`() {
        assertEquals("@ana", normalizeHandle(" ana "))
        assertTrue(isHandleValid("ana_1"))
        assertFalse(isHandleValid("ab"))
        assertFalse(isHandleValid("a b c"))
    }

    @Test
    fun `signup with email in release shows the captcha notice without calling the server`() =
        runTest {
            val release = TestEnv(captcha = null)
            val vm = AuthViewModel(release.session, release.account)
            vm.act(AuthIntent.GoSignup)
            vm.fillLogin()
            vm.act(AuthIntent.EmailChanged("ana@example.com"))
            vm.act(AuthIntent.SubmitSignup)
            assertEquals(AuthMessage.CaptchaUnavailable, vm.state.value.message)
            assertTrue(release.transport.requests.isEmpty())
        }

    @Test
    fun `successful signup returns to login with a notice and no password`() =
        runTest {
            t.on(
                "POST /auth/accounts",
                json(201, ACCOUNT_JSON),
            )
            val vm = viewModel()
            vm.act(AuthIntent.GoSignup)
            vm.fillLogin()
            vm.act(AuthIntent.SubmitSignup)
            val state = vm.state.value
            assertEquals(AuthScreen.Login, state.screen)
            assertEquals(AuthMessage.AccountCreated, state.message)
            assertEquals("", state.password)
        }

    @Test
    fun `taken handle maps to its own message`() {
        val failed = reduceAuth(AuthState(busy = true), AuthIntent.Failed(AuthError.Conflict))
        assertEquals(AuthMessage.HandleTaken, failed.message)
        assertFalse(failed.busy)
    }

    @Test
    fun `devices load and revoke`() =
        runTest {
            t.on("POST /auth/token", json(200, tokenBody("acc", "ref")))
            t.on(
                "GET /me/devices",
                json(200, "[${deviceJson("d1")},${deviceJson("d2")}]"),
            )
            t.on("DELETE /me/devices/d1", json(204))
            val vm = viewModel()
            vm.fillLogin()
            vm.act(AuthIntent.SubmitLogin)
            assertEquals(
                listOf("d1", "d2"),
                vm.state.value.devices
                    .map { it.id },
            )
            vm.act(AuthIntent.RevokeDevice("d1"))
            assertEquals(
                listOf("d2"),
                vm.state.value.devices
                    .map { it.id },
            )
        }

    @Test
    fun `lost session on devices returns to login`() =
        runTest {
            t.on("POST /auth/token", json(200, tokenBody("acc", "ref")))
            t.on("GET /me/devices", problem(401))
            t.on("POST /auth/refresh", problem(401))
            val vm = viewModel()
            vm.fillLogin()
            vm.act(AuthIntent.SubmitLogin)
            val state = vm.state.value
            assertEquals(AuthScreen.Login, state.screen)
            assertEquals(AuthMessage.SessionExpired, state.message)
            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
        }

    @Test
    fun `stored session is restored on start`() =
        runTest {
            env.secrets.put(AuthSession.REFRESH_KEY, "ref-old")
            t.on("POST /auth/refresh", json(200, tokenBody("acc", "ref-new")))
            t.on("GET /me/devices", json(200, "[]"))
            assertEquals(AuthScreen.Devices, viewModel().state.value.screen)
        }
}
