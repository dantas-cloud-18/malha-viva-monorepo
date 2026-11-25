package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthApi
import br.malhaviva.feature.auth.net.AuthError

private const val STATUS_BAD_REQUEST = 400

/** Consentimento parental (conta de menor de 12 anos): confirma o OTP do responsavel e o reenvia. */
class ParentalConsent(
    private val api: AuthApi,
    private val captcha: CaptchaTokenProvider,
) {
    suspend fun confirm(
        handle: String,
        code: String,
    ): ApiResult<Unit> {
        val result = api.confirmParentalConsent(handle, code)
        // O servidor responde 400 `invalid_code` igual para handle inexistente, conta nao pendente e codigo errado.
        val wrong = result is ApiResult.Fail && result.error == AuthError.Rejected(STATUS_BAD_REQUEST, "invalid_code")
        return if (wrong) ApiResult.Fail(AuthError.InvalidCode) else result
    }

    /** O reenvio sempre exige CAPTCHA; sem provedor nada e enviado. */
    suspend fun resend(handle: String): ApiResult<Unit> {
        val token = captcha.token() ?: return ApiResult.Fail(AuthError.CaptchaUnavailable)
        return api.resendParentalConsent(handle, token)
    }
}
