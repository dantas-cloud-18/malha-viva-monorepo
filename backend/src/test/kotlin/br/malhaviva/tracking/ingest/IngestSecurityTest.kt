package br.malhaviva.tracking.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestSecurityTest {
    private val key = "chave-de-teste-com-32-bytes-ok!!".toByteArray()
    private val body = """{"samples":[]}""".toByteArray()
    private val now = 1_800_000_000_000L
    private val nonce = "nonce-0123456789ab"

    private fun verify(
        ts: Long = now,
        n: String = nonce,
        b: ByteArray = body,
        sig: String = BatchSecurity.sign(key, ts, n, body),
        store: NonceStore,
    ) = BatchSecurity.verify(key, ts, n, b, sig, now, store)

    @Test
    fun `assinatura valida passa`() {
        assertEquals(VerifyResult.Ok, verify(store = InMemoryNonceStore()))
    }

    @Test
    fun `replay do mesmo nonce e rejeitado`() {
        val store = InMemoryNonceStore()
        assertEquals(VerifyResult.Ok, verify(store = store))
        assertEquals(VerifyResult.Replay, verify(store = store))
    }

    @Test
    fun `assinatura ruim nao gasta o nonce`() {
        val store = InMemoryNonceStore()
        assertEquals(VerifyResult.BadSignature, verify(sig = "ruim", store = store))
        assertEquals(VerifyResult.Ok, verify(store = store))
    }

    @Test
    fun `corpo adulterado falha`() {
        val tampered = """{"samples":[1]}""".toByteArray()
        assertEquals(VerifyResult.BadSignature, verify(b = tampered, store = InMemoryNonceStore()))
    }

    @Test
    fun `timestamp fora de 60 s falha e 59 s passa`() {
        val tooOld = now - 61_000
        val tooNew = now + 61_000
        assertEquals(
            VerifyResult.BadTimestamp,
            verify(ts = tooOld, sig = BatchSecurity.sign(key, tooOld, nonce, body), store = InMemoryNonceStore()),
        )
        assertEquals(
            VerifyResult.BadTimestamp,
            verify(ts = tooNew, sig = BatchSecurity.sign(key, tooNew, nonce, body), store = InMemoryNonceStore()),
        )
        val edge = now - 59_000
        val ok = verify(ts = edge, sig = BatchSecurity.sign(key, edge, nonce, body), store = InMemoryNonceStore())
        assertEquals(VerifyResult.Ok, ok)
    }

    private fun sample(
        seq: Long,
        ts: Long = now - 100_000 + seq * 1000,
        lat: Double = -23.55,
        speed: Double? = null,
        mock: Boolean = false,
    ) = IngestSample(seq, ts, lat, -46.63, 5.0, speed, mock)

    private fun validate(
        vararg samples: IngestSample,
        lastSeq: Long? = null,
        off: (IngestSample) -> Boolean = { false },
    ) = validateSamples(samples.toList(), now, lastSeq, off)

    @Test
    fun `amostra valida e aceita`() {
        val r = validate(sample(1, ts = now - 5000))
        assertEquals(1, r.accepted.size)
        assertTrue(r.rejected.isEmpty())
    }

    @Test
    fun `mock location rejeitada`() {
        assertEquals(mapOf("MOCK" to 1), validate(sample(1, mock = true)).rejected)
    }

    @Test
    fun `precisao ruim rejeitada`() {
        assertEquals(mapOf("ACCURACY" to 1), validate(sample(1).copy(accuracyM = 80.0)).rejected)
    }

    @Test
    fun `fora do corredor rejeitada`() {
        assertEquals(mapOf("OFF_CORRIDOR" to 1), validate(sample(1), off = { true }).rejected)
    }

    @Test
    fun `velocidade reportada absurda rejeitada`() {
        assertEquals(mapOf("IMPLAUSIBLE_SPEED" to 1), validate(sample(1, speed = 90.0)).rejected)
    }

    @Test
    fun `salto de posicao implica velocidade absurda`() {
        val a = sample(1, ts = now - 20_000)
        val b = sample(2, ts = now - 10_000, lat = -23.45)
        val r = validate(a, b)
        assertEquals(listOf(a), r.accepted)
        assertEquals(mapOf("IMPLAUSIBLE_SPEED" to 1), r.rejected)
    }

    @Test
    fun `mesmo instante com deslocamento e implausivel`() {
        val a = sample(1, ts = now - 10_000)
        val b = sample(2, ts = now - 10_000, lat = -23.5499)
        assertEquals(mapOf("IMPLAUSIBLE_SPEED" to 1), validate(a, b).rejected)
    }

    @Test
    fun `amostra velha ou do futuro e STALE`() {
        val old = sample(1, ts = now - SamplePolicy.MAX_AGE_MS - 1)
        val future = sample(2, ts = now + SamplePolicy.MAX_FUTURE_MS + 1)
        assertEquals(mapOf("STALE" to 2), validate(old, future).rejected)
    }

    @Test
    fun `seq repetida ou antiga e duplicada`() {
        val r = validate(sample(5), sample(5), sample(4), sample(6), lastSeq = null)
        assertEquals(listOf(5L, 6L), r.accepted.map { it.seq })
        assertEquals(mapOf("DUPLICATE" to 2), r.rejected)
        assertEquals(mapOf("DUPLICATE" to 1), validate(sample(3), lastSeq = 3).rejected)
    }

    @Test
    fun `limitador bloqueia rajada e recarrega`() {
        val rl = RateLimiter(capacity = 3.0, refillPerSecond = 1.0)
        repeat(3) { assertTrue(rl.tryAcquire("a", 0)) }
        assertFalse(rl.tryAcquire("a", 0))
        assertFalse(rl.tryAcquire("a", 500))
        assertTrue(rl.tryAcquire("a", 1000))
        assertFalse(rl.tryAcquire("a", 1000))
    }

    @Test
    fun `chaves do limitador sao independentes`() {
        val rl = RateLimiter(capacity = 1.0, refillPerSecond = 0.1)
        assertTrue(rl.tryAcquire("a", 0))
        assertFalse(rl.tryAcquire("a", 0))
        assertTrue(rl.tryAcquire("b", 0))
    }
}
