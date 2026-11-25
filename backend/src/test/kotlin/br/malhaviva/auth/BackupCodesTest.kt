package br.malhaviva.auth

import br.malhaviva.auth.recovery.BACKUP_CODE_ALPHABET
import br.malhaviva.auth.recovery.BACKUP_CODE_COUNT
import br.malhaviva.auth.recovery.BACKUP_CODE_LENGTH
import br.malhaviva.auth.recovery.Channel
import br.malhaviva.auth.recovery.ContactSecurityNotifier
import br.malhaviva.auth.recovery.FAKE_EMAIL
import br.malhaviva.auth.recovery.FAKE_PHONE
import br.malhaviva.auth.recovery.FakeSender
import br.malhaviva.auth.recovery.InMemoryContactRepository
import br.malhaviva.auth.recovery.MessageGateway
import br.malhaviva.auth.recovery.SecurityEvent
import br.malhaviva.auth.recovery.TOTP_LOCK_MS
import br.malhaviva.auth.recovery.TOTP_MAX_FAILURES
import br.malhaviva.auth.recovery.Totp
import br.malhaviva.auth.recovery.roomyLimits
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val STEP_MS = 30_000L
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val ENROLL_CODE = "codigo-admin-de-teste-fedcba9876543210"
private const val WRONG_BACKUP = "AAAAAAAAAA"
private const val WRONG_TOTP = "000000"
private const val RACERS = 8

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

class BackupCodesTest {
    private class Enabled(
        val id: UUID,
        val handle: String,
        val secret: ByteArray,
        val codes: List<String>,
    )

    private fun TestAuth.enabled(
        handle: String,
        role: String = ROLE_USER,
    ): Enabled =
        runBlocking {
            assertEquals(RegisterResult.Created, components.auth.register(handle, STRONG_PASSWORD))
            val id = accounts.findByHandle(handle)!!.id
            accounts.setRole(id, role)
            if (role == ROLE_ADMIN) accounts.setAdminEnrollCode(id, ENROLL_CODE)
            val started = assertIs<TotpEnrollResult.Started>(mfa.enroll(id, ENROLL_CODE.takeIf { role == ROLE_ADMIN }))
            val secret = base32Decode(started.enrollment.secretBase32)
            val codes = assertNotNull(mfa.confirm(id, code(secret)))
            now += 2 * STEP_MS
            Enabled(id, handle, secret, codes)
        }

    private fun TestAuth.code(secret: ByteArray) = Totp.generate(secret, Totp.step(now))

    private fun TestAuth.challenge(
        e: Enabled,
        ip: String = "10.1.0.1",
    ): String =
        runBlocking {
            assertIs<LoginResult.MfaRequired>(components.auth.login(ip, e.handle, STRONG_PASSWORD, null, "ok")).mfaToken
        }

    private fun TestAuth.loginWithBackup(
        e: Enabled,
        code: String,
        ip: String = "10.1.0.1",
    ) = components.auth.loginMfa(ip, challenge(e, ip), code, MfaFactor.BACKUP)

    @Test
    fun `confirm returns ten distinct codes of ten chars from the unambiguous alphabet, only hashes stored`() {
        val t = TestAuth()
        val e = t.enabled("@gera_codigos")
        assertEquals(BACKUP_CODE_COUNT, e.codes.size)
        assertEquals(BACKUP_CODE_COUNT, e.codes.toSet().size)
        e.codes.forEach { c ->
            assertEquals(BACKUP_CODE_LENGTH, c.length)
            assertTrue(c.all { it in BACKUP_CODE_ALPHABET }, "simbolo ambiguo ou fora do alfabeto em $c")
        }
        assertFalse(BACKUP_CODE_ALPHABET.any { it in "IO01" })
        val stored = t.backupRepo.storedHashes(e.id)
        assertEquals(BACKUP_CODE_COUNT, stored.size)
        e.codes.forEach { c -> assertTrue(stored.none { c in it }, "codigo em claro guardado") }
        // lotes sucessivos nao repetem codigos (50 bits cada)
        val batches = (1..20).flatMap { t.backup.regenerate(e.id) }
        assertEquals(batches.size, batches.toSet().size)
    }

    @Test
    fun `a backup code logs in once and only once, with the TOTP session marked as mfa`() {
        val t = TestAuth()
        val e = t.enabled("@uso_unico")
        val ok = assertIs<LoginResult.Success>(t.loginWithBackup(e, e.codes[0]))
        assertTrue(t.access.verifyPrincipal(ok.tokens.accessToken, t.now)!!.mfa)
        assertEquals(LoginResult.InvalidMfa, t.loginWithBackup(e, e.codes[0], "10.1.0.2"), "segundo uso recusado")
        // espacos, hifen e minusculas sao tolerados na digitacao
        val typed = e.codes[1].lowercase().let { "${it.take(5)}-${it.drop(5)}" }
        assertIs<LoginResult.Success>(t.loginWithBackup(e, typed, "10.1.0.3"))
        assertEquals(listOf(e.id to SecurityEvent.BACKUP_CODE_USED), t.notices.events.take(1))
        assertEquals(2, t.notices.events.size)
    }

