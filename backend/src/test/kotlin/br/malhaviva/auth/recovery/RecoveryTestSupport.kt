package br.malhaviva.auth.recovery

import br.malhaviva.auth.AuthComponents
import br.malhaviva.auth.CaptchaVerifier
import br.malhaviva.auth.FakePwned
import br.malhaviva.auth.NoopCaptchaVerifier
import br.malhaviva.auth.PasswordPolicy
import br.malhaviva.auth.TestAuth
import br.malhaviva.tracking.ingest.RateLimiter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Chave falsa: so existe nos testes. */
val TEST_OTP_KEY = ByteArray(32) { (50 + it).toByte() }

const val FAKE_PHONE = "+5521999990000"
const val FAKE_PHONE_2 = "+5521999990001"
const val FAKE_EMAIL = "test@example.test"
const val FAKE_EMAIL_2 = "novo@example.test"
const val NEW_PASSWORD = "outra frase longa sobre trens do ramal"

data class Sent(
    val channel: Channel,
    val to: String,
    val text: String,
) {
    /** Codigo de 6 digitos da mensagem, se houver. */
    val code: String? get() = Regex("\\b(\\d{6})\\b").find(text)?.groupValues?.get(1)
}

class FakeSender(
    var failing: Boolean = false,
    private val also: MessageSender? = null,
) : MessageSender {
    val sent = CopyOnWriteArrayList<Sent>()

    override suspend fun sendSms(
        toE164: String,
        text: String,
    ) {
        record(Sent(Channel.PHONE, toE164, text))
        also?.sendSms(toE164, text)
    }

    override suspend fun sendEmail(
        to: String,
        subject: String,
        text: String,
    ) {
        record(Sent(Channel.EMAIL, to, text))
        also?.sendEmail(to, subject, text)
    }

    private fun record(s: Sent) {
        if (failing) throw MessageDeliveryException("provider down")
        sent += s
    }

    fun to(target: String) = sent.filter { it.to == target }

    fun lastCode(channel: Channel) = sent.last { it.channel == channel && it.code != null }.code!!
}

class InMemoryContactRepository : ContactRepository {
    private val rows = HashMap<UUID, ContactInfo>()

    @Synchronized
    override fun find(accountId: UUID) = rows[accountId] ?: ContactInfo()

    @Synchronized
    override fun store(
        accountId: UUID,
        channel: Channel,
        value: String,
    ) {
        val cur = find(accountId)
        rows[accountId] =
            if (channel == Channel.PHONE) {
                cur.copy(phone = value, phoneVerified = false)
            } else {
                cur.copy(email = value, emailVerified = false)
            }
    }

    @Synchronized
    override fun setVerified(
        accountId: UUID,
        channel: Channel,
        value: String,
        now: Instant,
    ): Boolean {
        val takenByOther = rows.any { (id, c) -> id != accountId && c.verified(channel) && c.value(channel) == value }
        val cur = find(accountId)
        if (!takenByOther) {
            rows[accountId] =
                if (channel == Channel.PHONE) {
                    cur.copy(phone = value, phoneVerified = true)
                } else {
                    cur.copy(email = value, emailVerified = true)
                }
        }
        return !takenByOther
    }
}

class InMemoryContactChangeRepository : ContactChangeRepository {
    private val rows = LinkedHashMap<UUID, ContactChange>()

    @Synchronized
    override fun create(change: ContactChange) {
        rows[change.id] = change
    }

    @Synchronized
    override fun find(id: UUID) = rows[id]

    @Synchronized
    override fun open(accountId: UUID) = rows.values.filter { it.accountId == accountId && it.open }

    @Synchronized
    override fun markConfirmed(
        id: UUID,
        now: Instant,
    ) {
        rows[id] = rows.getValue(id).copy(confirmedAt = now)
    }

    @Synchronized
    override fun markApplied(
        id: UUID,
        now: Instant,
    ) {
        rows[id] = rows.getValue(id).copy(appliedAt = now)
    }

    @Synchronized
    override fun cancel(
        accountId: UUID,
        channel: Channel?,
        now: Instant,
    ): Int {
        val hit = open(accountId).filter { channel == null || it.channel == channel }
        hit.forEach { rows[it.id] = it.copy(cancelledAt = now) }
        return hit.size
    }

