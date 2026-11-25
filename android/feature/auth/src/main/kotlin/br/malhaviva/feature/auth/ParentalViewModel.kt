package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.ApiResult
import kotlinx.coroutines.launch

class ParentalViewModel(
    private val parental: ParentalConsent,
) : MviViewModel<ParentalState, ParentalIntent>(ParentalState()) {
    override fun reduce(
        state: ParentalState,
        intent: ParentalIntent,
    ): ParentalState = reduceParental(state, intent)

    fun act(intent: ParentalIntent) {
        val before = state.value
        onIntent(intent)
        if (intent !in SUBMITS || before.busy || !state.value.busy) return
        viewModelScope.launch {
            val handle = normalizeHandle(before.handle)
            if (intent == ParentalIntent.Resend) {
                finish(parental.resend(handle), ParentalIntent.Resent)
            } else {
                finish(parental.confirm(handle, before.code), ParentalIntent.Confirmed)
            }
        }
    }

    private fun finish(
        result: ApiResult<Unit>,
        success: ParentalIntent,
    ) = onIntent(if (result is ApiResult.Fail) ParentalIntent.Failed(result.error) else success)

    private companion object {
        val SUBMITS = setOf(ParentalIntent.SubmitConfirm, ParentalIntent.Resend)
    }
}
