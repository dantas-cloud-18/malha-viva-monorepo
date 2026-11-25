package br.malhaviva.tracking.ingest

import br.malhaviva.platform.DEFAULT_MAX_KEYS
import br.malhaviva.platform.ExpiringMap
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/**
 * Balde de fichas por chave. A chave inativa por mais que o tempo de reencher o balde some (sem perda: ele ja
 * estaria cheio) e a tabela tem teto, entao chaves vindas da rede nao crescem sem fim.
 */
class RateLimiter(
    private val capacity: Double,
    private val refillPerSecond: Double,
    maxKeys: Long = DEFAULT_MAX_KEYS,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private class Bucket(
        var tokens: Double,
        var lastMs: Long,
    )

    // tempo de reencher o balde (sem perda ao esquecer a chave), limitado a um dia quando a recarga e quase nula
    private val buckets =
        ExpiringMap<String, Bucket>(maxKeys, Duration.ofSeconds(refillSeconds(capacity, refillPerSecond)), ticker)

    fun tryAcquire(
        key: String,
        nowMs: Long,
    ): Boolean {
        val bucket = buckets.map.computeIfAbsent(key) { Bucket(capacity, nowMs) }
        return synchronized(bucket) {
            val elapsedS = maxOf(0L, nowMs - bucket.lastMs) / MS_PER_SECOND
            bucket.tokens = minOf(capacity, bucket.tokens + elapsedS * refillPerSecond)
            bucket.lastMs = maxOf(bucket.lastMs, nowMs)
            val ok = bucket.tokens >= 1.0
            if (ok) bucket.tokens -= 1.0
            ok
        }
    }

    /** Chaves vivas (para testes e metricas). */
    fun trackedKeys(): Long = buckets.size()

    private companion object {
        const val MS_PER_SECOND = 1000.0
        const val MAX_TTL_S = 86_400.0

        fun refillSeconds(
            capacity: Double,
            refillPerSecond: Double,
        ): Long = minOf(MAX_TTL_S, capacity / maxOf(refillPerSecond, Double.MIN_VALUE)).toLong() + 1
    }
}
