package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.Guardian
import br.malhaviva.feature.auth.net.NewAccount
import kotlinx.coroutines.launch

/** [deviceLabel] e o nome amigavel do aparelho enviado no login (o servidor sanitiza). */
class AuthViewModel(
    private val session: AuthSession,
    private val account: AccountApi,
    private val deviceLabel: String? = null,
) : MviViewModel<AuthState, AuthIntent>(AuthState()) {
    init {
        viewModelScope.launch {
            if (session.restore()) {
                onIntent(AuthIntent.SessionRestored)
                act(AuthIntent.LoadDevices)
            }
        }
    }

    override fun reduce(
        state: AuthState,
        intent: AuthIntent,
    ): AuthState = reduceAuth(state, intent)

    /** Entrada da UI: aplica o reducer e dispara o efeito de rede, quando houver. */
    fun act(intent: AuthIntent) {
        val before = state.value
        onIntent(intent)
        // O reducer recusa o envio (entrada invalida ou ja em andamento) deixando `busy` como estava.
        if (intent in SUBMITS && (before.busy || !state.value.busy)) return
        viewModelScope.launch {
            when (intent) {
                AuthIntent.SubmitLogin -> login(before)
                AuthIntent.SubmitMfa -> mfa(before)
                AuthIntent.SubmitSignup -> signup(before)
                AuthIntent.LoadDevices -> loadDevices()
                is AuthIntent.RevokeDevice -> revoke(intent.id)
                AuthIntent.Logout -> session.logout()
                else -> Unit
            }
        }
    }

    private suspend fun login(form: AuthState) {
        when (val result = session.login(normalizeHandle(form.handle), form.password, deviceLabel)) {
            is ApiResult.Ok -> {
                onIntent(AuthIntent.LoginSucceeded(result.value))
                if (state.value.screen == AuthScreen.Devices) act(AuthIntent.LoadDevices)
            }
            is ApiResult.Fail -> onIntent(AuthIntent.Failed(result.error))
        }
    }

    private suspend fun mfa(form: AuthState) {
        val token = form.mfaToken ?: return onIntent(AuthIntent.Failed(AuthError.InvalidMfa))
        val code = form.mfaCode
        val result =
            if (form.useBackupCode) session.loginMfa(token, backupCode = code) else session.loginMfa(token, code = code)
        when (result) {
            is ApiResult.Ok -> {
                onIntent(AuthIntent.MfaSucceeded)
                act(AuthIntent.LoadDevices)
            }
            is ApiResult.Fail -> onIntent(AuthIntent.Failed(result.error))
        }
    }

    private suspend fun signup(form: AuthState) {
        val email = form.email.trim().ifEmpty { null }
        val phone = if (isPhoneBlank(form.phone)) null else normalizePhone(form.phone)
        val birth = form.birth ?: return onIntent(AuthIntent.Failed(AuthError.Rejected(STATUS_BAD_REQUEST, null)))
        val guardian =
            if (!form.needsGuardian) {
                null
            } else if (form.guardianChannel == ContactChannel.Phone) {
                Guardian(form.guardianChannel.wire, normalizePhone(form.guardian))
            } else {
                Guardian(form.guardianChannel.wire, form.guardian.trim())
            }
        val result =
            session.signup(
                NewAccount(normalizeHandle(form.handle), form.password, birth.toString(), guardian, phone, email),
            )
        when (result) {
            is ApiResult.Ok ->
                onIntent(
                    AuthIntent.SignedUp(withContact = phone != null || email != null, parentalPending = result.value),
                )
            is ApiResult.Fail -> onIntent(AuthIntent.Failed(result.error))
        }
    }

    private suspend fun loadDevices() {
        when (val result = session.authed { account.listDevices(it) }) {
            is ApiResult.Ok -> onIntent(AuthIntent.DevicesLoaded(result.value))
            is ApiResult.Fail -> failAuthed(result.error)
        }
    }

    private suspend fun revoke(id: String) {
        when (val result = session.authed { account.revokeDevice(it, id) }) {
            is ApiResult.Ok -> onIntent(AuthIntent.DeviceRevoked(id))
            is ApiResult.Fail -> failAuthed(result.error)
        }
    }

    // 401 numa rota autenticada, depois do refresh, significa sessao perdida: volta ao login.
    private fun failAuthed(error: AuthError) =
        onIntent(if (error == AuthError.Unauthorized) AuthIntent.SessionLost else AuthIntent.Failed(error))

    private companion object {
        const val STATUS_BAD_REQUEST = 400
        val SUBMITS = setOf(AuthIntent.SubmitLogin, AuthIntent.SubmitMfa, AuthIntent.SubmitSignup)
    }
}