    @Synchronized
    override fun cancelOne(
        accountId: UUID,
        id: UUID,
        now: Instant,
    ): Boolean {
        val c = rows[id]?.takeIf { it.accountId == accountId && it.open }
        if (c != null) rows[id] = c.copy(cancelledAt = now)
        return c != null
    }
}

class InMemoryOtpRepository : OtpRepository {
    val rows = LinkedHashMap<UUID, OtpRecord>()

    @Synchronized
    override fun replace(record: OtpRecord) {
        rows.replaceAll { _, r ->
            if (sameSlot(r, record) &&
                r.consumedAt == null
            ) {
                r.copy(consumedAt = record.createdAt)
            } else {
                r
            }
        }
        rows[record.id] = record
    }

    private fun sameSlot(
        a: OtpRecord,
        b: OtpRecord,
    ) = a.accountId == b.accountId && a.purpose == b.purpose && a.channel == b.channel

    @Synchronized
    override fun latest(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
    ) = rows.values.lastOrNull { it.accountId == accountId && it.purpose == purpose && it.channel == channel }

    @Synchronized
    override fun addAttempt(id: UUID): Int {
        val r = rows.getValue(id)
        rows[id] = r.copy(attempts = r.attempts + 1)
        return r.attempts + 1
    }

    @Synchronized
    override fun consume(
        id: UUID,
        now: Instant,
    ): Boolean {
        val r = rows.getValue(id)
        if (r.consumedAt == null) rows[id] = r.copy(consumedAt = now)
        return r.consumedAt == null
    }
}

class InMemoryTotpRepository : TotpRepository {
    val rows = HashMap<UUID, TotpRecord>()

    @Synchronized
    override fun find(accountId: UUID) = rows[accountId]

    @Synchronized
    override fun save(
        accountId: UUID,
        secretEnc: ByteArray,
    ) {
        if (rows[accountId]?.enabled != true) rows[accountId] = TotpRecord(secretEnc, false, 0)
    }

    @Synchronized
    override fun enable(
        accountId: UUID,
        step: Long,
    ) {
        rows[accountId] = TotpRecord(rows.getValue(accountId).secretEnc, true, step)
    }

    @Synchronized
    override fun advance(
        accountId: UUID,
        expectedLast: Long,
        step: Long,
    ): Boolean {
        val r = rows.getValue(accountId)
        if (r.lastStep == expectedLast) rows[accountId] = TotpRecord(r.secretEnc, r.enabled, step)
        return r.lastStep == expectedLast
    }

    @Synchronized
    override fun delete(accountId: UUID) {
        rows.remove(accountId)
    }
}

/** Limites generosos: cada teste que quer limite apertado passa o seu. */
fun roomyLimits(clock: () -> Long) =
    SendLimits(
        clock = clock,
        ddi = RateLimiter(1000.0, 1.0),
        target = RateLimiter(1000.0, 1.0),
        account = RateLimiter(1000.0, 1.0),
    )

/** Plano de recuperacao inteiro em memoria, sobre o [TestAuth], com o mesmo relogio. */
class TestRecovery(
    val auth: TestAuth,
    val sender: MessageSender = FakeSender(),
    captcha: CaptchaVerifier = NoopCaptchaVerifier,
    sendLimits: SendLimits = roomyLimits { auth.now },
    limits: RecoveryLimits = RecoveryLimits(),
) {
    val fake: FakeSender get() = sender as FakeSender
    val contacts = InMemoryContactRepository()
    val changes = InMemoryContactChangeRepository()
    val otpRepo = InMemoryOtpRepository()
    val otp = OtpService(otpRepo, TEST_OTP_KEY, { auth.now })
    private val gateway = MessageGateway(sender, sendLimits)
    val contactService = ContactService(auth.accounts, contacts, changes, otp, gateway, auth.hasher, { auth.now })
    val service =
        RecoveryService(
            auth.accounts,
            contacts,
            contactService,
            changes,
            otp,
            gateway,
            auth.sessions,
            PasswordPolicy(FakePwned()),
            auth.hasher,
            captcha,
            limits,
            { auth.now },
            background = { runBlocking { it() } },
        )
    val components =
        AuthComponents(
            auth.components.auth,
            auth.sessions,
            auth.access,
            auth.components.limiters,
            { auth.now },
            RecoveryComponents(service, contactService),
        )
}
