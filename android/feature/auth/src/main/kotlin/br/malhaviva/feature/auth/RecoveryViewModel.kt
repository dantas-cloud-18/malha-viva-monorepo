package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.ApiResult
import kotlinx.coroutines.launch

class RecoveryViewModel(
    private val recovery: AccountRecovery,
) : MviViewModel<RecoveryState, RecoveryIntent>(RecoveryState()) {
    override fun reduce(
        state: RecoveryState,
        intent: RecoveryIntent,
    ): RecoveryState = reduceRecovery(state, intent)

    fun act(intent: RecoveryIntent) {
        val before = state.value
        onIntent(intent)
        if (intent !in SUBMITS || before.busy || !state.value.busy) return
        viewModelScope.launch {
            val result =
                when (intent) {
                    RecoveryIntent.SubmitRequest -> recovery.request(normalizeHandle(before.handle))
                    else ->
                        recovery.complete(
                            normalizeHandle(before.handle),
                            before.newPassword,
                            before.phoneCode.ifEmpty { null },
                            before.emailCode.ifEmpty { null },
                        )
                }
            onIntent(
                when {
                    result is ApiResult.Fail -> RecoveryIntent.Failed(result.error)
                    intent == RecoveryIntent.SubmitRequest -> RecoveryIntent.Requested
                    else -> RecoveryIntent.Completed
                },
            )
        }
    }

    private companion object {
        val SUBMITS = setOf(RecoveryIntent.SubmitRequest, RecoveryIntent.SubmitComplete)
    }
}
