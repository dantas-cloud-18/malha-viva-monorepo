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

/** Dados do cadastro. `birthDate` e ISO (aaaa-mm-dd); `guardian` so para menor de 12 anos. */
data class NewAccount(
    val handle: String,
    val password: String,
    val birthDate: String,
    val guardian: Guardian? = null,
    val phone: String? = null,
    val email: String? = null,
)

/** Contato do responsavel (menor de 12 anos): `channel` e `phone` ou `email`. */
data class Guardian(
    val channel: String,
    val value: String,
)

/** Conta como o servidor a descreve; `ageBracket` e `under_12`, `teen_12_17` ou `adult` (ausente = null). */
data class AccountInfo(
    val handle: String,
    val ageBracket: String?,
)

data class Consent(
    val purpose: String,
    val granted: Boolean,
    val needsReconsent: Boolean,
)

data class ConsentList(
    val policyVersion: String,
    val consents: List<Consent>,
)

data class ConsentEvent(
    val purpose: String,
    val action: String,
    val policyVersion: String,
    val occurredAt: String,
)

sealed interface AuthError {
    /** 403 `parental_consent_pending`: senha certa, mas o responsavel ainda nao autorizou a conta. */
    data object ParentalConsentPending : AuthError

    /** 403 `marketing_forbidden_for_minors`. */
    data object MarketingForbiddenForMinors : AuthError

    /** 409 `policy_version_mismatch`: o app mostrou um texto de politica que nao e o vigente. */
    data object PolicyMismatch : AuthError

    /** 401: no login, handle ou senha errados; em rota autenticada, sessao invalida. */
    data object Unauthorized : AuthError

    /** 401 `invalid_mfa`: codigo errado, reusado, ou `mfa_token` vencido. */
    data object InvalidMfa : AuthError

    /** 403 `invalid_password`: senha atual incorreta ou ausente. */
    data object InvalidPassword : AuthError

    data object CaptchaRequired : AuthError

    data object AdminMfaRequired : AuthError

    /** 400 `invalid_or_expired_code`: OTP errado, vencido ou ausente (igual para handle inexistente). */
    data object InvalidCode : AuthError

    /** 403 `invalid_credentials`: senha ou segundo fator errado em operacao de TOTP. */
    data object InvalidCredentials : AuthError

    /** 403 `admin_mfa_mandatory`: conta admin nao pode desligar o TOTP. */
    data object AdminMfaMandatory : AuthError

    /** 403 `enrollment_code_required`: admin sem o codigo de enrolamento valido. */
    data object EnrollmentCodeRequired : AuthError

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
