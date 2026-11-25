package br.malhaviva.auth

import br.malhaviva.auth.recovery.BackupCodeService
import br.malhaviva.auth.recovery.SecurityEvent
import br.malhaviva.auth.recovery.SecurityNotifier
import br.malhaviva.auth.recovery.TotpEnrollment
import br.malhaviva.auth.recovery.TotpService
import br.malhaviva.platform.ExpiringMap
import com.github.benmanes.caffeine.cache.Ticker
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

const val MFA_TOKEN_TTL_SECONDS = 5L * 60
private const val MFA_TOKEN_BYTES = 32
private const val MFA_MAX_ATTEMPTS = 5
private const val MFA_MAX_CHALLENGES = 100_000L
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

/** Segundo fator aceito no login: o codigo TOTP ou um codigo de recuperacao (uso unico). */
enum class MfaFactor { TOTP, BACKUP }

sealed interface BackupRegenerateResult {
    /** Codigos em claro: so existem nesta resposta. */
    class Done(
        val codes: List<String>,
    ) : BackupRegenerateResult

    data object Denied : BackupRegenerateResult
}

sealed interface TotpEnrollResult {
    class Started(
        val enrollment: TotpEnrollment,
    ) : TotpEnrollResult

    data object AlreadyEnabled : TotpEnrollResult

    /** Admin: o primeiro enrolamento exige o codigo de uso unico entregue fora de banda. */
    data object CodeRequired : TotpEnrollResult
}

