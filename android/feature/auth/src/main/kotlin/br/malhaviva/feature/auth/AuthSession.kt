package br.malhaviva.feature.auth

import br.malhaviva.core.data.secure.SecretStore
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthApi
import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.LoginOutcome
import br.malhaviva.feature.auth.net.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fornece o token anti-bot do cadastro e do login. A versao de release devolve null ate existir um
 * provedor real: sem token, o app nao envia cadastro com contato e mostra um aviso claro.
 */
fun interface CaptchaTokenProvider {
    suspend fun token(): String?
}

sealed interface SessionState {
    data object LoggedOut : SessionState

    data object LoggedIn : SessionState

    /** Token restrito de admin sem TOTP: so serve para o enrolamento; nao ha refresh. */
    data object EnrollmentOnly : SessionState
}

/**
 * Sessao da conta. Access token so em memoria; refresh token no [SecretStore]. A rotacao e serializada
 * por mutex: dois refresh com o mesmo token contariam como reuso e derrubariam a familia inteira.
 */
class AuthSession(
    private val api: AuthApi,
    private val secrets: SecretStore,
    private val captcha: CaptchaTokenProvider,
) {
    private val refreshLock = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Volatile
    private var accessToken: String? = null

    /** Tenta retomar a sessao salva (app reaberto). True se ha sessao valida. */
    suspend fun restore(): Boolean = secrets.get(REFRESH_KEY) != null && refresh(null) is ApiResult.Ok

    /** Entra; se o servidor exigir CAPTCHA pede um token ao provedor e repete uma vez. */
    suspend fun login(
        handle: String,
        password: String,
        deviceLabel: String?,
    ): ApiResult<LoginOutcome> {
        var result = api.login(handle, password, deviceLabel, null)
        if (result is ApiResult.Fail && result.error == AuthError.CaptchaRequired) {
            val token = captcha.token() ?: return ApiResult.Fail(AuthError.CaptchaUnavailable)
            result = api.login(handle, password, deviceLabel, token)
        }
        if (result is ApiResult.Ok) {
            when (val outcome = result.value) {
                is LoginOutcome.Success -> store(outcome.tokens)
                is LoginOutcome.EnrollmentRequired -> {
                    accessToken = outcome.accessToken
                    mutableState.value = SessionState.EnrollmentOnly
                }
                is LoginOutcome.MfaRequired -> Unit
            }
        }
        return result
    }

    suspend fun loginMfa(
        mfaToken: String,
        code: String? = null,
        backupCode: String? = null,
    ): ApiResult<Tokens> = api.loginMfa(mfaToken, code, backupCode).also { if (it is ApiResult.Ok) store(it.value) }

    /** Cadastro; com telefone ou e-mail exige CAPTCHA, e sem provedor nada e enviado. */
    suspend fun signup(
        handle: String,
        password: String,
        phone: String? = null,
        email: String? = null,
    ): ApiResult<Unit> {
        val hasContact = phone != null || email != null
        val token = if (hasContact) captcha.token() ?: return ApiResult.Fail(AuthError.CaptchaUnavailable) else null
        return api.createAccount(handle, password, phone, email, token)
    }

    suspend fun logout() {
        secrets.get(REFRESH_KEY)?.let { api.logout(it) }
        clear()
    }

    /**
     * Chama [block] com o access atual; em 401 renova uma vez e repete. Se a renovacao for recusada
     * (token reusado ou vencido), a sessao e limpa e o resultado e [AuthError.Unauthorized].
     */
    suspend fun <T> authed(block: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val first =
            when (val access = ensureAccess()) {
                is ApiResult.Fail -> return access
                is ApiResult.Ok -> access.value
            }
        val result = block(first)
        val unauthorized = result is ApiResult.Fail && result.error == AuthError.Unauthorized
        return if (!unauthorized) {
            result
        } else {
            when (val renewed = refresh(first)) {
                is ApiResult.Fail -> renewed
                is ApiResult.Ok -> block(renewed.value.accessToken)
            }
        }
    }

    private suspend fun ensureAccess(): ApiResult<String> {
        accessToken?.let { return ApiResult.Ok(it) }
        return when (val renewed = refresh(null)) {
            is ApiResult.Fail -> renewed
            is ApiResult.Ok -> accessToken?.let { ApiResult.Ok(it) } ?: ApiResult.Fail(AuthError.Unauthorized)
        }
    }

    /** [stale] e o access que acabou de falhar: se outro chamador ja renovou, so reaproveita o novo. */
    private suspend fun refresh(stale: String?): ApiResult<Tokens> =
        refreshLock.withLock {
            val current = accessToken
            val stored = secrets.get(REFRESH_KEY)
            when {
                stale != null && current != null && current != stale ->
                    ApiResult.Ok(Tokens(current, stored.orEmpty(), 0))
                stored == null -> {
                    clear()
                    ApiResult.Fail(AuthError.Unauthorized)
                }
                else ->
                    api.refresh(stored).also { result ->
                        when {
                            result is ApiResult.Ok -> store(result.value)
                            // Reuso ou token vencido: a familia ja foi revogada no servidor.
                            result is ApiResult.Fail && result.error == AuthError.Unauthorized -> clear()
                        }
                    }
            }
        }

    private fun store(tokens: Tokens) {
        secrets.put(REFRESH_KEY, tokens.refreshToken)
        accessToken = tokens.accessToken
        mutableState.value = SessionState.LoggedIn
    }

    private fun clear() {
        secrets.remove(REFRESH_KEY)
        accessToken = null
        mutableState.value = SessionState.LoggedOut
    }

    companion object {
        const val REFRESH_KEY = "auth.refresh_token"
    }
}
