package br.malhaviva.tracking.ingest

import java.util.concurrent.ConcurrentHashMap

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

/** Para testes e desenvolvimento. O V2 nao tem tabela de tokens, entao nao ha versao JDBC. */
class InMemoryTelemetryTokenStore : TelemetryTokenStore {
    private val tokens = ConcurrentHashMap<String, TelemetryToken>()

    fun put(token: TelemetryToken) {
        tokens[token.tokenId] = token
    }

    override fun find(tokenId: String): TelemetryToken? = tokens[tokenId]
}
