package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError

/** Tela do codigo do responsavel: a conta so entra depois que ele confirma. */
data class ParentalState(
    val handle: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    val retryAfterSeconds: Long? = null,
    /** True depois do sucesso; a tela avisa o fluxo principal e volta ao login. */
    val done: Boolean = false,
) {
    val canConfirm get() = !busy && isHandleValid(handle) && isCodeValid(code)
    val canResend get() = !busy && isHandleValid(handle)
}

sealed interface ParentalIntent {
    data class Open(
        val handle: String,
    ) : ParentalIntent

    data class HandleChanged(
        val value: String,
    ) : ParentalIntent

    data class CodeChanged(
        val value: String,
    ) : ParentalIntent

    data object SubmitConfirm : ParentalIntent

    data object Resend : ParentalIntent

    data object Resent : ParentalIntent

    data object Confirmed : ParentalIntent

    data class Failed(
        val error: AuthError,
    ) : ParentalIntent
}

fun reduceParental(
    state: ParentalState,
    intent: ParentalIntent,
): ParentalState =
    when (intent) {
        is ParentalIntent.Open -> ParentalState(handle = intent.handle)
        is ParentalIntent.HandleChanged -> state.copy(handle = intent.value, message = null)
        is ParentalIntent.CodeChanged -> state.copy(code = intent.value.trim(), message = null)
        ParentalIntent.SubmitConfirm -> if (state.canConfirm) state.copy(busy = true, message = null) else state
        ParentalIntent.Resend -> if (state.canResend) state.copy(busy = true, message = null) else state
        ParentalIntent.Resent -> state.copy(busy = false, message = AuthMessage.ParentalResent)
        ParentalIntent.Confirmed -> ParentalState(handle = state.handle, done = true)
        is ParentalIntent.Failed ->
            state.copy(
                busy = false,
                message = intent.error.toMessage(),
                retryAfterSeconds = (intent.error as? AuthError.RateLimited)?.retryAfterSeconds,
            )
    }
