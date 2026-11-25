package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthError
import kotlinx.coroutines.launch

private const val STATUS_BAD_REQUEST = 400

class TotpViewModel(
    private val session: AuthSession,
    private val account: AccountApi,
) : MviViewModel<TotpState, TotpIntent>(TotpState()) {
    override fun reduce(
        state: TotpState,
        intent: TotpIntent,
    ): TotpState = reduceTotp(state, intent)

    fun act(intent: TotpIntent) {
        val before = state.value
        onIntent(intent)
        // Sessao restrita nao tem refresh: depois de ver os codigos, ela e descartada.
        if (intent == TotpIntent.Acknowledge && before.enrollOnly) viewModelScope.launch { session.logout() }
        if (intent !in SUBMITS || before.busy || !state.value.busy) return
        viewModelScope.launch {
            when (intent) {
                TotpIntent.SubmitStart -> start(before)
                TotpIntent.SubmitConfirm -> confirm(before)
                TotpIntent.SubmitRegenerate -> regenerate(before)
                else -> disable(before)
            }
        }
    }

    private suspend fun start(form: TotpState) {
        val code = form.enrollmentCode.ifEmpty { null }
        when (val result = session.authed { account.startTotp(it, code) }) {
            is ApiResult.Ok -> onIntent(TotpIntent.Started(result.value))
            is ApiResult.Fail -> onIntent(TotpIntent.Failed(result.error))
        }
    }

    private suspend fun confirm(form: TotpState) {
        when (val result = session.authed { account.confirmTotp(it, form.code) }) {
            is ApiResult.Ok -> onIntent(TotpIntent.CodesReceived(result.value, regenerated = false))
            // Codigo errado na confirmacao volta como 400: mostra a mensagem de codigo incorreto.
            is ApiResult.Fail -> onIntent(TotpIntent.Failed(wrongCode(result.error)))
        }
    }

    private suspend fun regenerate(form: TotpState) {
        val code = if (form.useBackupCode) null else form.code
        val backup = if (form.useBackupCode) form.code else null
        when (val result = session.authed { account.regenerateBackupCodes(it, form.password, code, backup) }) {
            is ApiResult.Ok -> onIntent(TotpIntent.CodesReceived(result.value, regenerated = true))
            is ApiResult.Fail -> onIntent(TotpIntent.Failed(result.error))
        }
    }

    private suspend fun disable(form: TotpState) {
        when (val result = session.authed { account.disableTotp(it, form.password, form.code) }) {
            is ApiResult.Ok -> onIntent(TotpIntent.Disabled)
            is ApiResult.Fail -> onIntent(TotpIntent.Failed(result.error))
        }
    }

    private fun wrongCode(error: AuthError) =
        if (error is AuthError.Rejected && error.status == STATUS_BAD_REQUEST) AuthError.InvalidMfa else error

    private companion object {
        val SUBMITS =
            setOf(
                TotpIntent.SubmitStart,
                TotpIntent.SubmitConfirm,
                TotpIntent.SubmitRegenerate,
                TotpIntent.SubmitDisable,
            )
    }
}
