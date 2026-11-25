package br.malhaviva.auth

import br.malhaviva.auth.recovery.TOTP_LOCK_MS
import br.malhaviva.auth.recovery.TOTP_MAX_FAILURES
import br.malhaviva.auth.recovery.Totp
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val WRONG = "000000"
private const val STEP_MS = 30_000L

private fun base32Decode(s: String): ByteArray {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (ch in s) {
        buffer = (buffer shl 5) or BASE32.indexOf(ch)
        bits += 5
        if (bits >= 8) {
            out.write((buffer shr (bits - 8)) and 0xff)
            bits -= 8
        }
    }
    return out.toByteArray()
}

class TotpLockoutTest {
    private class Enabled(
        val id: UUID,
        val secret: ByteArray,
    )

    private fun TestAuth.enabled(handle: String): Enabled =
        runBlocking {
            assertEquals(RegisterResult.Created, components.auth.register(handle, STRONG_PASSWORD))
            val id = accounts.findByHandle(handle)!!.id
            val started = assertIs<TotpEnrollResult.Started>(mfa.enroll(id, null))
            val secret = base32Decode(started.enrollment.secretBase32)
            assertTrue(mfa.confirm(id, code(secret)))
            now += 2 * STEP_MS
            Enabled(id, secret)
        }

    private fun TestAuth.code(secret: ByteArray) = Totp.generate(secret, Totp.step(now))

    /** Login por senha de um IP novo: devolve um mfa_token novo (o CAPTCHA aceita "ok"). */
    private fun TestAuth.newToken(
        handle: String,
        ip: String,
    ): String =
        runBlocking {
            assertIs<LoginResult.MfaRequired>(components.auth.login(ip, handle, STRONG_PASSWORD, null, "ok")).mfaToken
        }

    @Test
    fun `wrong codes across many mfa tokens and ips lock the account for 15 minutes`() {
        val t = TestAuth()
        val e = t.enabled("@forca_bruta")
        // 5 tokens x 2 erros, cada um de um IP: a trava por IP+handle e por token nunca chega a agir
        repeat(TOTP_MAX_FAILURES / 2) { i ->
            val ip = "10.0.0.$i"
            val token = t.newToken("@forca_bruta", ip)
            repeat(2) { assertEquals(LoginResult.InvalidMfa, t.components.auth.loginMfa(ip, token, WRONG)) }
        }
        val token = t.newToken("@forca_bruta", "10.0.0.99")
        val locked = t.components.auth.loginMfa("10.0.0.99", token, t.code(e.secret))
        val throttled = assertIs<LoginResult.Throttled>(locked, "nem o codigo certo passa enquanto trava")
        assertTrue(throttled.retryAfterSeconds in 1..TOTP_LOCK_MS / 1000)

        t.now += TOTP_LOCK_MS + 1_000
        val fresh = t.newToken("@forca_bruta", "10.0.0.98")
        assertIs<LoginResult.Success>(t.components.auth.loginMfa("10.0.0.98", fresh, t.code(e.secret)))
        val row = t.totpRepo.rows.getValue(e.id)
        assertEquals(0, row.failedAttempts, "acerto zera o contador")
        assertEquals(0L, row.lockedUntilMs)
    }

    @Test
    fun `a correct code resets the counter, so spread out typos never lock the owner`() {
        val t = TestAuth()
        val e = t.enabled("@digitou_errado")
        repeat(TOTP_MAX_FAILURES - 1) { assertFalse(t.totp.verify(e.id, WRONG)) }
        t.now += STEP_MS
        assertTrue(t.totp.verify(e.id, t.code(e.secret)))
        repeat(TOTP_MAX_FAILURES - 1) { assertFalse(t.totp.verify(e.id, WRONG)) }
        assertEquals(0L, t.totp.lockedSeconds(e.id), "nove erros depois do acerto ainda nao travam")
        assertFalse(t.totp.verify(e.id, WRONG))
        assertTrue(t.totp.lockedSeconds(e.id) > 0)
    }

    @Test
    fun `enrollment confirmation and disable are locked after the same number of failures`() {
        val t = TestAuth()
        runBlocking { t.components.auth.register("@confirma_1", STRONG_PASSWORD) }
        val id = t.accounts.findByHandle("@confirma_1")!!.id
        val started = assertIs<TotpEnrollResult.Started>(t.mfa.enroll(id, null))
        val secret = base32Decode(started.enrollment.secretBase32)
        repeat(TOTP_MAX_FAILURES) { assertFalse(t.mfa.confirm(id, WRONG)) }
        assertFalse(t.mfa.confirm(id, t.code(secret)), "travado: o codigo certo e recusado")
        t.now += TOTP_LOCK_MS + 1_000
        assertTrue(t.mfa.confirm(id, t.code(secret)))

        t.now += STEP_MS
        repeat(TOTP_MAX_FAILURES) { assertEquals(TotpDisableResult.DENIED, t.mfa.disable(id, STRONG_PASSWORD, WRONG)) }
        assertEquals(TotpDisableResult.DENIED, t.mfa.disable(id, STRONG_PASSWORD, t.code(secret)))
        assertTrue(t.mfa.totpLockedSeconds(id) > 0)
    }
}
