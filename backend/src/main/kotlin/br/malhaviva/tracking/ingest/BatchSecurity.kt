package br.malhaviva.tracking.ingest

import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class VerifyResult { Ok, BadTimestamp, BadSignature, Replay }

/** Guarda de nonces ja usados. Em producao: Valkey com TTL; aqui, memoria de um no. */
interface NonceStore {
    /** true na primeira vez que o nonce aparece. */
    fun firstUse(
        nonce: String,
        nowMs: Long,
    ): Boolean
}

class InMemoryNonceStore(
    private val ttlMs: Long = BatchSecurity.TOLERANCE_MS * 2,
) : NonceStore {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun firstUse(
        nonce: String,
        nowMs: Long,
    ): Boolean {
        seen.entries.removeIf { nowMs - it.value > ttlMs }
        return seen.putIfAbsent(nonce, nowMs) == null
    }
}

/** Assinatura do lote (contracts/openapi.yaml, /telemetry/batches). */
object BatchSecurity {
    const val TOLERANCE_MS = 60_000L
    private const val HMAC = "HmacSHA256"

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun sha256Hex(body: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))

    fun sign(
        key: ByteArray,
        timestampMs: Long,
        nonce: String,
        body: ByteArray,
    ): String {
        val input = "$timestampMs\n$nonce\n${sha256Hex(body)}"
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }
        return b64.encodeToString(mac.doFinal(input.toByteArray()))
    }

    /** Ordem: timestamp, assinatura, nonce. Assinatura invalida nao gasta nonce. */
    @Suppress("LongParameterList")
    fun verify(
        key: ByteArray,
        timestampMs: Long,
        nonce: String,
        body: ByteArray,
        signature: String,
        nowMs: Long,
        nonces: NonceStore,
    ): VerifyResult {
        val expected = sign(key, timestampMs, nonce, body)
        return when {
            abs(nowMs - timestampMs) > TOLERANCE_MS -> VerifyResult.BadTimestamp
            !MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray()) -> VerifyResult.BadSignature
            !nonces.firstUse(nonce, nowMs) -> VerifyResult.Replay
            else -> VerifyResult.Ok
        }
    }
}
