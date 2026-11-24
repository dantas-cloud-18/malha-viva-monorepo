package br.malhaviva.tracking.ingest

import java.util.concurrent.ConcurrentHashMap

/** Balde de fichas por chave. Chaves nunca expiram; em producao, limpar inativas. */
class RateLimiter(
    private val capacity: Double,
    private val refillPerSecond: Double,
) {
    private class Bucket(
        var tokens: Double,
        var lastMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(
        key: String,
        nowMs: Long,
    ): Boolean {
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity, nowMs) }
        return synchronized(bucket) {
            val elapsedS = maxOf(0L, nowMs - bucket.lastMs) / MS_PER_SECOND
            bucket.tokens = minOf(capacity, bucket.tokens + elapsedS * refillPerSecond)
            bucket.lastMs = maxOf(bucket.lastMs, nowMs)
            val ok = bucket.tokens >= 1.0
            if (ok) bucket.tokens -= 1.0
            ok
        }
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
