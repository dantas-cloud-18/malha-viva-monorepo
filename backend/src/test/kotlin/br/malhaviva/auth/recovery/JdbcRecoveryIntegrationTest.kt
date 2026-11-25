package br.malhaviva.auth.recovery

import br.malhaviva.PostgresIntegrationTest
import br.malhaviva.auth.JdbcAccountRepository
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OTP de recuperacao, contatos e trocas de contato nos repositorios JDBC, contra Postgres de verdade. */
class JdbcRecoveryIntegrationTest : PostgresIntegrationTest() {
    private fun account(handle: String) = JdbcAccountRepository(ds).create(handle, "hash")!!

    private fun otp(
        acc: UUID,
        hmac: String,
        createdAt: Instant,
        purpose: OtpPurpose = OtpPurpose.RECOVERY,
        channel: Channel = Channel.PHONE,
    ) = OtpRecord(UUID.randomUUID(), acc, purpose, channel, hmac, createdAt.plusSeconds(600), 0, null, createdAt)

    @Test
    fun `otp replace invalidates the previous one and latest returns the newest`() {
        val repo = JdbcOtpRepository(ds)
        val acc = account("@otp_um")
        val now = Instant.now()
        val a = otp(acc, "hmac-a", now)
        repo.replace(a)
        val latestA = assertNotNull(repo.latest(acc, OtpPurpose.RECOVERY, Channel.PHONE))
        assertEquals(a.id, latestA.id)
        assertEquals("hmac-a", latestA.codeHmac)
        assertEquals(0, latestA.attempts)
        assertNull(latestA.consumedAt)

        val b = otp(acc, "hmac-b", now.plusSeconds(1))
        repo.replace(b)
        assertEquals(b.id, repo.latest(acc, OtpPurpose.RECOVERY, Channel.PHONE)!!.id)
        assertNotNull(
            scalars("SELECT consumed_at FROM otp_codes WHERE otp_id = ?", a.id).single(),
            "o antigo foi invalidado",
        )
        assertNull(repo.latest(acc, OtpPurpose.RECOVERY, Channel.EMAIL))
        assertNull(repo.latest(acc, OtpPurpose.VERIFY_CONTACT, Channel.PHONE))
    }

    @Test
    fun `otp attempts count atomically and consume is single use`() {
        val repo = JdbcOtpRepository(ds)
        val acc = account("@otp_dois")
        val rec = otp(acc, "hmac", Instant.now())
        repo.replace(rec)
        assertEquals(1, repo.addAttempt(rec.id))
        assertEquals(2, repo.addAttempt(rec.id))
        assertEquals(Int.MAX_VALUE, repo.addAttempt(UUID.randomUUID()), "id desconhecido conta como estourado")

        val counts = race(8) { repo.addAttempt(rec.id) }
        assertEquals((3..10).toSet(), counts.toSet(), "nenhuma tentativa se perde sob concorrencia")
        assertEquals(10, repo.latest(acc, OtpPurpose.RECOVERY, Channel.PHONE)!!.attempts)

        val wins = race(8) { repo.consume(rec.id, Instant.now()) }
        assertEquals(1, wins.count { it })
        assertFalse(repo.consume(rec.id, Instant.now()))
        assertNotNull(repo.latest(acc, OtpPurpose.RECOVERY, Channel.PHONE)!!.consumedAt)
    }

    @Test
    fun `contacts are stored unverified, verified once, and a verified contact is unique across accounts`() {
        val repo = JdbcContactRepository(ds)
        val me = account("@contato_um")
        val other = account("@contato_dois")
        assertEquals(ContactInfo(), repo.find(me))

        repo.store(me, Channel.PHONE, FAKE_PHONE)
        assertEquals(ContactInfo(phone = FAKE_PHONE), repo.find(me))
        repo.store(other, Channel.PHONE, FAKE_PHONE)
        assertTrue(repo.setVerified(me, Channel.PHONE, FAKE_PHONE, Instant.now()))
        assertEquals(listOf(Channel.PHONE), repo.find(me).verifiedChannels())
        assertFalse(repo.setVerified(other, Channel.PHONE, FAKE_PHONE, Instant.now()), "ja verificado por outra conta")
        assertFalse(repo.find(other).phoneVerified)

        assertTrue(repo.setVerified(me, Channel.EMAIL, "Teste@Example.test", Instant.now()))
        assertFalse(repo.setVerified(other, Channel.EMAIL, "teste@example.test", Instant.now()), "e-mail sem caixa")
        repo.store(me, Channel.PHONE, FAKE_PHONE_2)
        assertFalse(repo.find(me).phoneVerified, "trocar o valor zera a verificacao")
        assertTrue(repo.find(me).emailVerified)
    }

    @Test
    fun `contact changes move from open to confirmed, applied or cancelled`() {
        val repo = JdbcContactChangeRepository(ds)
        val me = account("@troca_um")
        val other = account("@troca_dois")
        val now = Instant.now()

        fun change(
            channel: Channel,
            value: String,
            at: Instant,
        ) = ContactChange(UUID.randomUUID(), me, channel, value, at, at.plusSeconds(86_400))
        val phone = change(Channel.PHONE, FAKE_PHONE_2, now)
        val email = change(Channel.EMAIL, FAKE_EMAIL_2, now.plusSeconds(1))
        repo.create(phone)
        repo.create(email)

        val found = assertNotNull(repo.find(phone.id))
        assertEquals(Channel.PHONE, found.channel)
        assertEquals(FAKE_PHONE_2, found.newValue)
        assertTrue(found.open)
        assertEquals(listOf(phone.id, email.id), repo.open(me).map { it.id })
        repo.markConfirmed(phone.id, now)
        assertNotNull(repo.find(phone.id)!!.confirmedAt)
        assertEquals(2, repo.open(me).size, "confirmada, mas ainda aberta ate aplicar ou cancelar")

        assertEquals(1, repo.cancel(me, Channel.PHONE, now))
        assertEquals(listOf(email.id), repo.open(me).map { it.id })
        assertFalse(repo.cancelOne(other, email.id, now), "troca de outra conta")
        assertTrue(repo.cancelOne(me, email.id, now))
        assertFalse(repo.cancelOne(me, email.id, now))

        val third = change(Channel.EMAIL, "terceiro@example.test", now.plusSeconds(2))
        repo.create(third)
        repo.markApplied(third.id, now)
        assertEquals(emptyList(), repo.open(me))
        assertEquals(0, repo.cancel(me, null, now))
        val fourth = change(Channel.PHONE, "+5521999990002", now.plusSeconds(3))
        repo.create(fourth)
        assertEquals(1, repo.cancel(me, null, now))
        assertEquals(emptyList(), repo.open(me))
    }
}
