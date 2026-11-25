package br.malhaviva.tracking.ingest

import br.malhaviva.platform.DEFAULT_MAX_KEYS
import br.malhaviva.platform.ExpiringMap
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration

/** Token anonimo de telemetria. Nao tem relacao com conta; classe comum porque guarda ByteArray. */
class TelemetryToken(
    val tokenId: String,
    val signingKey: ByteArray,
    val anonEpochId: String,
    val expiresAtMs: Long,
)

interface TelemetryTokenStore {
    fun find(tokenId: String): TelemetryToken?
}

/**
 * Para testes e desenvolvimento. O V2 nao tem tabela de tokens, entao nao ha versao JDBC. Token vencido some por
 * inatividade (o TTL cobre a vida do token) e a tabela tem teto, entao emissao em massa nao enche a memoria.
 */
class InMemoryTelemetryTokenStore(
    ticker: Ticker = Ticker.systemTicker(),
    maxTokens: Long = DEFAULT_MAX_KEYS,
) : TelemetryTokenStore {
    private val store = ExpiringMap<String, TelemetryToken>(maxTokens, Duration.ofMillis(TOKEN_TTL_MS), ticker)

    fun put(token: TelemetryToken) {
        store.map[token.tokenId] = token
    }

    override fun find(tokenId: String): TelemetryToken? = store.map[tokenId]

    /** Tokens vivos (para testes e metricas). */
    fun size(): Long = store.size()
}