/**
 * 2FA TOTP no login: opcional para `user`, obrigatorio para `admin` (BRIEF 4.2).
 *
 * O `mfa_token` e opaco (256 bits), vale 5 min, e de uso unico e so o SHA-256 fica guardado. Codigos e segredos
 * nunca sao logados. A recuperacao de senha nao toca no TOTP, entao depois do reset o login segue pedindo o codigo.
 * A forca bruta do codigo e contida por conta ([TotpService]): novo `mfa_token` ou novo IP nao zera a contagem.
 * shortcut: desafios em memoria de um unico no (com expiracao e teto); trocar por Redis com TTL ao escalar.
 *
 * Suppress: injecao de dependencias (o ticker so existe para testar a expiracao) e um unico fluxo de 2FA que
 * compartilha o estado dos desafios.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class MfaService(
    private val accounts: AccountRepository,
    private val totp: TotpService,
    private val backup: BackupCodeService,
    private val notifier: SecurityNotifier,
    private val hasher: PasswordHasher,
    private val access: AccessTokenService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val store =
        ExpiringMap<String, MfaChallenge>(MFA_MAX_CHALLENGES, Duration.ofSeconds(MFA_TOKEN_TTL_SECONDS), ticker)
    private val challenges get() = store.map

    fun gate(account: AccountRecord): MfaGate =
        when {
            totp.isEnabled(account.id) -> MfaGate.CODE
            account.role == ROLE_ADMIN -> MfaGate.ENROLL
            else -> MfaGate.NONE
        }

    /** Access restrito ao enrolamento de TOTP, sem refresh, sem dispositivo e sem `sid`. */
    fun enrollOnlyToken(accountId: UUID): String = access.issue(accountId, clock(), amrFor(false), SCOPE_MFA_ENROLL)

    fun isAdmin(accountId: UUID): Boolean = accounts.findById(accountId)?.role == ROLE_ADMIN

    /** Segundos de trava do TOTP da conta (0 = livre). */
    fun totpLockedSeconds(accountId: UUID): Long = totp.lockedSeconds(accountId)

    /** Desafios vivos (para testes). */
    fun pendingChallenges(): Long = store.size()

    fun open(
        accountId: UUID,
        handleKey: String,
        deviceLabel: String?,
    ): String {
        val raw =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(MFA_TOKEN_BYTES).also(random::nextBytes))
        challenges[sha256Hex(raw)] =
            MfaChallenge(accountId, handleKey, deviceLabel, clock() + MFA_TOKEN_TTL_SECONDS * MS_PER_SECOND)
        return raw
    }

    /** Desafio vivo do token, sem consumir. */
    fun peek(token: String): MfaChallenge? = challenges[sha256Hex(token)]?.takeIf { it.expiresAtMs > clock() }

    /** Sucesso consome o token; erro conta tentativa e queima o token na [MFA_MAX_ATTEMPTS]-esima. */
    fun redeem(
        token: String,
        code: String,
        factor: MfaFactor = MfaFactor.TOTP,
    ): MfaRedeem {
        val hash = sha256Hex(token)
        val ch = challenges[hash]?.takeIf { it.expiresAtMs > clock() }
        return when {
            ch == null -> MfaRedeem.Unknown.also { challenges.remove(hash) }
            verifyFactor(ch.accountId, code, factor) ->
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

    /**
     * Inicia o enrolamento. Admin sem TOTP so enrola com o [enrollmentCode] de uso unico gravado (como hash) na
     * mesma operacao manual que o promoveu: so a senha nunca basta para ele registrar o proprio segundo fator.
     */
    fun enroll(
        accountId: UUID,
        enrollmentCode: String?,
    ): TotpEnrollResult {
        val account = accounts.findById(accountId)
        return when {
            account == null || totp.isEnabled(accountId) -> TotpEnrollResult.AlreadyEnabled
            account.role == ROLE_ADMIN && !consumeEnrollmentCode(accountId, enrollmentCode) ->
                TotpEnrollResult.CodeRequired
            else ->
                totp.enroll(account.id, account.handle)?.let { TotpEnrollResult.Started(it) }
                    ?: TotpEnrollResult.AlreadyEnabled
        }
    }

    private fun consumeEnrollmentCode(
        accountId: UUID,
        code: String?,
    ): Boolean = code != null && accounts.consumeAdminEnrollCode(accountId, sha256Hex(code))

    /** Codigo certo liga o 2FA e devolve os codigos de recuperacao (so desta vez); null se errado. */
    fun confirm(
        accountId: UUID,
        code: String,
    ): List<String>? = if (totp.confirm(accountId, code)) backup.regenerate(accountId) else null

    /**
     * Codigo de recuperacao entra na mesma trava por conta do TOTP e, ao ser usado, avisa os canais verificados.
     * Os dois fatores consomem o proprio codigo (passo do TOTP ou codigo de recuperacao).
     */
    private fun verifyFactor(
        accountId: UUID,
        code: String,
        factor: MfaFactor,
    ): Boolean =
        when (factor) {
            MfaFactor.TOTP -> totp.verify(accountId, code)
            MfaFactor.BACKUP ->
                totp.verifyAlternative(accountId) { backup.consume(accountId, code) }.also {
                    if (it) notifier.notify(accountId, SecurityEvent.BACKUP_CODE_USED)
                }
        }

    /** Exige senha atual e um segundo fator (TOTP ou codigo de recuperacao); invalida todos os codigos antigos. */
    fun regenerateBackupCodes(
        accountId: UUID,
        password: String,
        code: String,
        factor: MfaFactor,
    ): BackupRegenerateResult {
        val account = accounts.findById(accountId)
        val passwordOk = account != null && hasher.verify(normalizePassword(password), account.passwordHash)
        return if (passwordOk && verifyFactor(accountId, code, factor)) {
            val codes = backup.regenerate(accountId)
            notifier.notify(accountId, SecurityEvent.BACKUP_CODES_REGENERATED)
            BackupRegenerateResult.Done(codes)
        } else {
            BackupRegenerateResult.Denied
        }
    }

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
            passwordOk && totp.disable(accountId, code) -> {
                backup.deleteAll(accountId)
                TotpDisableResult.DONE
            }
            else -> TotpDisableResult.DENIED
        }
    }
}
