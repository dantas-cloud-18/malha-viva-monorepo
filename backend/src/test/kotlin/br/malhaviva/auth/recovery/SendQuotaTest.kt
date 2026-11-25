package br.malhaviva.auth.recovery

import br.malhaviva.tracking.ingest.RateLimiter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val RECOVERY = SendContext(SendPurpose.RECOVERY)

private fun signup(ip: String) = SendContext(SendPurpose.SIGNUP, ip)

class SendQuotaTest {
    private val now = 1_800_000_000_000L
    private val acc = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun roomy() = RateLimiter(1000.0, 1.0)

    @Test
    fun `exhausting the signup ceiling never touches the reserved recovery quota`() {
        val limits =
            SendLimits(
                clock = { now },
                ddi = roomy(),
                target = roomy(),
                account = roomy(),
                openDdi = roomy(),
                openTarget = roomy(),
                signupIp = roomy(),
                signupSms = SpendCeiling("sms-signup", 2) { now },
                recoverySms = SpendCeiling("sms-recovery", 2) { now },
                sms = SpendCeiling("sms", 1) { now },
            )
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990001", signup("1.1.1.1")))
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990002", signup("1.1.1.1")))
        assertFalse(limits.allow(acc, Channel.PHONE, "+5521999990003", signup("1.1.1.1")), "cadastro esgotou a cota")
        // outros envios (avisos) tem cota propria e tambem nao afetam a recuperacao
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990004"))
        assertFalse(limits.allow(acc, Channel.PHONE, "+5521999990005"))
        assertTrue(limits.allow(other, Channel.PHONE, "+5521999990010", RECOVERY))
        assertTrue(limits.allow(other, Channel.PHONE, "+5521999990011", RECOVERY))
        assertFalse(limits.allow(other, Channel.PHONE, "+5521999990012", RECOVERY), "recuperacao tem teto proprio")
    }

    @Test
    fun `signup sends are limited per ip`() {
        val limits =
            SendLimits(
                clock = { now },
                openDdi = roomy(),
                openTarget = roomy(),
                account = roomy(),
                signupIp = RateLimiter(2.0, 0.0001),
            )
        assertTrue(limits.allow(acc, Channel.EMAIL, "a@example.test", signup("9.9.9.9")))
        assertTrue(limits.allow(acc, Channel.EMAIL, "b@example.test", signup("9.9.9.9")))
        assertFalse(limits.allow(acc, Channel.EMAIL, "c@example.test", signup("9.9.9.9")), "teto por IP")
        assertTrue(limits.allow(acc, Channel.EMAIL, "c@example.test", signup("9.9.9.8")), "outro IP segue livre")
    }

    @Test
    fun `signup calling code and number buckets are separate from recovery`() {
        val limits =
            SendLimits(
                clock = { now },
                ddi = RateLimiter(5.0, 0.0001),
                target = RateLimiter(5.0, 0.0001),
                account = roomy(),
                openDdi = RateLimiter(1.0, 0.0001),
                openTarget = RateLimiter(1.0, 0.0001),
                signupIp = roomy(),
            )
        assertTrue(limits.allow(acc, Channel.PHONE, "+5521999990001", signup("1.1.1.1")))
        assertFalse(limits.allow(acc, Channel.PHONE, "+5521988880002", signup("1.1.1.2")), "DDI +55 do cadastro cheio")
        // enchi o DDI e o alvo do cadastro com o numero da vitima: a recuperacao dela segue funcionando
        assertTrue(limits.allow(other, Channel.PHONE, "+5521999990001", RECOVERY))
        assertTrue(limits.allow(other, Channel.PHONE, "+5521988880003", RECOVERY))
    }
}
