package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError

enum class RecoveryStep { Request, Complete }

/**
 * Recuperacao de conta. O servidor nunca diz se a conta existe nem quais canais estao verificados,
 * entao a tela pede um campo por canal e aceita qualquer combinacao com ao menos um codigo.
 */
data class RecoveryState(
    val step: RecoveryStep = RecoveryStep.Request,
    val handle: String = "",
    val phoneCode: String = "",
    val emailCode: String = "",
    val newPassword: String = "",
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    val retryAfterSeconds: Long? = null,
    /** True depois do sucesso; a tela avisa o fluxo principal e volta ao login. */
    val done: Boolean = false,
) {
    val canRequest get() = !busy && isHandleValid(handle)
    val canComplete
        get() =
            !busy &&
                isHandleValid(handle) &&
                isPasswordValid(newPassword) &&
                (phoneCode.isNotEmpty() || emailCode.isNotEmpty()) &&
                (phoneCode.isEmpty() || isCodeValid(phoneCode)) &&
                (emailCode.isEmpty() || isCodeValid(emailCode))
}

sealed interface RecoveryIntent {
    /** Entrada na tela: zera tudo, guardando so o handle ja digitado no login. */
    data class Open(
        val handle: String,
    ) : RecoveryIntent

    data class HandleChanged(
        val value: String,
    ) : RecoveryIntent

    data class PhoneCodeChanged(
        val value: String,
    ) : RecoveryIntent

    data class EmailCodeChanged(
        val value: String,
    ) : RecoveryIntent

    data class PasswordChanged(
        val value: String,
    ) : RecoveryIntent

    data object SubmitRequest : RecoveryIntent

    data object SubmitComplete : RecoveryIntent

    data object Requested : RecoveryIntent

    data object Completed : RecoveryIntent

    data class Failed(
        val error: AuthError,
    ) : RecoveryIntent
}

fun reduceRecovery(
    state: RecoveryState,
    intent: RecoveryIntent,
): RecoveryState =
    when (intent) {
        is RecoveryIntent.Open -> RecoveryState(handle = intent.handle)
        is RecoveryIntent.HandleChanged -> state.copy(handle = intent.value, message = null)
        is RecoveryIntent.PhoneCodeChanged -> state.copy(phoneCode = intent.value.trim(), message = null)
        is RecoveryIntent.EmailCodeChanged -> state.copy(emailCode = intent.value.trim(), message = null)
        is RecoveryIntent.PasswordChanged -> state.copy(newPassword = intent.value, message = null)
        RecoveryIntent.SubmitRequest -> if (state.canRequest) state.copy(busy = true, message = null) else state
        RecoveryIntent.SubmitComplete -> if (state.canComplete) state.copy(busy = true, message = null) else state
        // Mensagem identica exista a conta ou nao: o 202 do servidor tambem e sempre igual.
        RecoveryIntent.Requested ->
            state.copy(step = RecoveryStep.Complete, busy = false, message = AuthMessage.RecoveryRequested)
        // Senha e codigos saem do estado no sucesso.
        RecoveryIntent.Completed -> RecoveryState(handle = state.handle, done = true)
        is RecoveryIntent.Failed ->
            state.copy(
                busy = false,
                message = intent.error.toMessage(),
                retryAfterSeconds = (intent.error as? AuthError.RateLimited)?.retryAfterSeconds,
            )
    }