    @Test
    fun `two concurrent consumes of the same code, exactly one wins`() {
        val t = TestAuth()
        val e = t.enabled("@corrida")
        val pool = Executors.newFixedThreadPool(RACERS)
        val start = CountDownLatch(1)
        try {
            val wins =
                (1..RACERS)
                    .map {
                        pool.submit<Boolean> {
                            start.await()
                            t.backup.consume(e.id, e.codes[0])
                        }
                    }.also { start.countDown() }
                    .map { it.get() }
            assertEquals(1, wins.count { it })
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `wrong backup codes and wrong TOTP codes share the per-account lockout`() {
        val t = TestAuth()
        val e = t.enabled("@trava_backup")
        // 5 tokens x 2 erros, cada um de um IP: so a trava por conta pode agir
        repeat(TOTP_MAX_FAILURES / 2) { i ->
            val ip = "10.2.0.$i"
            val token = t.challenge(e, ip)
            assertEquals(LoginResult.InvalidMfa, t.components.auth.loginMfa(ip, token, WRONG_BACKUP, MfaFactor.BACKUP))
            assertEquals(LoginResult.InvalidMfa, t.components.auth.loginMfa(ip, token, WRONG_TOTP))
        }
        assertTrue(t.mfa.totpLockedSeconds(e.id) in 1..TOTP_LOCK_MS / 1000)
        assertIs<LoginResult.Throttled>(t.loginWithBackup(e, e.codes[0], "10.2.0.99"), "nem o codigo certo passa")
        t.now += TOTP_LOCK_MS + 1_000
        assertIs<LoginResult.Success>(t.loginWithBackup(e, e.codes[0], "10.2.0.98"), "o codigo nao foi gasto na trava")
    }

    @Test
    fun `admin logs in with a backup code and reaches the admin gate`() {
        val t = TestAuth()
        val e = t.enabled("@admin_backup", ROLE_ADMIN)
        val ok = assertIs<LoginResult.Success>(t.loginWithBackup(e, e.codes[3]))
        val principal = assertNotNull(t.access.verifyPrincipal(ok.tokens.accessToken, t.now))
        assertTrue(principal.mfa && t.mfa.isAdmin(principal.accountId))
        assertEquals(TotpDisableResult.ADMIN_MANDATORY, t.mfa.disable(e.id, STRONG_PASSWORD, WRONG_TOTP))
    }

    @Test
    fun `regenerate needs password plus TOTP or backup code and invalidates the old batch`() {
        val t = TestAuth()
        authApp(t.components) {
            val access = signupAndLogin("@regenera").text("access_token")
            val started = httpPost("/me/mfa/totp", "{}", access).json()
            val secret = base32Decode(started.text("secret"))
            val confirmed = httpPost("/me/mfa/totp/confirm", """{"code":"${t.code(secret)}"}""", access)
            assertEquals(HttpStatusCode.OK, confirmed.status)
            val first =
                confirmed
                    .json()
                    .getValue("backup_codes")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(BACKUP_CODE_COUNT, first.size)
            t.now += 2 * STEP_MS

            val path = "/me/mfa/totp/backup-codes"
            val badPassword = httpPost(path, """{"password":"errada","backup_code":"${first[0]}"}""", access)
            assertEquals(HttpStatusCode.Forbidden, badPassword.status)
            val both = """{"password":"$STRONG_PASSWORD","code":"${t.code(secret)}","backup_code":"${first[0]}"}"""
            assertEquals(HttpStatusCode.BadRequest, httpPost(path, both, access).status)
            assertEquals(
                HttpStatusCode.BadRequest,
                httpPost(path, """{"password":"$STRONG_PASSWORD"}""", access).status,
            )

            val viaBackup = httpPost(path, """{"password":"$STRONG_PASSWORD","backup_code":"${first[0]}"}""", access)
            assertEquals(HttpStatusCode.OK, viaBackup.status)
            val second =
                viaBackup
                    .json()
                    .getValue("backup_codes")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(BACKUP_CODE_COUNT, second.size)
            assertTrue(first.intersect(second.toSet()).isEmpty())

            val id = t.accounts.findByHandle("@regenera")!!.id
            val e = Enabled(id, "@regenera", secret, second)
            assertEquals(LoginResult.InvalidMfa, t.loginWithBackup(e, first[1]), "lote antigo morreu")

            val viaTotp = httpPost(path, """{"password":"$STRONG_PASSWORD","code":"${t.code(secret)}"}""", access)
            assertEquals(HttpStatusCode.OK, viaTotp.status)
            val third =
                viaTotp
                    .json()
                    .getValue("backup_codes")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(LoginResult.InvalidMfa, t.loginWithBackup(e, second[0], "10.1.0.7"))
            assertIs<LoginResult.Success>(
                t.loginWithBackup(e.let { Enabled(id, it.handle, secret, third) }, third[0], "10.1.0.8"),
            )
            assertEquals(2, t.notices.events.count { it.second == SecurityEvent.BACKUP_CODES_REGENERATED })
        }
    }

    @Test
    fun `login accepts exactly one of code and backup_code`() {
        val t = TestAuth()
        authApp(t.components) {
            val access = signupAndLogin("@um_so").text("access_token")
            val secret = base32Decode(httpPost("/me/mfa/totp", "{}", access).json().text("secret"))
            httpPost("/me/mfa/totp/confirm", """{"code":"${t.code(secret)}"}""", access)
            t.now += 2 * STEP_MS
            val token =
                httpPost(
                    "/auth/token",
                    """{"handle":"@um_so","password":"$STRONG_PASSWORD"}""",
                ).json().text("mfa_token")
            val none = httpPost("/auth/login/mfa", """{"mfa_token":"$token"}""")
            assertEquals(HttpStatusCode.BadRequest, none.status)
            val both =
                httpPost("/auth/login/mfa", """{"mfa_token":"$token","code":"123456","backup_code":"AAAAAAAAAA"}""")
            assertEquals(HttpStatusCode.BadRequest, both.status)
            val wrong = httpPost("/auth/login/mfa", """{"mfa_token":"$token","backup_code":"AAAAAAAAAA"}""")
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertEquals("invalid_mfa", wrong.json().text("detail"))
        }
    }

    @Test
    fun `disabling TOTP wipes the backup codes`() {
        val t = TestAuth()
        val e = t.enabled("@desliga")
        assertEquals(TotpDisableResult.DONE, t.mfa.disable(e.id, STRONG_PASSWORD, t.code(e.secret)))
        assertTrue(t.backupRepo.storedHashes(e.id).isEmpty())
        assertFalse(t.backup.consume(e.id, e.codes[0]))
    }

    @Test
    fun `verified channels are told when a code is used or regenerated, and the messages carry no code`() {
        val t = TestAuth()
        val e = t.enabled("@avisos")
        val contacts = InMemoryContactRepository()
        contacts.setVerified(e.id, Channel.PHONE, FAKE_PHONE, Instant.EPOCH)
        contacts.store(e.id, Channel.EMAIL, FAKE_EMAIL)
        val sender = FakeSender()
        val notifier =
            ContactSecurityNotifier(contacts, MessageGateway(sender, roomyLimits { t.now })) { runBlocking { it() } }
        val mfa = MfaService(t.accounts, t.totp, t.backup, notifier, t.hasher, t.access, { t.now })
        val regenerated =
            assertIs<BackupRegenerateResult.Done>(
                mfa.regenerateBackupCodes(e.id, STRONG_PASSWORD, e.codes[0], MfaFactor.BACKUP),
            )
        assertEquals(setOf(FAKE_PHONE), sender.sent.map { it.to }.toSet(), "so canal verificado")
        assertEquals(2, sender.sent.size, "uso do codigo + regeneracao")
        val body = sender.sent.joinToString("\n") { it.text }
        (e.codes + regenerated.codes).forEach { assertFalse(body.contains(it)) }
    }

    @Test
    fun `backup codes never reach the logs`() {
        val t = TestAuth()
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger("br.malhaviva") as Logger
        root.addAppender(appender)
        val seen = mutableListOf<String>()
        try {
            val e = t.enabled("@sigilo_backup")
            seen += e.codes
            t.loginWithBackup(e, WRONG_BACKUP)
            t.loginWithBackup(e, e.codes[0], "10.3.0.1")
            t.loginWithBackup(e, e.codes[0], "10.3.0.2")
            seen +=
                assertIs<BackupRegenerateResult.Done>(
                    t.mfa.regenerateBackupCodes(e.id, STRONG_PASSWORD, e.codes[1], MfaFactor.BACKUP),
                ).codes
        } finally {
            root.detachAppender(appender)
        }
        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        (seen + WRONG_BACKUP).forEach { assertFalse(logs.contains(it), "codigo no log") }
    }
}
