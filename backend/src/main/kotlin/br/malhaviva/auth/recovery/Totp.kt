package br.malhaviva.auth.recovery

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val STEP_SECONDS = 30L
private const val MS_PER_SECOND = 1000L
private const val TOTP_DIGITS = 6
private const val SECRET_BYTES = 20
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val LOW_NIBBLE = 0x0f
private const val BYTE_MASK = 0xff
private const val INT_SIGN_MASK = 0x7fffffff
private const val INT_BYTES = 4
private const val BASE32_BITS = 5
private const val BASE32_MASK = 0x1f
private const val BITS_PER_BYTE = 8
private val POWERS = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

/** RFC 6238 (TOTP) sobre RFC 4226 (HOTP): HMAC-SHA1, passo de 30 s, 6 digitos, janela de +-1 passo. JDK puro. */
object Totp {
    fun step(nowMs: Long): Long = nowMs / MS_PER_SECOND / STEP_SECONDS

    fun generate(
        secret: ByteArray,
        step: Long,
        digits: Int = TOTP_DIGITS,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and LOW_NIBBLE
        val binary =
            (0 until INT_BYTES).fold(0) { acc, i ->
                (acc shl BITS_PER_BYTE) or
                    (hash[offset + i].toInt() and BYTE_MASK)
            }
        return ((binary and INT_SIGN_MASK) % POWERS[digits]).toString().padStart(digits, '0')
    }

    /**
     * Passo que casou com [code] (janela de +-1) ou null. Passos <= [lastUsedStep] sao recusados (sem
     * reuso do mesmo codigo). Percorre sempre os tres candidatos, com comparacao em tempo constante.
     */
    fun verify(
        secret: ByteArray,
        code: String,
        nowMs: Long,
        lastUsedStep: Long,
    ): Long? {
        val current = step(nowMs)
        var matched: Long? = null
        for (candidate in current - 1..current + 1) {
            val same = MessageDigest.isEqual(generate(secret, candidate).toByteArray(), code.toByteArray())
            val fresh = candidate > maxOf(lastUsedStep, matched ?: Long.MIN_VALUE)
            if (same && fresh) matched = candidate
        }
        return matched
    }

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (b.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BASE32_BITS) {
                out.append(BASE32_ALPHABET[(buffer shr (bits - BASE32_BITS)) and BASE32_MASK])
                bits -= BASE32_BITS
            }
        }
        if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
        return out.toString()
    }
}

class TotpRecord(
    val secretEnc: ByteArray,
    val enabled: Boolean,
    val lastStep: Long,
)

interface TotpRepository {
    fun find(accountId: UUID): TotpRecord?

    /** Grava um segredo novo, ainda nao habilitado, no lugar de qualquer segredo nao habilitado. */
    fun save(
        accountId: UUID,
        secretEnc: ByteArray,
    )

    fun enable(
        accountId: UUID,
        step: Long,
    )

    /** Avanca o ultimo passo usado se ainda for [expectedLast]; false se outro pedido ganhou. */
    fun advance(
        accountId: UUID,
        expectedLast: Long,
        step: Long,
    ): Boolean
}

class TotpEnrollment(
    val secretBase32: String,
    val otpauthUri: String,
)

/**
 * Enrolamento e verificacao de TOTP. O segredo e guardado cifrado (AES-GCM, chave derivada de
 * MALHAVIVA_OTP_KEY, ligada ao id da conta) e nunca e logado.
 *
 * NAO INTEGRADO AO LOGIN nem exposto em rotas: so as primitivas. O gatilho `totpRequired` para contas com
 * papel admin depende de papeis que ainda nao existem (admin web e Fase posterior).
 */
class TotpService(
    private val repo: TotpRepository,
    otpKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key =
        SecretKeySpec(
            Mac
                .getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(otpKey, "HmacSHA256")) }
                .doFinal("totp-secret-v1".toByteArray()),
            "AES",
        )

    private fun cipher(
        mode: Int,
        iv: ByteArray,
        accountId: UUID,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(accountId.toString().toByteArray())
        }

    private fun encrypt(
        accountId: UUID,
        secret: ByteArray,
    ): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        return iv + cipher(Cipher.ENCRYPT_MODE, iv, accountId).doFinal(secret)
    }

    private fun decrypt(
        accountId: UUID,
        stored: ByteArray,
    ): ByteArray =
        cipher(Cipher.DECRYPT_MODE, stored.copyOfRange(0, GCM_IV_BYTES), accountId)
            .doFinal(stored, GCM_IV_BYTES, stored.size - GCM_IV_BYTES)

    fun isEnabled(accountId: UUID): Boolean = repo.find(accountId)?.enabled == true

    /** Novo segredo, ainda inativo ate [confirm]. Null se o 2FA ja esta ativo. */
    fun enroll(
        accountId: UUID,
        label: String,
    ): TotpEnrollment? =
        if (isEnabled(accountId)) {
            null
        } else {
            val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
            repo.save(accountId, encrypt(accountId, secret))
            val b32 = Totp.base32(secret)
            TotpEnrollment(b32, "otpauth://totp/MalhaViva:$label?secret=$b32&issuer=MalhaViva")
        }

    /** Primeiro codigo valido ativa o 2FA. */
    fun confirm(
        accountId: UUID,
        code: String,
    ): Boolean {
        val rec = repo.find(accountId)?.takeIf { !it.enabled }
        val step = rec?.let { Totp.verify(decrypt(accountId, it.secretEnc), code, clock(), it.lastStep) }
        if (step != null) repo.enable(accountId, step)
        return step != null
    }

    /** Verifica um codigo de login; cada passo vale uma unica vez. */
    fun verify(
        accountId: UUID,
        code: String,
    ): Boolean {
        val rec = repo.find(accountId)?.takeIf { it.enabled }
        val step = rec?.let { Totp.verify(decrypt(accountId, it.secretEnc), code, clock(), it.lastStep) }
        return rec != null && step != null && repo.advance(accountId, rec.lastStep, step)
    }
}
