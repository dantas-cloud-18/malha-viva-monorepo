package br.malhaviva.feature.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ParentalViewModelTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private fun viewModel() = ParentalViewModel(env.parental).also { it.act(ParentalIntent.Open("ana")) }

    @Test
    fun `confirm sends handle and code, then signals done`() =
        runTest {
            t.on("POST /auth/parental-consent/confirm", json(204))
            val vm = viewModel()
            vm.act(ParentalIntent.CodeChanged("12345"))
            vm.act(ParentalIntent.SubmitConfirm)
            assertTrue("codigo curto nao sai do aparelho", t.requests.isEmpty())
            vm.act(ParentalIntent.CodeChanged("123456"))
            vm.act(ParentalIntent.SubmitConfirm)
            val body = t.bodyOf(t.sent("POST /auth/parental-consent/confirm").single())
            assertEquals("@ana", body.getString("handle"))
            assertEquals("123456", body.getString("code"))
            assertTrue(vm.state.value.done)
            assertEquals("", vm.state.value.code)
        }

    @Test
    fun `wrong code shows the invalid code message`() =
        runTest {
            t.on("POST /auth/parental-consent/confirm", problem(400, "invalid_code"))
            val vm = viewModel()
            vm.act(ParentalIntent.CodeChanged("000000"))
            vm.act(ParentalIntent.SubmitConfirm)
            assertEquals(AuthMessage.InvalidCode, vm.state.value.message)
            assertFalse(vm.state.value.done)
        }

    @Test
    fun `resend always carries the captcha token`() =
        runTest {
            t.on("POST /auth/parental-consent/resend", json(202, """{"status":"accepted"}"""))
            val vm = viewModel()
            vm.act(ParentalIntent.Resend)
            val body = t.bodyOf(t.sent("POST /auth/parental-consent/resend").single())
            assertEquals("captcha-ok", body.getString("captcha_token"))
            assertEquals("@ana", body.getString("handle"))
            assertEquals(AuthMessage.ParentalResent, vm.state.value.message)
        }

    @Test
    fun `resend without a captcha provider sends nothing`() =
        runTest {
            val release = TestEnv(captcha = null)
            val vm = ParentalViewModel(release.parental).also { it.act(ParentalIntent.Open("ana")) }
            vm.act(ParentalIntent.Resend)
            assertEquals(AuthMessage.CaptchaUnavailable, vm.state.value.message)
            assertTrue(release.transport.requests.isEmpty())
        }

    @Test
    fun `rate limit shows retry-after`() =
        runTest {
            t.on("POST /auth/parental-consent/confirm", problem(429, headers = mapOf("retry-after" to "30")))
            val vm = viewModel()
            vm.act(ParentalIntent.CodeChanged("123456"))
            vm.act(ParentalIntent.SubmitConfirm)
            assertEquals(AuthMessage.RateLimited, vm.state.value.message)
            assertEquals(30L, vm.state.value.retryAfterSeconds)
        }
}
