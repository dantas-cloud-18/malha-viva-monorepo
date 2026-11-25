package br.malhaviva.feature.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecoveryViewModelTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private fun RecoveryViewModel.fillRequest() {
        act(RecoveryIntent.HandleChanged("ana"))
        act(RecoveryIntent.SubmitRequest)
    }

    private fun RecoveryViewModel.fillComplete(
        phone: String = "123456",
        email: String = "654321",
    ) {
        act(RecoveryIntent.PhoneCodeChanged(phone))
        act(RecoveryIntent.EmailCodeChanged(email))
        act(RecoveryIntent.PasswordChanged("nova-senha-longa"))
        act(RecoveryIntent.SubmitComplete)
    }

    @Test
    fun `request then complete ends logged out with no secrets left`() =
        runTest {
            env.secrets.put(AuthSession.REFRESH_KEY, "ref-old")
            t.on("POST /auth/recovery", json(202, """{"status":"accepted"}"""))
            t.on("POST /auth/recovery/complete", json(204))
            t.on("POST /auth/logout", json(204))
            val vm = RecoveryViewModel(env.recovery)
            vm.state.test {
                skipItems(1)
                vm.fillRequest()
                val asked = expectMostRecentItem()
                assertEquals(RecoveryStep.Complete, asked.step)
                assertEquals(AuthMessage.RecoveryRequested, asked.message)
                vm.fillComplete()
                val done = expectMostRecentItem()
                assertTrue(done.done)
                assertEquals("", done.newPassword)
                assertEquals("", done.phoneCode)
                assertEquals("", done.emailCode)
            }
            val body = t.bodyOf(t.sent("POST /auth/recovery/complete").single())
            assertEquals("@ana", body.getString("handle"))
            assertEquals("123456", body.getString("phone_code"))
            assertEquals("654321", body.getString("email_code"))
            assertEquals("nova-senha-longa", body.getString("new_password"))
            assertNull(env.secrets.get(AuthSession.REFRESH_KEY))
        }

    @Test
    fun `request shows the same message whatever the handle`() =
        runTest {
            t.on("POST /auth/recovery", json(202, """{"status":"accepted"}"""), json(202, """{"status":"accepted"}"""))
            val known = RecoveryViewModel(env.recovery).also { it.fillRequest() }
            val unknown = RecoveryViewModel(env.recovery)
            unknown.act(RecoveryIntent.HandleChanged("ninguem_aqui"))
            unknown.act(RecoveryIntent.SubmitRequest)
            assertEquals(known.state.value.message, unknown.state.value.message)
            assertEquals(known.state.value.step, unknown.state.value.step)
        }

    @Test
    fun `wrong or expired otp shows one generic error and stays on the form`() =
        runTest {
            t.on("POST /auth/recovery", json(202, """{"status":"accepted"}"""))
            t.on("POST /auth/recovery/complete", problem(400, "invalid_or_expired_code"))
            val vm = RecoveryViewModel(env.recovery)
            vm.fillRequest()
            vm.fillComplete()
            val state = vm.state.value
            assertEquals(AuthMessage.InvalidCode, state.message)
            assertFalse(state.done)
            assertFalse(state.busy)
        }

    @Test
    fun `429 on request shows Retry-After`() =
        runTest {
            t.on("POST /auth/recovery", problem(429, headers = mapOf("retry-after" to "30")))
            val vm = RecoveryViewModel(env.recovery)
            vm.fillRequest()
            assertEquals(AuthMessage.RateLimited, vm.state.value.message)
            assertEquals(30L, vm.state.value.retryAfterSeconds)
            assertEquals(RecoveryStep.Request, vm.state.value.step)
        }

    @Test
    fun `captcha is requested on demand and unavailable in release`() =
        runTest {
            t.on("POST /auth/recovery", problem(403, "captcha_required"), json(202, """{"status":"accepted"}"""))
            RecoveryViewModel(env.recovery).fillRequest()
            val retry = t.bodyOf(t.sent("POST /auth/recovery").last())
            assertEquals("captcha-ok", retry.getString("captcha_token"))

            val release = TestEnv(captcha = null)
            release.transport.on("POST /auth/recovery", problem(403, "captcha_required"))
            val vm = RecoveryViewModel(release.recovery)
            vm.fillRequest()
            assertEquals(AuthMessage.CaptchaUnavailable, vm.state.value.message)
            assertEquals(1, release.transport.requests.size)
        }

    @Test
    fun `completion needs a code, six digits and a valid new password`() {
        val base = RecoveryState(step = RecoveryStep.Complete, handle = "@ana", newPassword = "nova-senha-longa")
        assertFalse(base.canComplete)
        assertFalse(base.copy(phoneCode = "12345").canComplete)
        assertFalse(base.copy(phoneCode = "12345a").canComplete)
        assertFalse(base.copy(phoneCode = "123456", newPassword = "curta").canComplete)
        assertTrue(base.copy(phoneCode = "123456").canComplete)
        assertTrue(base.copy(emailCode = "123456").canComplete)
        assertEquals(base, reduceRecovery(base, RecoveryIntent.SubmitComplete))
    }
}
