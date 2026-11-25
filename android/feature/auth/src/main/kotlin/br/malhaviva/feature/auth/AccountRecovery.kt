package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthApi
import br.malhaviva.feature.auth.net.AuthError

/** Recuperacao de conta: pedido de OTPs (com CAPTCHA sob demanda) e troca da senha. */
class AccountRecovery(
    private val api: AuthApi,
    private val captcha: CaptchaTokenProvider,
    private val session: AuthSession,
) {
    /** Com CAPTCHA exigido pede token ao provedor e repete uma vez; sem provedor, nada mais e enviado. */
    suspend fun request(handle: String): ApiResult<Unit> {
        val first = api.requestRecovery(handle, null)
        val needsCaptcha = first is ApiResult.Fail && first.error == AuthError.CaptchaRequired
        if (!needsCaptcha) return first
        return captcha.token()?.let { api.requestRecovery(handle, it) } ?: ApiResult.Fail(AuthError.CaptchaUnavailable)
    }

    /** O servidor revoga todas as sessoes ao concluir; a copia local do refresh tambem sai. */
    suspend fun complete(
        handle: String,
        newPassword: String,
        phoneCode: String?,
        emailCode: String?,
    ): ApiResult<Unit> {
        val result = api.completeRecovery(handle, newPassword, phoneCode, emailCode)
        if (result is ApiResult.Ok) session.logout()
        return result
    }
}
