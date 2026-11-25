package br.malhaviva.feature.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignupPhoneTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private fun AuthViewModel.fillSignup(phone: String) {
        act(AuthIntent.GoSignup)
        act(AuthIntent.HandleChanged("ana"))
        act(AuthIntent.PasswordChanged("senha-longa-1"))
        act(AuthIntent.PhoneChanged(phone))
        act(AuthIntent.SubmitSignup)
    }

    @Test
    fun `phone signup sends E164 and the captcha token, then tells to confirm the contact`() =
        runTest {
            t.on("POST /auth/accounts", json(201, ACCOUNT_JSON))
            val vm = AuthViewModel(env.session, env.account)
            vm.fillSignup("+55 (11) 99999-8888")
            val body = t.bodyOf(t.sent("POST /auth/accounts").single())
            assertEquals("+5511999998888", body.getString("phone"))
            assertEquals("captcha-ok", body.getString("captcha_token"))
            val state = vm.state.value
            assertEquals(AuthScreen.Login, state.screen)
            assertEquals(AuthMessage.AccountCreatedVerify, state.message)
            assertEquals("", state.password)
            assertEquals(DEFAULT_PHONE, state.phone)
        }

    @Test
    fun `phone signup in release is blocked without calling the server`() =
        runTest {
            val release = TestEnv(captcha = null)
            val vm = AuthViewModel(release.session, release.account)
            vm.fillSignup("+5511999998888")
            assertEquals(AuthMessage.CaptchaUnavailable, vm.state.value.message)
            assertTrue(release.transport.requests.isEmpty())
        }

    @Test
    fun `the default country code alone means no phone and no captcha`() =
        runTest {
            val release = TestEnv(captcha = null)
            release.transport.on("POST /auth/accounts", json(201, ACCOUNT_JSON))
            val vm = AuthViewModel(release.session, release.account)
            vm.fillSignup(DEFAULT_PHONE)
            val body = release.transport.bodyOf(release.transport.requests.single())
            assertFalse(body.has("phone"))
            assertFalse(body.has("captcha_token"))
            assertEquals(AuthMessage.AccountCreated, vm.state.value.message)
        }

    @Test
    fun `invalid phone never leaves the device and email still works`() =
        runTest {
            val vm = AuthViewModel(env.session, env.account)
            vm.fillSignup("+55 11")
            assertTrue(t.requests.isEmpty())
            t.on("POST /auth/accounts", json(201, ACCOUNT_JSON))
            vm.act(AuthIntent.PhoneChanged(DEFAULT_PHONE))
            vm.act(AuthIntent.EmailChanged("ana@example.com"))
            vm.act(AuthIntent.SubmitSignup)
            val body = t.bodyOf(t.sent("POST /auth/accounts").single())
            assertEquals("ana@example.com", body.getString("email"))
            assertFalse(body.has("phone"))
        }

    @Test
    fun `E164 helper accepts only plus and 8 to 15 digits`() {
        assertTrue(isPhoneValid("+5511999998888"))
        assertTrue(isPhoneValid("+55 11 99999-8888"))
        assertFalse(isPhoneValid("5511999998888"))
        assertFalse(isPhoneValid("+0511999998888"))
        assertFalse(isPhoneValid("+1234567"))
        assertFalse(isPhoneValid("+1234567890123456"))
    }
}
