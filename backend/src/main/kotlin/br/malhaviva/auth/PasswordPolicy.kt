package br.malhaviva.auth

import com.nulabinc.zxcvbn.Zxcvbn
import java.text.Normalizer

const val PASSWORD_MIN_LENGTH = 8
const val PASSWORD_MAX_LENGTH = 128
private const val MIN_ZXCVBN_SCORE = 3

/** Motivo de recusa; o codigo vai no campo `detail` do Problem. Nao depende de nenhuma conta. */
enum class PasswordRejection(
    val code: String,
) {
    TOO_SHORT("password_too_short"),
    TOO_LONG("password_too_long"),
    PWNED("password_pwned"),
    WEAK("password_weak"),
}

sealed interface PolicyResult {
    /** [normalized] e a senha em NFKC: e ela que vai para o hash, no cadastro e no login. */
    data class Accepted(
        val normalized: String,
    ) : PolicyResult

    data class Rejected(
        val reason: PasswordRejection,
    ) : PolicyResult
}

fun normalizePassword(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)

/**
 * NIST SP 800-63B: tamanho (em pontos de codigo, apos NFKC), lista de vazadas e estimativa de forca.
 * Sem regras de composicao e sem expiracao.
 */
class PasswordPolicy(
    private val pwned: PwnedPasswordsChecker,
    private val zxcvbn: Zxcvbn = Zxcvbn(),
) {
    suspend fun check(
        raw: String,
        userInputs: List<String> = emptyList(),
    ): PolicyResult {
        val normalized = normalizePassword(raw)
        val length = normalized.codePointCount(0, normalized.length)
        val rejection =
            when {
                length < PASSWORD_MIN_LENGTH -> PasswordRejection.TOO_SHORT
                length > PASSWORD_MAX_LENGTH -> PasswordRejection.TOO_LONG
                pwned.isPwned(normalized) -> PasswordRejection.PWNED
                zxcvbn.measure(normalized, userInputs).score < MIN_ZXCVBN_SCORE -> PasswordRejection.WEAK
                else -> null
            }
        return rejection?.let(PolicyResult::Rejected) ?: PolicyResult.Accepted(normalized)
    }
}
