package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.ContactChangeStarted
import br.malhaviva.feature.auth.net.PendingChange
import br.malhaviva.feature.auth.net.ContactsState as ContactsData

enum class ContactChannel(
    val wire: String,
) {
    Phone("phone"),
    Email("email"),
}

enum class ContactsStep { List, Add, Confirm }

private const val EMAIL_MAX = 254

/**
 * Contatos de recuperacao. Toda inclusao ou troca exige a senha atual; a senha fica no estado so ate
 * o OTP ser confirmado (verificar um contato gravado tambem a exige).
 */
data class ContactsState(
    val step: ContactsStep = ContactsStep.List,
    val channel: ContactChannel = ContactChannel.Phone,
    val phoneVerified: Boolean = false,
    val emailVerified: Boolean = false,
    val pending: List<PendingChange> = emptyList(),
    val value: String = DEFAULT_PHONE,
    val password: String = "",
    val code: String = "",
    /** Troca de contato em andamento; null ao verificar um contato novo. */
    val changeId: String? = null,
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    val retryAfterSeconds: Long? = null,
    val sessionLost: Boolean = false,
) {
    val valueValid
        get() =
            when (channel) {
                ContactChannel.Phone -> isPhoneValid(value)
                ContactChannel.Email -> '@' in value && value.trim().length <= EMAIL_MAX
            }
    val canAdd get() = !busy && password.isNotEmpty() && valueValid
    val canConfirm get() = !busy && isCodeValid(code) && (changeId != null || password.isNotEmpty())
}

sealed interface ContactsIntent {
    data object Open : ContactsIntent

    data object Reload : ContactsIntent

    data class StartAdd(
        val channel: ContactChannel,
    ) : ContactsIntent

    data class StartConfirm(
        val channel: ContactChannel,
        val changeId: String?,
    ) : ContactsIntent

    data object Back : ContactsIntent

    data class ValueChanged(
        val value: String,
    ) : ContactsIntent

    data class PasswordChanged(
        val value: String,
    ) : ContactsIntent

    data class CodeChanged(
        val value: String,
    ) : ContactsIntent

    data object SubmitAdd : ContactsIntent

    data object SubmitConfirm : ContactsIntent

    data class Cancel(
        val changeId: String,
    ) : ContactsIntent

    data class Loaded(
        val data: ContactsData,
    ) : ContactsIntent

    data class Started(
        val result: ContactChangeStarted,
    ) : ContactsIntent

    data class Confirmed(
        val wasChange: Boolean,
    ) : ContactsIntent

    data class Canceled(
        val changeId: String,
    ) : ContactsIntent

    data class Failed(
        val error: AuthError,
    ) : ContactsIntent
}

fun reduceContacts(
    state: ContactsState,
    intent: ContactsIntent,
): ContactsState =
    when (intent) {
        ContactsIntent.Open -> ContactsState(busy = true)
        ContactsIntent.Reload -> state.copy(busy = true)
        is ContactsIntent.StartAdd ->
            clean(state).copy(
                step = ContactsStep.Add,
                channel = intent.channel,
                value = if (intent.channel == ContactChannel.Phone) DEFAULT_PHONE else "",
            )
        is ContactsIntent.StartConfirm ->
            clean(state).copy(step = ContactsStep.Confirm, channel = intent.channel, changeId = intent.changeId)
        ContactsIntent.Back -> clean(state)
        ContactsIntent.SubmitAdd -> if (state.canAdd) state.copy(busy = true, message = null) else state
        ContactsIntent.SubmitConfirm -> if (state.canConfirm) state.copy(busy = true, message = null) else state
        is ContactsIntent.Cancel -> state.copy(busy = true, message = null)
        else -> reduceContactsEdit(state, intent)
    }

private fun reduceContactsEdit(
    state: ContactsState,
    intent: ContactsIntent,
): ContactsState =
    when (intent) {
        is ContactsIntent.ValueChanged -> state.copy(value = intent.value.trim(), message = null)
        is ContactsIntent.PasswordChanged -> state.copy(password = intent.value, message = null)
        is ContactsIntent.CodeChanged -> state.copy(code = intent.value.trim(), message = null)
        else -> reduceContactsResult(state, intent)
    }

/** Volta a lista sem nenhum segredo digitado. */
private fun clean(state: ContactsState) =
    state.copy(
        step = ContactsStep.List,
        value = DEFAULT_PHONE,
        password = "",
        code = "",
        changeId = null,
        busy = false,
        message = null,
        retryAfterSeconds = null,
    )

private fun reduceContactsResult(
    state: ContactsState,
    intent: ContactsIntent,
): ContactsState =
    when (intent) {
        is ContactsIntent.Loaded ->
            state.copy(
                busy = false,
                phoneVerified = intent.data.phoneVerified,
                emailVerified = intent.data.emailVerified,
                pending = intent.data.pendingChanges,
            )
        is ContactsIntent.Started -> reduceStarted(state, intent.result)
        is ContactsIntent.Confirmed ->
            clean(state).copy(
                message = if (intent.wasChange) AuthMessage.ContactChangePending else AuthMessage.ContactConfirmed,
            )
        is ContactsIntent.Canceled ->
            state.copy(
                busy = false,
                pending = state.pending.filterNot { it.id == intent.changeId },
                message = AuthMessage.ContactChangeCanceled,
            )
        is ContactsIntent.Failed ->
            state.copy(
                busy = false,
                sessionLost = intent.error == AuthError.Unauthorized,
                message = intent.error.toMessage(),
                retryAfterSeconds = (intent.error as? AuthError.RateLimited)?.retryAfterSeconds,
            )
        else -> state
    }

private fun reduceStarted(
    state: ContactsState,
    result: ContactChangeStarted,
): ContactsState =
    if (result.changeId != null) {
        // A senha ja foi provada ao pedir a troca; o OTP do novo canal nao a exige de novo.
        state.copy(
            step = ContactsStep.Confirm,
            changeId = result.changeId,
            password = "",
            code = "",
            busy = false,
            message = AuthMessage.ContactChangePending,
        )
    } else {
        state.copy(
            step = ContactsStep.Confirm,
            changeId = null,
            code = "",
            busy = false,
            message = AuthMessage.ContactSent,
        )
    }
