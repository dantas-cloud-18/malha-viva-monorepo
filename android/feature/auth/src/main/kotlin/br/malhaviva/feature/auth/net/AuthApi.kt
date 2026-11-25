package br.malhaviva.feature.auth.net

import br.malhaviva.core.data.net.HttpTransport
import org.json.JSONObject

/**
 * Rotas publicas de conta de `contracts/openapi.yaml` (sem access token). Nunca registra corpos,
 * senhas nem tokens em log.
 */
class AuthApi(
    baseUrl: () -> String,
    transport: HttpTransport,
) {
    private val client = JsonClient(transport, baseUrl)

    suspend fun login(
        handle: String,
        password: String,
        deviceLabel: String?,
        captchaToken: String?,
    ): ApiResult<LoginOutcome> =
        call("/auth/token", obj(::loginOutcome)) {
            put("handle", handle)
            put("password", password)
            deviceLabel?.let { put("device_label", it) }
            captchaToken?.let { put("captcha_token", it) }
        }

    /** Informe `code` (TOTP, 6 digitos) ou `backupCode`; nunca os dois. */
    suspend fun loginMfa(
        mfaToken: String,
        code: String? = null,
        backupCode: String? = null,
    ): ApiResult<Tokens> =
        call("/auth/login/mfa", obj(::tokens)) {
            put("mfa_token", mfaToken)
            code?.let { put("code", it) }
            backupCode?.let { put("backup_code", it) }
        }

    suspend fun refresh(refreshToken: String): ApiResult<Tokens> =
        call("/auth/refresh", obj(::tokens)) { put("refresh_token", refreshToken) }

    suspend fun logout(refreshToken: String): ApiResult<Unit> =
        call("/auth/logout", { }) { put("refresh_token", refreshToken) }

    /** Devolve true quando a conta nasceu `pending_parental_consent` (menor de 12 anos). */
    suspend fun createAccount(
        account: NewAccount,
        captchaToken: String? = null,
    ): ApiResult<Boolean> =
        call("/auth/accounts", obj { it.optString("status") == "pending_parental_consent" }) {
            put("handle", account.handle)
            put("password", account.password)
            put("birth_date", account.birthDate)
            account.guardian?.let {
                put("guardian", JSONObject().put("channel", it.channel).put("value", it.value))
            }
            account.phone?.let { put("phone", it) }
            account.email?.let { put("email", it) }
            account.gender?.let { put("gender", it) }
            captchaToken?.let { put("captcha_token", it) }
        }

    suspend fun confirmParentalConsent(
        handle: String,
        code: String,
    ): ApiResult<Unit> =
        call("/auth/parental-consent/confirm", { }) {
            put("handle", handle)
            put("code", code)
        }

    /** O servidor exige `captcha_token` sempre; a resposta (202) nunca diz se a conta existe. */
    suspend fun resendParentalConsent(
        handle: String,
        captchaToken: String,
    ): ApiResult<Unit> =
        call("/auth/parental-consent/resend", { }) {
            put("handle", handle)
            put("captcha_token", captchaToken)
        }

    suspend fun requestRecovery(
        handle: String,
        captchaToken: String? = null,
    ): ApiResult<Unit> =
        call("/auth/recovery", { }) {
            put("handle", handle)
            captchaToken?.let { put("captcha_token", it) }
        }

    suspend fun completeRecovery(
        handle: String,
        newPassword: String,
        phoneCode: String? = null,
        emailCode: String? = null,
    ): ApiResult<Unit> =
        call("/auth/recovery/complete", { }) {
            put("handle", handle)
            put("new_password", newPassword)
            phoneCode?.let { put("phone_code", it) }
            emailCode?.let { put("email_code", it) }
        }

    private suspend fun <T> call(
        path: String,
        parse: (String) -> T,
        body: JSONObject.() -> Unit,
    ): ApiResult<T> = client.call("POST", path, null, parse, body)
}

private fun tokens(json: JSONObject) =
    Tokens(json.getString("access_token"), json.getString("refresh_token"), json.getLong("expires_in"))

private fun loginOutcome(json: JSONObject): LoginOutcome =
    when {
        json.optBoolean("mfa_required") ->
            LoginOutcome.MfaRequired(json.getString("mfa_token"), json.getLong("expires_in"))
        json.optBoolean("mfa_enrollment_required") ->
            LoginOutcome.EnrollmentRequired(json.getString("access_token"), json.getLong("expires_in"))
        else -> LoginOutcome.Success(tokens(json))
    }
