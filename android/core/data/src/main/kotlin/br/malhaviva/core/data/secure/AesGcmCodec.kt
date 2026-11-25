package br.malhaviva.core.data.secure

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM puro (sem Android). Formato do blob: IV (12 bytes) + ciphertext + tag (16 bytes).
 * O IV e gerado pelo provedor (aleatorio por escrita; no Keystore o provedor o exige).
 * O AAD amarra o blob ao nome da entrada, impedindo trocar o ciphertext de uma entrada por outra.
 */
class AesGcmCodec(
    private val key: SecretKey,
) {
    fun encrypt(
        plain: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "IV inesperado: ${iv.size} bytes" }
        return iv + cipher.doFinal(plain)
    }

    /** Lanca [GeneralSecurityException] se o blob foi adulterado, truncado ou a chave/AAD nao confere. */
    fun decrypt(
        blob: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (blob.size < IV_BYTES + TAG_BYTES) throw AEADBadTagException("blob curto demais")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
