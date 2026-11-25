package br.malhaviva.auth

import br.malhaviva.PostgresIntegrationTest
import br.malhaviva.auth.recovery.BACKUP_CODE_COUNT
import br.malhaviva.auth.recovery.BackupCodeService
import br.malhaviva.auth.recovery.JdbcBackupCodeRepository
import br.malhaviva.auth.recovery.JdbcTotpRepository
import br.malhaviva.auth.recovery.RecordingSecurityNotifier
import br.malhaviva.auth.recovery.SecurityEvent
import br.malhaviva.auth.recovery.TEST_OTP_KEY
import br.malhaviva.auth.recovery.TOTP_MAX_FAILURES
import br.malhaviva.auth.recovery.Totp
import br.malhaviva.auth.recovery.TotpService
import java.io.ByteArrayOutputStream
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val STEP_MS = 30_000L
private const val ENROLL_CODE = "codigo-admin-integracao-0123456789"
private const val UNIQUE_VIOLATION = "23505"

private fun unbase32(s: String): ByteArray {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (ch in s) {
        buffer = (buffer shl 5) or B32.indexOf(ch)
        bits += 5
        if (bits >= 8) {
            out.write((buffer shr (bits - 8)) and 0xff)
            bits -= 8
        }
    }
    return out.toByteArray()
}

/** Segredo TOTP, trava por conta e codigos de recuperacao nos repositorios JDBC, contra Postgres de verdade. */
class JdbcTotpIntegrationTest : PostgresIntegrationTest() {
    private val accounts get() = JdbcAccountRepository(ds)
    private val totpRepo get() = JdbcTotpRepository(ds)
    private val backupRepo get() = JdbcBackupCodeRepository(ds)
    private val backup get() = BackupCodeService(backupRepo, TEST_OTP_KEY)

    private fun account(handle: String) = accounts.create(handle, "hash")!!

    private fun backupRows(acc: UUID) =
        scalars("SELECT count(*) FROM totp_backup_codes WHERE account_id = ?", acc).single() as Long

    @Test
    fun `totp secret lifecycle keeps an enabled secret and advances the step only once per race`() {
        val acc = account("@totp_um")
        assertNull(totpRepo.find(acc))
        totpRepo.save(acc, byteArrayOf(1, 2, 3))
        totpRepo.save(acc, byteArrayOf(4, 5, 6))
        val pending = assertNotNull(totpRepo.find(acc))
        assertContentEquals(byteArrayOf(4, 5, 6), pending.secretEnc, "segredo nao habilitado e substituido")
        assertFalse(pending.enabled)
        assertFalse(totpRepo.advance(acc, 0, 1), "so avanca depois de habilitado")

        totpRepo.enable(acc, 5)
        totpRepo.save(acc, byteArrayOf(7, 7, 7))
        val enabled = totpRepo.find(acc)!!
        assertTrue(enabled.enabled)
        assertEquals(5, enabled.lastStep)
        assertContentEquals(byteArrayOf(4, 5, 6), enabled.secretEnc, "segredo habilitado nao e sobrescrito")

        assertTrue(totpRepo.advance(acc, 5, 6))
        assertFalse(totpRepo.advance(acc, 5, 7))
        assertEquals(1, race(8) { totpRepo.advance(acc, 6, 100L + it) }.count { it })
        totpRepo.delete(acc)
        assertNull(totpRepo.find(acc))
    }

    @Test
    fun `recordFailure counts every failure, locks at the limit and clearFailures resets`() {
        val acc = account("@totp_dois")
        totpRepo.save(acc, byteArrayOf(1))
        totpRepo.enable(acc, 1)
        val now = System.currentTimeMillis()
        repeat(2) { totpRepo.recordFailure(acc, now, 3, 60_000L) }
        assertEquals(2, totpRepo.find(acc)!!.failedAttempts)
        assertEquals(0L, totpRepo.find(acc)!!.lockedUntilMs)

        totpRepo.recordFailure(acc, now, 3, 60_000L)
        val locked = totpRepo.find(acc)!!
        assertEquals(0, locked.failedAttempts, "ao travar o contador zera")
        assertEquals(now + 60_000L, locked.lockedUntilMs)

        totpRepo.clearFailures(acc)
        assertEquals(0L, totpRepo.find(acc)!!.lockedUntilMs)
        race(9) { totpRepo.recordFailure(acc, now, 100, 60_000L) }
        assertEquals(9, totpRepo.find(acc)!!.failedAttempts, "erros simultaneos nunca perdem contagem")
    }

    @Test
    fun `TotpService on JDBC enrolls, confirms, replays once and locks the account after the limit`() {
        val acc = account("@totp_tres")
        var now = System.currentTimeMillis()
        val svc = TotpService(totpRepo, TEST_OTP_KEY, { now })
        val secret = unbase32(assertNotNull(svc.enroll(acc, "@totp_tres")).secretBase32)
        assertFalse(svc.isEnabled(acc))
        assertTrue(svc.confirm(acc, Totp.generate(secret, Totp.step(now))))
        assertTrue(svc.isEnabled(acc))
        assertNull(svc.enroll(acc, "@totp_tres"), "ja habilitado")

        now += 2 * STEP_MS
        val code = Totp.generate(secret, Totp.step(now))
        assertTrue(svc.verify(acc, code))
        assertFalse(svc.verify(acc, code), "o mesmo passo nao vale duas vezes (e conta 1 erro)")
        repeat(TOTP_MAX_FAILURES - 2) { assertFalse(svc.verify(acc, "000000")) }
        assertEquals(0L, svc.lockedSeconds(acc))
        assertFalse(svc.verify(acc, "000000"))
        assertTrue(svc.lockedSeconds(acc) > 0)
        now += STEP_MS
        assertFalse(svc.verify(acc, Totp.generate(secret, Totp.step(now))), "travada: nem o codigo certo passa")
    }

