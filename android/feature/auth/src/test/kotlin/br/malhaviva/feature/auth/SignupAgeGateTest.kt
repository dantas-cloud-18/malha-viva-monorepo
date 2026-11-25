package br.malhaviva.feature.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class SignupAgeGateTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport
    private val today = LocalDate.of(2026, 11, 25)

    private fun AuthViewModel.fill(
        birth: String,
        guardian: String? = null,
        channel: ContactChannel = ContactChannel.Phone,
    ) {
        act(AuthIntent.GoSignup)
        act(AuthIntent.HandleChanged("ana"))
        act(AuthIntent.PasswordChanged("senha-longa-1"))
        act(AuthIntent.BirthDateChanged(birth))
        if (guardian != null) {
            act(AuthIntent.GuardianChannelChanged(channel))
            act(AuthIntent.GuardianChanged(guardian))
        }
    }

    @Test
    fun `birth date accepts only real past dates in dd-mm-yyyy`() {
        assertEquals(LocalDate.of(2000, 2, 29), parseBirthDate("29/02/2000", today))
        assertNull(parseBirthDate("29/02/2001", today))
        assertNull(parseBirthDate("2000-02-29", today))
        assertEquals(LocalDate.of(1972, 2, 1), parseBirthDate("01-02-1972", today))
        assertEquals(LocalDate.of(1972, 2, 1), parseBirthDate("01.02.1972", today))
        assertEquals(LocalDate.of(1972, 2, 1), parseBirthDate("01021972", today))
        assertNull(parseBirthDate("01/01/2027", today))
        assertNull(parseBirthDate("01/01/1800", today))
        assertNull(parseBirthDate("", today))
        assertEquals(10, ageOn(LocalDate.of(2015, 11, 26), today))
        assertEquals(11, ageOn(LocalDate.of(2015, 11, 25), today))
        assertEquals(12, ageOn(LocalDate.of(2014, 11, 25), today))
    }

    @Test
    fun `signup needs a valid birth date before anything is sent`() =
        runTest {
            val vm = AuthViewModel(env.session, env.account)
            vm.fill("31/02/1990")
            vm.act(AuthIntent.SubmitSignup)
            assertTrue(t.requests.isEmpty())
            assertFalse(vm.state.value.canSubmitSignup)
        }

    @Test
    fun `adult signup sends the ISO birth date and no guardian`() =
        runTest {
            t.on("POST /auth/accounts", json(201, """{"handle":"@ana","age_bracket":"adult","status":"active"}"""))
            val vm = AuthViewModel(env.session, env.account)
            vm.fill("01/02/1990")
            vm.act(AuthIntent.SubmitSignup)
            val body = t.bodyOf(t.sent("POST /auth/accounts").single())
            assertEquals("1990-02-01", body.getString("birth_date"))
            assertFalse(body.has("guardian"))
            assertFalse("sem escolha o genero nao e enviado", body.has("gender"))
            assertEquals(AuthMessage.AccountCreated, vm.state.value.message)
        }

    @Test
    fun `chosen gender is sent and the default is not informed`() =
        runTest {
            t.on("POST /auth/accounts", json(201, """{"handle":"@ana","age_bracket":"adult","status":"active"}"""))
            val vm = AuthViewModel(env.session, env.account)
            assertNull(vm.state.value.gender)
            vm.fill("01/02/1990")
            vm.act(AuthIntent.GenderChanged(Gender.NonBinary))
            vm.act(AuthIntent.GenderChanged(Gender.PreferNotToSay))
            vm.act(AuthIntent.SubmitSignup)
            assertEquals("prefer_not_to_say", t.bodyOf(t.sent("POST /auth/accounts").single()).getString("gender"))
            assertNull("estado limpo apos o cadastro", vm.state.value.gender)
        }

    @Test
    fun `under 12 requires a guardian contact and sends it with the captcha`() =
        runTest {
            t.on(
                "POST /auth/accounts",
                json(201, """{"handle":"@ana","age_bracket":"under_12","status":"pending_parental_consent"}"""),
            )
            val vm = AuthViewModel(env.session, env.account)
            val young = LocalDate.now().minusYears(8)
            val birth = "%02d/%02d/%d".format(young.dayOfMonth, young.monthValue, young.year)
            vm.fill(birth)
            assertTrue(vm.state.value.needsGuardian)
            vm.act(AuthIntent.SubmitSignup)
            assertTrue("sem responsavel nada e enviado", t.requests.isEmpty())

            vm.act(AuthIntent.GuardianChanged("+55 (11) 98888-7777"))
            vm.act(AuthIntent.SubmitSignup)
            val body = t.bodyOf(t.sent("POST /auth/accounts").single())
            assertEquals("phone", body.getJSONObject("guardian").getString("channel"))
            assertEquals("+5511988887777", body.getJSONObject("guardian").getString("value"))
            assertEquals("captcha-ok", body.getString("captcha_token"))
            val state = vm.state.value
            assertEquals(AuthScreen.Login, state.screen)
            assertEquals(AuthMessage.AccountCreatedParental, state.message)
            assertNotNull(body.optString("birth_date"))
        }

    @Test
    fun `guardian by e-mail is validated and sent`() =
        runTest {
            t.on("POST /auth/accounts", json(201, ACCOUNT_JSON))
            val vm = AuthViewModel(env.session, env.account)
            val young = LocalDate.now().minusYears(10)
            vm.fill("%02d/%02d/%d".format(young.dayOfMonth, young.monthValue, young.year))
            vm.act(AuthIntent.GuardianChannelChanged(ContactChannel.Email))
            vm.act(AuthIntent.GuardianChanged("sem-arroba"))
            assertFalse(vm.state.value.canSubmitSignup)
            vm.act(AuthIntent.GuardianChanged("mae@example.com"))
            vm.act(AuthIntent.SubmitSignup)
            val guardian = t.bodyOf(t.sent("POST /auth/accounts").single()).getJSONObject("guardian")
            assertEquals("email", guardian.getString("channel"))
            assertEquals("mae@example.com", guardian.getString("value"))
        }

    @Test
    fun `under 12 in release without captcha is blocked locally`() =
        runTest {
            val release = TestEnv(captcha = null)
            val vm = AuthViewModel(release.session, release.account)
            val young = LocalDate.now().minusYears(9)
            vm.fill("%02d/%02d/%d".format(young.dayOfMonth, young.monthValue, young.year), "+5511988887777")
            vm.act(AuthIntent.SubmitSignup)
            assertEquals(AuthMessage.CaptchaUnavailable, vm.state.value.message)
            assertTrue(release.transport.requests.isEmpty())
        }

    @Test
    fun `login with pending parental consent goes to the guardian code screen`() =
        runTest {
            t.on("POST /auth/token", problem(403, "parental_consent_pending"))
            val vm = AuthViewModel(env.session, env.account)
            vm.act(AuthIntent.HandleChanged("ana"))
            vm.act(AuthIntent.PasswordChanged("senha-longa-1"))
            vm.act(AuthIntent.SubmitLogin)
            val state = vm.state.value
            assertEquals(AuthScreen.Parental, state.screen)
            assertEquals("ana", state.handle)
            assertEquals("", state.password)
        }
}
