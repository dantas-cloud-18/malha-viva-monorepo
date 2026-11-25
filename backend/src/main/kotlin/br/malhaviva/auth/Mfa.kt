package br.malhaviva.auth

import br.malhaviva.auth.recovery.TotpEnrollment
import br.malhaviva.auth.recovery.TotpService
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val MFA_TOKEN_TTL_SECONDS = 5L * 60
private const val MFA_TOKEN_BYTES = 32
private const val MFA_MAX_ATTEMPTS = 5
private const val MS_PER_SECOND = 1000L

/** O que a senha correta destrava. */
enum class MfaGate {
    /** Sem 2FA: tokens direto. */
    NONE,

    /** TOTP ativo: exige o codigo em /auth/login/mfa. */
    CODE,

    /** Admin sem TOTP: so o token restrito de enrolamento. */
    ENROLL,
}

/** Desafio pendente entre a senha e o codigo. [handleKey] alimenta o [LoginGuard]. */
class MfaChallenge(
    val accountId: UUID,
    val handleKey: String,
    val deviceLabel: String?,
    val expiresAtMs: Long,
    val attempts: Int = 0,
)

sealed interface MfaRedeem {
    data class Ok(
        val challenge: MfaChallenge,
    ) : MfaRedeem

    data object Wrong : MfaRedeem

    /** Token desconhecido, vencido ou ja usado. */
    data object Unknown : MfaRedeem
}

enum class TotpDisableResult { DONE, DENIED, ADMIN_MANDATORY }

/**
 * 2FA TOTP no login: opcional para `user`, obrigatorio para `admin` (BRIEF 4.2).
 *
 * O `mfa_token` e opaco (256 bits), vale 5 min, e de uso unico e so o SHA-256 fica guardado. Codigos e segredos
 * nunca sao logados. A recuperacao de senha nao toca no TOTP, entao depois do reset o login segue pedindo o codigo.
 * shortcut: desafios em memoria de um unico no; trocar por Redis com TTL ao escalar horizontalmente.
 */
class MfaService(
    private val accounts: AccountRepository,
    private val totp: TotpService,
    private val hasher: PasswordHasher,
    private val access: AccessTokenService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val challenges = ConcurrentHashMap<String, MfaChallenge>()

    fun gate(account: AccountRecord): MfaGate =
        when {
            totp.isEnabled(account.id) -> MfaGate.CODE
            account.role == ROLE_ADMIN -> MfaGate.ENROLL
            else -> MfaGate.NONE
        }

    /** Access restrito ao enrolamento de TOTP, sem refresh e sem dispositivo. */
    fun enrollOnlyToken(accountId: UUID): String = access.issue(accountId, clock(), amrFor(false), SCOPE_MFA_ENROLL)

    fun isAdmin(accountId: UUID): Boolean = accounts.findById(accountId)?.role == ROLE_ADMIN

    fun open(
        accountId: UUID,
        handleKey: String,
        deviceLabel: String?,
    ): String {
        val raw =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(MFA_TOKEN_BYTES).also(random::nextBytes))
        val now = clock()
        challenges.values.removeIf { it.expiresAtMs <= now }
        challenges[sha256Hex(raw)] =
            MfaChallenge(accountId, handleKey, deviceLabel, now + MFA_TOKEN_TTL_SECONDS * MS_PER_SECOND)
        return raw
    }

    /** Desafio vivo do token, sem consumir. */
    fun peek(token: String): MfaChallenge? = challenges[sha256Hex(token)]?.takeIf { it.expiresAtMs > clock() }

    /** Sucesso consome o token; erro conta tentativa e queima o token na [MFA_MAX_ATTEMPTS]-esima. */
    fun redeem(
        token: String,
        code: String,
    ): MfaRedeem {
        val hash = sha256Hex(token)
        val ch = challenges[hash]?.takeIf { it.expiresAtMs > clock() }
        return when {
            ch == null -> MfaRedeem.Unknown.also { challenges.remove(hash) }
            totp.verify(ch.accountId, code) ->
                // remove condicional: dois pedidos simultaneos nao viram duas sessoes
                if (challenges.remove(hash, ch)) MfaRedeem.Ok(ch) else MfaRedeem.Unknown
            else -> {
                val next = MfaChallenge(ch.accountId, ch.handleKey, ch.deviceLabel, ch.expiresAtMs, ch.attempts + 1)
                if (next.attempts >= MFA_MAX_ATTEMPTS) {
                    challenges.remove(hash, ch)
                } else {
                    challenges.replace(hash, ch, next)
                }
                MfaRedeem.Wrong
            }
        }
    }

    fun enroll(accountId: UUID): TotpEnrollment? = accounts.findById(accountId)?.let { totp.enroll(it.id, it.handle) }

    fun confirm(
        accountId: UUID,
        code: String,
    ): Boolean = totp.confirm(accountId, code)

    /** Exige senha atual e codigo atual. Admin nao desliga: o 2FA e obrigatorio. */
    fun disable(
        accountId: UUID,
        password: String,
        code: String,
    ): TotpDisableResult {
        val account = accounts.findById(accountId)
        val passwordOk = account != null && hasher.verify(normalizePassword(password), account.passwordHash)
        return when {
            account?.role == ROLE_ADMIN -> TotpDisableResult.ADMIN_MANDATORY
            passwordOk && totp.disable(accountId, code) -> TotpDisableResult.DONE
            else -> TotpDisableResult.DENIED
        }
    }
}
