package br.malhaviva.auth.recovery

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val BACKUP_CODE_COUNT = 10
const val BACKUP_CODE_LENGTH = 10

/** Sem I, O, 0 e 1 (ambiguos): 32 simbolos, 5 bits cada, 50 bits por codigo. */
const val BACKUP_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val BACKUP_CODE_REGEX = Regex("^[$BACKUP_CODE_ALPHABET]{$BACKUP_CODE_LENGTH}$")
private val IGNORED_INPUT = Regex("[\\s-]")

interface BackupCodeRepository {
    /** Apaga os codigos da conta e grava [hashes] como novos, de forma atomica (regenerar invalida os antigos). */
    fun replaceAll(
        accountId: UUID,
        hashes: List<String>,
        now: Instant,
    )

    /** HMACs dos codigos ainda nao usados. */
    fun unusedHashes(accountId: UUID): List<String>

    /** Marca o codigo como usado se ainda nao estava; false se outro pedido ganhou a corrida. */
    fun consume(
        accountId: UUID,
        hash: String,
        now: Instant,
    ): Boolean

    fun deleteAll(accountId: UUID)
}

/**
 * Codigos de recuperacao do TOTP: [BACKUP_CODE_COUNT] codigos de uso unico, gerados com SecureRandom e devolvidos
 * uma unica vez. So o HMAC-SHA256 (chave derivada de MALHAVIVA_OTP_KEY, ligado a conta) fica guardado, e a
 * conferencia compara todos os candidatos da conta em tempo constante. Codigos nunca sao logados.
 */
class BackupCodeService(
    private val repo: BackupCodeRepository,
    otpKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key =
        SecretKeySpec(
            Mac
                .getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(otpKey, "HmacSHA256")) }
                .doFinal("totp-backup-v1".toByteArray()),
            "HmacSHA256",
        )

    private fun hmac(
        accountId: UUID,
        code: String,
    ): String =
        Mac
            .getInstance("HmacSHA256")
            .apply { init(key) }
            .doFinal("$accountId:$code".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun newCode() =
        String(CharArray(BACKUP_CODE_LENGTH) { BACKUP_CODE_ALPHABET[random.nextInt(BACKUP_CODE_ALPHABET.length)] })

    /** Troca todos os codigos da conta por um lote novo e devolve os codigos em claro (so aqui). */
    fun regenerate(accountId: UUID): List<String> {
        val codes = LinkedHashSet<String>()
        while (codes.size < BACKUP_CODE_COUNT) codes += newCode()
        repo.replaceAll(accountId, codes.map { hmac(accountId, it) }, Instant.ofEpochMilli(clock()))
        return codes.toList()
    }

    /** Confere e consome o codigo (maiusculas, sem espacos ou hifens). Uso unico mesmo sob concorrencia. */
    fun consume(
        accountId: UUID,
        raw: String,
    ): Boolean {
        val code = raw.replace(IGNORED_INPUT, "").uppercase()
        val candidate = hmac(accountId, code).toByteArray()
        // percorre todos, sem parar no primeiro acerto
        val hit =
            repo.unusedHashes(accountId).fold(null as String?) { found, h ->
                if (MessageDigest.isEqual(h.toByteArray(), candidate)) h else found
            }
        return BACKUP_CODE_REGEX.matches(code) &&
            hit != null &&
            repo.consume(accountId, hit, Instant.ofEpochMilli(clock()))
    }

    fun deleteAll(accountId: UUID) = repo.deleteAll(accountId)
}
