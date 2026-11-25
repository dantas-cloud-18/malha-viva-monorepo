package br.malhaviva.auth.recovery

import br.malhaviva.auth.STRONG_PASSWORD
import br.malhaviva.auth.TestAuth
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIN_MS = 120L

class RecoveryEqualizationTest {
    private val wrong = RecoveryCodes(phone = "123456", email = "654321")

    private fun complete(
        r: TestRecovery,
        handle: String,
    ): RecoveryCompleteResult = runBlocking { r.service.complete("10.1.1.1", handle, wrong, NEW_PASSWORD) }

    /** Trabalho de repositorio de uma chamada a `complete`: (leituras de contato, consultas de OTP). */
    private fun shape(
        r: TestRecovery,
        handle: String,
    ): Pair<Int, Int> {
        val finds = r.contacts.findCalls
        val latests = r.otpRepo.latestCalls
        assertEquals(RecoveryCompleteResult.InvalidCode, complete(r, handle))
        return (r.contacts.findCalls - finds) to (r.otpRepo.latestCalls - latests)
    }

    private fun account(
        t: TestAuth,
        r: TestRecovery,
        handle: String,
        vararg verified: Channel,
    ) {
        runBlocking { t.components.auth.register(handle, STRONG_PASSWORD) }
        val id = t.accounts.findByHandle(handle)!!.id
        verified.forEach {
            r.contacts.setVerified(id, it, if (it == Channel.PHONE) FAKE_PHONE else FAKE_EMAIL, Instant.EPOCH)
        }
    }

    @Test
    fun `complete does the same repository work for an unknown handle and for known accounts`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        account(t, r, "@um_canal", Channel.PHONE)
        account(t, r, "@dois_canais", Channel.PHONE, Channel.EMAIL)
        account(t, r, "@sem_canal")

        val unknown = shape(r, "@nao_existe")
        assertEquals(1 to Channel.entries.size, unknown, "uma leitura de contatos e uma consulta de OTP por canal")
        assertEquals(unknown, shape(r, "@um_canal"))
        assertEquals(unknown, shape(r, "@dois_canais"))
        assertEquals(unknown, shape(r, "@sem_canal"))
        assertEquals(unknown, shape(r, "sem-formato-valido"))
    }

    @Test
    fun `the failure path takes at least the minimum time for unknown and known handles alike`() {
        val t = TestAuth()
        val r = TestRecovery(t, minCompleteMs = MIN_MS)
        account(t, r, "@existe_tempo", Channel.PHONE)
        listOf("@nao_existe_tempo", "@existe_tempo").forEach {
            val started = System.nanoTime()
            assertEquals(RecoveryCompleteResult.InvalidCode, complete(r, it))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs >= MIN_MS - 10, "$it levou so $elapsedMs ms")
        }
    }
}
