package br.malhaviva.platform

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration
import java.util.concurrent.ConcurrentMap

/**
 * Mapa limitado e com expiracao por inatividade (Caffeine). Evita que chaves vindas da rede (IP, handle, token)
 * cresçam sem fim. [map] e um ConcurrentMap comum: compute, merge e computeIfAbsent seguem atomicos.
 * shortcut: estado de um unico no; ao escalar horizontalmente trocar por Valkey com TTL.
 */
class ExpiringMap<K : Any, V : Any>(
    maxSize: Long,
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val cache: Cache<K, V> =
        Caffeine
            .newBuilder()
            .maximumSize(maxSize)
            .expireAfterAccess(ttl)
            .ticker(ticker)
            .executor(Runnable::run)
            .build()

    val map: ConcurrentMap<K, V> get() = cache.asMap()

    /** Entradas vivas depois de varrer as vencidas (a varredura do Caffeine anda em lotes: repete ate estabilizar). */
    fun size(): Long {
        var before: Long
        do {
            before = cache.estimatedSize()
            cache.cleanUp()
        } while (cache.estimatedSize() < before)
        return cache.estimatedSize()
    }
}

/** Tamanho padrao das tabelas de estado por chave de rede. */
const val DEFAULT_MAX_KEYS = 200_000L
