package br.malhaviva.auth.recovery

import br.malhaviva.tracking.ingest.RateLimiter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtpAndTotpTest {
    private var now = 1_800_000_000_000L
    private val repo = InMemoryOtpRepository()
    private val otp = OtpService(repo, TEST_OTP_KEY, { now })
    private val account = UUID.randomUUID()

    private fun issue(ref: String = "") = otp.issue(account, OtpPurpose.RECOVERY, Channel.PHONE, ref)

    private fun check(
        code: String?,
        ref: String = "",
    ) = otp.check(account, OtpPurpose.RECOVERY, Channel.PHONE, ref, code)

    @Test
    fun `otp has six digits and is stored only as an hmac`() {
        val code = issue()
        assertTrue(Regex("^[0-9]{6}$").matches(code))
        val stored = repo.rows.values.single()
        assertFalse(stored.codeHmac.contains(code))
        assertEquals(64, stored.codeHmac.length)
    }

    @Test
    fun `otp is single use`() {
        val code = issue()
        val rec = assertNotNull(check(code))
        assertTrue(otp.consume(rec))
        assertFalse(otp.consume(rec))
        assertNull(check(code))
    }

    @Test
    fun `otp expires after ten minutes`() {
        val code = issue()
        now += 9 * 60_000
        assertNotNull(check(code))
        now += 60_000 + 1
        assertNull(check(code))
    }

    @Test
    fun `otp is invalidated after five attempts even with the right code`() {
        val code = issue()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(OTP_MAX_ATTEMPTS) { assertNull(check(wrong)) }
        assertNull(check(code))
    }

    @Test
    fun `otp is bound to purpose, channel and reference and a new one replaces the old`() {
        val code = issue("ref-a")
        assertNull(check(code, "ref-b"))
        assertNull(otp.check(account, OtpPurpose.VERIFY_CONTACT, Channel.PHONE, "ref-a", code))
        assertNull(otp.check(account, OtpPurpose.RECOVERY, Channel.EMAIL, "ref-a", code))
        assertNotNull(check(code, "ref-a"))
        val fresh = issue("ref-a")
        assertNull(check(code.takeIf { it != fresh } ?: "x", "ref-a"))
        assertNotNull(check(fresh, "ref-a"))
    }

    @Test
    fun `otp key is required and must be long enough`() {
        assertFailsWith<IllegalStateException> { otpKeyFromEnv { null } }
        assertFailsWith<IllegalStateException> { otpKeyFromEnv { "c2hvcnQ=" } }
        assertEquals(
            32,
            otpKeyFromEnv {
                java.util.Base64
                    .getEncoder()
                    .encodeToString(TEST_OTP_KEY)
            }.size,
        )
    }

    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun `totp matches the RFC 6238 SHA1 test vectors`() {
        mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        ).forEach { (seconds, expected) ->
            assertEquals(expected, Totp.generate(rfcSecret, Totp.step(seconds * 1000), digits = 8), "t=$seconds")
        }
        assertEquals("287082", Totp.generate(rfcSecret, 1))
    }

    @Test
    fun `totp accepts plus or minus one step and refuses reuse`() {
        val t = 1_111_111_111_000L
        val step = Totp.step(t)
        assertEquals(step, Totp.verify(rfcSecret, Totp.generate(rfcSecret, step), t, 0))
        assertEquals(step - 1, Totp.verify(rfcSecret, Totp.generate(rfcSecret, step - 1), t, 0))
        assertEquals(step + 1, Totp.verify(rfcSecret, Totp.generate(rfcSecret, step + 1), t, 0))
        assertNull(Totp.verify(rfcSecret, Totp.generate(rfcSecret, step + 2), t, 0))
        assertNull(Totp.verify(rfcSecret, Totp.generate(rfcSecret, step - 2), t, 0))
        assertNull(Totp.verify(rfcSecret, Totp.generate(rfcSecret, step), t, step))
    }

    @Test
    fun `totp base32 encodes like RFC 4648`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
    }

    @Test
    fun `totp enrollment stores the secret encrypted, confirms once and never accepts a step twice`() {
        val totpRepo = InMemoryTotpRepository()
        val svc = TotpService(totpRepo, TEST_OTP_KEY, { now })
        val enrollment = assertNotNull(svc.enroll(account, "@teste"))
        assertFalse(svc.isEnabled(account))
        val secret = enrollment.secretBase32
        assertFalse(String(totpRepo.rows.getValue(account).secretEnc, Charsets.ISO_8859_1).contains(secret))
        assertFalse(svc.verify(account, "000000"))
        val raw = decodeBase32(secret)
        val code = Totp.generate(raw, Totp.step(now))
        assertTrue(svc.confirm(account, code))
        assertTrue(svc.isEnabled(account))
        assertNull(svc.enroll(account, "@teste"))
        assertFalse(svc.verify(account, code), "o passo usado na confirmacao nao pode ser reaproveitado")
        now += 30_000
        val next = Totp.generate(raw, Totp.step(now))
        assertTrue(svc.verify(account, next))
        assertFalse(svc.verify(account, next))
    }

    private fun decodeBase32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in s) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `send limits apply per calling code, per number, per account and per day`() {
        val limits =
            SendLimits(
                clock = { now },
                ddi = RateLimiter(3.0, 0.0001),
                target = RateLimiter(1.0, 0.0001),
                account = RateLimiter(100.0, 1.0),
                sms = SpendCeiling("sms", 3) { now },
            )
        val acc = UUID.randomUUID()
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990001"))
        assertFalse(limits.allow(acc, Channel.PHONE, "+5521999990001"), "por numero")
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990002"))
        assertFalse(limits.allow(acc, Channel.PHONE, "+5521999990003"), "por DDI (+55)")
        assertTrue(limits.allow(acc, Channel.PHONE, "+14155550100"), "outro DDI segue livre")
        assertFalse(limits.allow(acc, Channel.PHONE, "+14155550101"), "teto diario de 3 SMS")
        assertTrue(limits.allow(acc, Channel.EMAIL, FAKE_EMAIL), "e-mail tem teto proprio")
        now += 86_400_000
        assertTrue(limits.allow(acc, Channel.PHONE, "+14155550101"), "teto reinicia no dia seguinte")
    }

    @Test
    fun `calling code lookup handles one, two and three digit codes`() {
        assertEquals("1", callingCode("+14155550100"))
        assertEquals("55", callingCode("+5521999990000"))
        assertEquals("351", callingCode("+351912345678"))
        assertEquals("7", callingCode("+79001234567"))
    }

    @Test
    fun `spend ceiling raises a single alert without personal data`() {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger("br.malhaviva.auth.recovery") as Logger
        root.addAppender(appender)
        try {
            val limits =
                SendLimits(clock = { now }, sms = SpendCeiling("sms", 1) { now }, ddi = RateLimiter(100.0, 1.0))
            val gateway = MessageGateway(FakeSender(), limits)
            val acc = UUID.randomUUID()
            repeat(
                4,
            ) { i -> runBlocking { gateway.deliver(acc, Channel.PHONE, "+55219999900$i$i", "s", "texto 123456") } }
        } finally {
            root.detachAppender(appender)
        }
        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        assertEquals(1, Regex("ALERTA teto diario").findAll(logs).count(), logs)
        assertFalse(logs.contains("+5521"), logs)
        assertFalse(logs.contains("123456"), logs)
    }
}