    @Test
    fun `admin enrollment consumes the out-of-band code once and returns backup codes usable once`() {
        val acc = account("@admin_int")
        scalars(
            "UPDATE accounts SET role = 'admin', admin_enroll_hash = ? WHERE account_id = ?",
            sha256Hex(ENROLL_CODE),
            acc,
        )
        var now = System.currentTimeMillis()
        val notices = RecordingSecurityNotifier()
        val totp = TotpService(totpRepo, TEST_OTP_KEY, { now })
        val mfa =
            MfaService(
                accounts,
                totp,
                BackupCodeService(backupRepo, TEST_OTP_KEY, { now }),
                notices,
                PasswordHasher(TEST_PEPPER, FAST_PARAMS),
                AccessTokenService(TEST_JWT_SECRET),
                { now },
            )
        assertEquals(MfaGate.ENROLL, mfa.gate(accounts.findById(acc)!!))
        assertIs<TotpEnrollResult.CodeRequired>(mfa.enroll(acc, "errado"))
        val started = assertIs<TotpEnrollResult.Started>(mfa.enroll(acc, ENROLL_CODE))
        assertIs<TotpEnrollResult.CodeRequired>(mfa.enroll(acc, ENROLL_CODE), "o codigo e de uso unico")
        val secret = unbase32(started.enrollment.secretBase32)
        val codes = assertNotNull(mfa.confirm(acc, Totp.generate(secret, Totp.step(now))))
        assertEquals(BACKUP_CODE_COUNT, codes.size)
        assertEquals(BACKUP_CODE_COUNT.toLong(), backupRows(acc))
        assertEquals(MfaGate.CODE, mfa.gate(accounts.findById(acc)!!))

        val first = assertIs<MfaRedeem.Ok>(mfa.redeem(mfa.open(acc, "@admin_int", null), codes[0], MfaFactor.BACKUP))
        assertEquals(acc, first.challenge.accountId)
        assertEquals(MfaRedeem.Wrong, mfa.redeem(mfa.open(acc, "@admin_int", null), codes[0], MfaFactor.BACKUP))
        assertEquals(listOf(acc to SecurityEvent.BACKUP_CODE_USED), notices.events.toList())
        assertEquals(1, totpRepo.find(acc)!!.failedAttempts, "erro de codigo de recuperacao conta na trava do TOTP")
        now += STEP_MS
        assertEquals(TotpDisableResult.ADMIN_MANDATORY, mfa.disable(acc, "qualquer", "000000"))
    }

    @Test
    fun `backup codes are hashed, single use, and a concurrent double spend has one winner`() {
        val acc = account("@backup_um")
        val codes = backup.regenerate(acc)
        assertEquals(BACKUP_CODE_COUNT.toLong(), backupRows(acc))
        val stored = scalars("SELECT code_hmac FROM totp_backup_codes WHERE account_id = ?", acc).map { it as String }
        codes.forEach { c -> assertTrue(stored.none { c in it }, "codigo em claro no banco") }

        assertTrue(backup.consume(acc, codes[0]))
        assertFalse(backup.consume(acc, codes[0]))
        assertNotNull(scalars("SELECT used_at FROM totp_backup_codes WHERE used_at IS NOT NULL").single())
        assertEquals(BACKUP_CODE_COUNT - 1, backupRepo.unusedHashes(acc).size)
        assertFalse(backup.consume(account("@backup_dois"), codes[1]), "codigo de outra conta nao vale")

        assertEquals(1, race(8) { backup.consume(acc, codes[1]) }.count { it }, "corrida: um unico ganhador")
    }

    @Test
    fun `regenerate replaces the old batch even when regenerations race, and the account delete cascades`() {
        val acc = account("@backup_tres")
        val old = backup.regenerate(acc)
        val other = account("@backup_quatro")
        val otherCodes = backup.regenerate(other)
        race(6) { backup.regenerate(acc) }
        assertEquals(BACKUP_CODE_COUNT.toLong(), backupRows(acc), "regeneracoes simultaneas nao acumulam lotes")
        assertTrue(old.none { backup.consume(acc, it) }, "o lote antigo morreu")
        assertTrue(backup.consume(other, otherCodes[0]), "o lote de outra conta ficou intacto")

        val hash = backupRepo.unusedHashes(other).first()
        val dup =
            runCatching {
                scalars("INSERT INTO totp_backup_codes (account_id, code_hmac) VALUES (?, ?)", other, hash)
            }.exceptionOrNull()
        assertEquals(UNIQUE_VIOLATION, (dup as? SQLException)?.sqlState, "indice unico por (conta, hmac)")

        backup.deleteAll(other)
        assertEquals(0L, backupRows(other))
        scalars("DELETE FROM accounts WHERE account_id = ?", acc)
        assertEquals(0L, backupRows(acc))
    }
}
