package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TotpViewModelTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private fun viewModel(enrollOnly: Boolean = false) =
        TotpViewModel(env.session, env.account).also { it.act(TotpIntent.Open(enrollOnly)) }

    private fun TotpViewModel.enrollTo(code: String = "123456") {
        t.on(
            "POST /me/mfa/totp",
            json(
                201,
                """{"secret":"JBSWY3DPEHPK3PXP","otpauth_uri":"otpauth://totp/MV:ana?secret=JBSWY3DPEHPK3PXP"}""",
            ),
        )
        act(TotpIntent.Go(TotpStep.EnrollStart))
        act(TotpIntent.SubmitStart)
        act(TotpIntent.CodeChanged(code))
    }

    @Test
    fun `enroll shows the secret and uri, then shows backup codes once and clears them`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/confirm", json(200, codesJson()))
            val vm = viewModel()
            vm.enrollTo()
            val confirm = vm.state.value
            assertEquals(TotpStep.EnrollConfirm, confirm.step)
            assertEquals("JBSWY3DPEHPK3PXP", confirm.enrollment?.secret)
            assertTrue(confirm.enrollment!!.otpauthUri.startsWith("otpauth://totp/"))
            vm.act(TotpIntent.SubmitConfirm)
            val shown = vm.state.value
            assertEquals(TotpStep.BackupCodes, shown.step)
            assertEquals(10, shown.backupCodes.size)
            assertNull(shown.enrollment)
            assertEquals("", shown.code)
            // Sem "guardei os codigos" nao ha como sair.
            vm.act(TotpIntent.Go(TotpStep.Menu))
            assertEquals(TotpStep.BackupCodes, vm.state.value.step)
            vm.act(TotpIntent.Acknowledge)
            assertEquals(TotpState(), vm.state.value)
            assertEquals("123456", t.bodyOf(t.sent("POST /me/mfa/totp/confirm").single()).getString("code"))
        }

    @Test
    fun `wrong confirmation code shows the incorrect code message and keeps the secret on screen`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/confirm", problem(400, "invalid_code"))
            val vm = viewModel()
            vm.enrollTo()
            vm.act(TotpIntent.SubmitConfirm)
            assertEquals(AuthMessage.InvalidMfa, vm.state.value.message)
            assertEquals(TotpStep.EnrollConfirm, vm.state.value.step)
            assertTrue(
                vm.state.value.backupCodes
                    .isEmpty(),
            )
        }

    @Test
    fun `429 on confirm shows Retry-After`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/confirm", problem(429, headers = mapOf("retry-after" to "60")))
            val vm = viewModel()
            vm.enrollTo()
            vm.act(TotpIntent.SubmitConfirm)
            assertEquals(AuthMessage.RateLimited, vm.state.value.message)
            assertEquals(60L, vm.state.value.retryAfterSeconds)
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `admin enrollment sends enrollment_code with the restricted token and logs out after the codes`() =
        runTest {
            t.on("POST /auth/token", json(200, ENROLL_JSON))
            env.session.login("@adm", "senha-longa-1", null)
            t.on("POST /me/mfa/totp", problem(403, "enrollment_code_required"))
            t.on("POST /me/mfa/totp/confirm", json(200, codesJson()))
            val vm = viewModel(enrollOnly = true)
            assertEquals(TotpStep.EnrollStart, vm.state.value.step)
            assertTrue(vm.state.value.needsEnrollmentCode)
            vm.act(TotpIntent.SubmitStart)
            assertTrue("sem codigo de ativacao nada e enviado", t.sent("POST /me/mfa/totp").isEmpty())
            vm.act(TotpIntent.EnrollmentCodeChanged("ENR-123"))
            vm.act(TotpIntent.SubmitStart)
            assertEquals(AuthMessage.EnrollmentCodeRequired, vm.state.value.message)
            val first = t.sent("POST /me/mfa/totp").single()
            assertEquals("ENR-123", t.bodyOf(first).getString("enrollment_code"))
            assertEquals("Bearer e", first.headers["Authorization"])

            t.on("POST /me/mfa/totp", json(201, """{"secret":"S","otpauth_uri":"otpauth://totp/x"}"""))
            vm.act(TotpIntent.SubmitStart)
            assertEquals(TotpStep.EnrollConfirm, vm.state.value.step)
            assertEquals("", vm.state.value.enrollmentCode)
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitConfirm)
            assertEquals(10, vm.state.value.backupCodes.size)
            assertEquals(SessionState.EnrollmentOnly, env.session.state.value)
            vm.act(TotpIntent.Acknowledge)
            assertTrue(vm.state.value.relogin)
            assertTrue(
                vm.state.value.backupCodes
                    .isEmpty(),
            )
            assertEquals(SessionState.LoggedOut, env.session.state.value)
        }

    @Test
    fun `regenerate sends password and code, or backup_code, and shows the new batch`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/backup-codes", json(200, codesJson("NEWBATCH")), json(200, codesJson("OTHERBAT")))
            val vm = viewModel()
            vm.act(TotpIntent.Go(TotpStep.Regenerate))
            vm.act(TotpIntent.SubmitRegenerate)
            assertTrue(t.sent("POST /me/mfa/totp/backup-codes").isEmpty())
            vm.act(TotpIntent.PasswordChanged("senha-longa-1"))
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitRegenerate)
            val shown = vm.state.value
            assertEquals(TotpStep.BackupCodes, shown.step)
            assertEquals(AuthMessage.CodesRegenerated, shown.message)
            assertEquals("NEWBATCH2", shown.backupCodes.first())
            assertEquals("", shown.password)
            vm.act(TotpIntent.Acknowledge)

            vm.act(TotpIntent.Go(TotpStep.Regenerate))
            vm.act(TotpIntent.ToggleBackupCode)
            vm.act(TotpIntent.PasswordChanged("senha-longa-1"))
            vm.act(TotpIntent.CodeChanged("ABCDE-FGHJK"))
            vm.act(TotpIntent.SubmitRegenerate)
            val second = t.bodyOf(t.sent("POST /me/mfa/totp/backup-codes").last())
            assertEquals("ABCDE-FGHJK", second.getString("backup_code"))
            assertFalse(second.has("code"))
            assertEquals(
                "OTHERBAT2",
                vm.state.value.backupCodes
                    .first(),
            )
        }

    @Test
    fun `regenerate with a wrong password or code shows invalid credentials`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/backup-codes", problem(403, "invalid_credentials"))
            val vm = viewModel()
            vm.act(TotpIntent.Go(TotpStep.Regenerate))
            vm.act(TotpIntent.PasswordChanged("senha-errada-1"))
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitRegenerate)
            assertEquals(AuthMessage.InvalidCredentials, vm.state.value.message)
            assertEquals(TotpStep.Regenerate, vm.state.value.step)
        }

    @Test
    fun `disable succeeds for a normal user and returns to the menu`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/disable", json(204))
            val vm = viewModel()
            vm.act(TotpIntent.Go(TotpStep.Disable))
            vm.act(TotpIntent.PasswordChanged("senha-longa-1"))
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitDisable)
            val state = vm.state.value
            assertEquals(TotpStep.Menu, state.step)
            assertEquals(AuthMessage.TotpDisabled, state.message)
            assertEquals("", state.password)
            val body = t.bodyOf(t.sent("POST /me/mfa/totp/disable").single())
            assertEquals("senha-longa-1", body.getString("password"))
            assertEquals("123456", body.getString("code"))
        }

    @Test
    fun `disable shows the server message for admin accounts`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/disable", problem(403, "admin_mfa_mandatory"))
            val vm = viewModel()
            vm.act(TotpIntent.Go(TotpStep.Disable))
            vm.act(TotpIntent.PasswordChanged("senha-longa-1"))
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitDisable)
            assertEquals(AuthMessage.AdminMfaMandatory, vm.state.value.message)
            assertEquals(AuthError.AdminMfaMandatory.toMessage(), vm.state.value.message)
            assertEquals(TotpStep.Disable, vm.state.value.step)
        }

    @Test
    fun `lost session on an authenticated route raises sessionLost`() =
        runTest {
            env.signIn()
            t.on("POST /me/mfa/totp/disable", problem(401))
            t.on("POST /auth/refresh", problem(401))
            val vm = viewModel()
            vm.act(TotpIntent.Go(TotpStep.Disable))
            vm.act(TotpIntent.PasswordChanged("senha-longa-1"))
            vm.act(TotpIntent.CodeChanged("123456"))
            vm.act(TotpIntent.SubmitDisable)
            assertTrue(vm.state.value.sessionLost)
        }
}
