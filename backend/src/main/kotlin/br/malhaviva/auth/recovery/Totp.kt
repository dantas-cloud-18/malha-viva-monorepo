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
const val TOTP_MAX_FAILURES = 10
const val TOTP_LOCK_MS = 15 * 60_000L
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

private fun lockedSeconds(
    rec: TotpRecord?,
    nowMs: Long,
): Long {
    val left = (rec?.lockedUntilMs ?: 0L) - nowMs
    return if (left > 0) (left + MS_PER_SECOND - 1) / MS_PER_SECOND else 0L
}

/** Linha do TOTP da conta. [failedAttempts] e [lockedUntilMs] sao a trava por conta (V7). */
class TotpRecord(
    val secretEnc: ByteArray,
    val enabled: Boolean,
    val lastStep: Long,
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L,
) {
    fun copy(
        secretEnc: ByteArray = this.secretEnc,
        enabled: Boolean = this.enabled,
        lastStep: Long = this.lastStep,
        failedAttempts: Int = this.failedAttempts,
        lockedUntilMs: Long = this.lockedUntilMs,
    ) = TotpRecord(secretEnc, enabled, lastStep, failedAttempts, lockedUntilMs)
}

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

    /** Apaga o segredo (desliga o 2FA). */
    fun delete(accountId: UUID)

    /** Soma um erro; ao chegar em [maxFailures] trava ate `nowMs + lockMs` e zera o contador (atomico). */
    fun recordFailure(
        accountId: UUID,
        nowMs: Long,
        maxFailures: Int,
        lockMs: Long,
    )

    /** Acerto: zera o contador e a trava. */
    fun clearFailures(accountId: UUID)
}

class TotpEnrollment(
    val secretBase32: String,
    val otpauthUri: String,
)

/**
 * Enrolamento e verificacao de TOTP. O segredo e guardado cifrado (AES-GCM, chave derivada de
 * MALHAVIVA_OTP_KEY, ligada ao id da conta) e nunca e logado, assim como os codigos.
 * Integrado ao login por [br.malhaviva.auth.MfaService].
 *
 * Forca bruta: cada erro (login, confirmacao ou desligamento) conta no contador PERSISTENTE da conta; com
 * [TOTP_MAX_FAILURES] erros a conta trava por [TOTP_LOCK_MS], qualquer que seja o IP ou o `mfa_token`. Travada,
 * nem o codigo certo e aceito. Acerto zera o contador.
 *
 * Suppress: um unico servico concentra o segredo e a trava por conta, que os fatores alternativos reaproveitam.
 */
@Suppress("TooManyFunctions")
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

    /** Segundos que faltam para a trava da conta acabar; 0 se livre. */
    fun lockedSeconds(accountId: UUID): Long = lockedSeconds(repo.find(accountId), clock())

    /** Confere o codigo contra o segredo e atualiza o contador por conta; [accept] grava o efeito do acerto. */
    private fun counted(
        accountId: UUID,
        rec: TotpRecord,
        code: String,
        accept: (Long) -> Boolean,
    ): Boolean {
        val step = Totp.verify(decrypt(accountId, rec.secretEnc), code, clock(), rec.lastStep)
        return settle(accountId, rec, step != null && accept(step))
    }

    /** Efeito de um resultado na contagem por conta: erro soma (e pode travar), acerto zera. */
    private fun settle(
        accountId: UUID,
        rec: TotpRecord,
        ok: Boolean,
    ): Boolean {
        if (!ok) {
            repo.recordFailure(accountId, clock(), TOTP_MAX_FAILURES, TOTP_LOCK_MS)
        } else if (rec.failedAttempts > 0 || rec.lockedUntilMs > 0) {
            repo.clearFailures(accountId)
        }
        return ok
    }

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
        val rec = repo.find(accountId)?.takeIf { !it.enabled && lockedSeconds(it, clock()) == 0L }
        return rec != null &&
            counted(accountId, rec, code) {
                repo.enable(accountId, it)
                true
            }
    }

    /** Verifica um codigo de login; cada passo vale uma unica vez. */
    fun verify(
        accountId: UUID,
        code: String,
    ): Boolean {
        val rec = repo.find(accountId)?.takeIf { it.enabled && lockedSeconds(it, clock()) == 0L }
        return rec != null && counted(accountId, rec, code) { repo.advance(accountId, rec.lastStep, it) }
    }

    /**
     * Outro fator da mesma conta (codigo de recuperacao) na MESMA contagem e trava do TOTP: so roda [check] com o
     * 2FA ativo e a conta livre; erro soma na trava, acerto zera.
     */
    fun verifyAlternative(
        accountId: UUID,
        check: () -> Boolean,
    ): Boolean {
        val rec = repo.find(accountId)?.takeIf { it.enabled && lockedSeconds(it, clock()) == 0L }
        return rec != null && settle(accountId, rec, check())
    }

    /** Desliga com um codigo valido e ainda nao usado. */
    fun disable(
        accountId: UUID,
        code: String,
    ): Boolean = verify(accountId, code).also { if (it) repo.delete(accountId) }
}
