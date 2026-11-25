package br.malhaviva.platform

import br.malhaviva.auth.AccessTokenService
import br.malhaviva.auth.LoginGuard
import br.malhaviva.auth.MfaService
import br.malhaviva.auth.TEST_JWT_SECRET
import br.malhaviva.auth.TestAuth
import br.malhaviva.auth.recovery.RequestCounter
import br.malhaviva.tracking.ingest.RateLimiter
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Relogio de Caffeine controlavel: o tempo so anda quando o teste manda. */
class FakeTicker : Ticker {
    private var nanos = 0L

    override fun read(): Long = nanos

    fun advance(by: Duration) {
        nanos += by.toNanos()
    }
}

class ExpiringStateTest {
    private val ticker = FakeTicker()
    private val now = 1_800_000_000_000L

    @Test
    fun `expiring map drops idle entries and keeps the ones in use`() {
        val map = ExpiringMap<String, Int>(maxSize = 1_000, ttl = Duration.ofSeconds(10), ticker = ticker)
        map.map["velho"] = 1
        map.map["ativo"] = 2
        ticker.advance(Duration.ofSeconds(6))
        assertEquals(2, map.map["ativo"], "leitura renova o prazo")
        ticker.advance(Duration.ofSeconds(6))
        assertEquals(1L, map.size())
        assertEquals(null, map.map["velho"])
    }

    @Test
    fun `expiring map is bounded by its maximum size`() {
        val map = ExpiringMap<Int, Int>(maxSize = 100, ttl = Duration.ofHours(1), ticker = ticker)
        repeat(5_000) { map.map[it] = it }
        assertTrue(map.size() <= 100, "tamanho ${map.size()}")
    }

    @Test
    fun `rate limiter forgets idle keys without losing the refill and is bounded`() {
        val limiter = RateLimiter(5.0, 1.0, ticker = ticker)
        repeat(1_000) { assertTrue(limiter.tryAcquire("ip:$it", now)) }
        assertEquals(1_000L, limiter.trackedKeys())
        ticker.advance(Duration.ofSeconds(7))
        assertEquals(0L, limiter.trackedKeys(), "chave ociosa por mais que o tempo de reencher some")
        // a chave esquecida volta com o balde cheio, igual ao que o reabastecimento ja teria feito
        repeat(5) { assertTrue(limiter.tryAcquire("ip:1", now)) }

        val bounded = RateLimiter(5.0, 1.0, maxKeys = 100, ticker = ticker)
        repeat(5_000) { bounded.tryAcquire("k:$it", now) }
        assertTrue(bounded.trackedKeys() <= 100)
    }

    @Test
    fun `login guard state expires after the failure memory window`() {
        val guard = LoginGuard(ticker) { now }
        repeat(500) {
            guard.check("10.0.${it / 250}.${it % 250}", "@h$it")
            guard.recordFailure("10.0.${it / 250}.${it % 250}", "@h$it")
        }
        assertTrue(guard.trackedKeys() > 0)
        ticker.advance(Duration.ofMinutes(16))
        assertEquals(0L, guard.trackedKeys())
    }

    @Test
    fun `recovery request counter expires with its window`() {
        val counter = RequestCounter(ticker = ticker)
        repeat(300) { counter.hit("ip:$it", now) }
        assertEquals(300L, counter.trackedKeys())
        ticker.advance(Duration.ofMinutes(61))
        assertEquals(0L, counter.trackedKeys())
    }

    @Test
    fun `pending mfa challenges expire after five minutes`() {
        val t = TestAuth()
        val mfa =
            MfaService(t.accounts, t.totp, t.hasher, AccessTokenService(TEST_JWT_SECRET), { now }, ticker = ticker)
        repeat(50) { mfa.open(UUID.randomUUID(), "@h$it", null) }
        assertEquals(50L, mfa.pendingChallenges())
        ticker.advance(Duration.ofMinutes(6))
        assertEquals(0L, mfa.pendingChallenges())
    }
}
