package br.malhaviva.auth

import org.slf4j.LoggerFactory

private val HANDLE_REGEX = Regex("^@[a-zA-Z0-9_]{3,20}$")
private val log = LoggerFactory.getLogger("br.malhaviva.auth.AuthService")

sealed interface RegisterResult {
    data object Created : RegisterResult

    data object InvalidHandle : RegisterResult

    data object HandleTaken : RegisterResult

    data class PasswordRejected(
        val reason: PasswordRejection,
    ) : RegisterResult
}

sealed interface LoginResult {
    data class Success(
        val tokens: TokenPair,
    ) : LoginResult

    /** Mesmo resultado para handle inexistente e senha errada. */
    data object InvalidCredentials : LoginResult

    data object CaptchaRequired : LoginResult

    data class Throttled(
        val retryAfterSeconds: Long,
    ) : LoginResult
}

/** Cadastro e login. A orquestracao do abuso fica em [LoginGuard]; as sessoes em [SessionService]. */
class AuthService(
    private val accounts: AccountRepository,
    private val sessions: SessionService,
    private val policy: PasswordPolicy,
    private val hasher: PasswordHasher,
    private val guard: LoginGuard,
    private val captcha: CaptchaVerifier,
) {
    /** Hash descartavel: igualar o tempo quando o handle nao existe (sem revelar existencia). */
    private val dummyHash: String by lazy { hasher.hash("dummy-password-for-timing") }

    suspend fun register(
        handle: String,
        password: String,
    ): RegisterResult =
        if (!HANDLE_REGEX.matches(handle)) {
            RegisterResult.InvalidHandle
        } else {
            when (val checked = policy.check(password, listOf(handle.removePrefix("@")))) {
                is PolicyResult.Rejected -> RegisterResult.PasswordRejected(checked.reason)
                is PolicyResult.Accepted ->
                    if (accounts.create(handle, hasher.hash(checked.normalized)) != null) {
                        RegisterResult.Created
                    } else {
                        RegisterResult.HandleTaken
                    }
            }
        }

    suspend fun login(
        ip: String,
        handle: String,
        password: String,
        deviceLabel: String?,
        captchaToken: String?,
    ): LoginResult {
        val key = handle.lowercase()
        val normalized = normalizePassword(password)
        return when (val decision = guard.check(ip, key)) {
            is GuardDecision.Blocked -> LoginResult.Throttled(decision.retryAfterSeconds)
            GuardDecision.CaptchaRequired ->
                if (captcha.verify(
                        captchaToken,
                        ip,
                    )
                ) {
                    attempt(ip, key, handle, normalized, deviceLabel)
                } else {
                    LoginResult.CaptchaRequired
                }
            GuardDecision.Allow -> attempt(ip, key, handle, normalized, deviceLabel)
        }
    }

    private fun attempt(
        ip: String,
        key: String,
        handle: String,
        password: String,
        deviceLabel: String?,
    ): LoginResult {
        val account = accounts.findByHandle(handle)
        // sempre exatamente um verify, exista a conta ou nao
        val ok = hasher.verify(password, account?.passwordHash ?: dummyHash) && account != null
        return if (ok && account != null) {
            guard.recordSuccess(ip, key)
            upgradeHashIfNeeded(account, password)
            LoginResult.Success(sessions.startSession(account.id, deviceLabel))
        } else {
            guard.recordFailure(ip, key)
            LoginResult.InvalidCredentials
        }
    }

    private fun upgradeHashIfNeeded(
        account: AccountRecord,
        password: String,
    ) {
        if (hasher.needsRehash(account.passwordHash)) {
            accounts.updatePasswordHash(account.id, hasher.hash(password))
            log.info("Hash de senha atualizado para os parametros atuais")
        }
    }

    /** Verdadeiro se a senha tem tamanho aceitavel para tentar login (evita hash de entrada gigante). */
    fun isLoginPasswordSane(password: String): Boolean {
        val normalized = normalizePassword(password)
        return normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= PASSWORD_MAX_LENGTH
    }
}
