package br.malhaviva.feature.auth.net

class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

/** As tres formas de resposta 200 de `/auth/token`. */
sealed interface LoginOutcome {
    class Success(
        val tokens: Tokens,
    ) : LoginOutcome

    class MfaRequired(
        val mfaToken: String,
        val expiresIn: Long,
    ) : LoginOutcome

    /** Admin sem TOTP: token restrito (`mfa_enroll_only`), sem refresh. */
    class EnrollmentRequired(
        val accessToken: String,
        val expiresIn: Long,
    ) : LoginOutcome
}

data class Device(
    val id: String,
    val label: String,
    val createdAt: String,
    val lastUsedAt: String,
)

data class PendingChange(
    val id: String,
    val channel: String,
    val effectiveAt: String,
    val confirmed: Boolean,
)

data class ContactsState(
    val phoneVerified: Boolean,
    val emailVerified: Boolean,
    val pendingChanges: List<PendingChange>,
)

data class ContactChangeStarted(
    val status: String,
    val changeId: String?,
    val effectiveAt: String?,
)

data class TotpEnrollment(
    val secret: String,
    val otpauthUri: String,
)

sealed interface AuthError {
    /** 401: no login, handle ou senha errados; em rota autenticada, sessao invalida. */
    data object Unauthorized : AuthError

    /** 401 `invalid_mfa`: codigo errado, reusado, ou `mfa_token` vencido. */
    data object InvalidMfa : AuthError

    /** 403 `invalid_password`: senha atual incorreta ou ausente. */
    data object InvalidPassword : AuthError

    data object CaptchaRequired : AuthError

    data object AdminMfaRequired : AuthError

    /** Cadastro com contato sem CAPTCHA disponivel neste build; nenhuma requisicao e feita. */
    data object CaptchaUnavailable : AuthError

    data object PayloadTooLarge : AuthError

    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : AuthError

    data object NotFound : AuthError

    data object Conflict : AuthError

    data class Rejected(
        val status: Int,
        val code: String?,
    ) : AuthError

    /** Sem rede ou resposta ilegivel. */
    data object Network : AuthError
}

sealed interface ApiResult<out T> {
    class Ok<T>(
        val value: T,
    ) : ApiResult<T>

    class Fail(
        val error: AuthError,
    ) : ApiResult<Nothing>
}
