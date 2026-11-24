package br.malhaviva.feature.tracking.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Assinatura do lote (contrato `ingestBatch`): HMAC-SHA256 sobre "{timestamp}\n{nonce}\n{sha256 hex do corpo}". */
object BatchSigner {
    private const val HMAC = "HmacSHA256"
    private const val NONCE_BYTES = 16
    private val random = SecureRandom()

    fun bodyHash(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    /** [signingKey] em base64url (como vem do token); resultado em base64url sem padding. */
    fun signature(
        signingKey: String,
        timestampMs: Long,
        nonce: String,
        body: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(signingKey), HMAC))
        val message = "$timestampMs\n$nonce\n${bodyHash(body)}".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
    }

    /** 32 caracteres hexadecimais (dentro de 16..64 do contrato), aleatorios e de uso unico. */
    fun newNonce(): String = ByteArray(NONCE_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
