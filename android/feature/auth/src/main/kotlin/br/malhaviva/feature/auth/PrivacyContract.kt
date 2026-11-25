package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.ConsentEvent
import br.malhaviva.feature.auth.net.ConsentList

/** Finalidades de consentimento; o texto da tela vem de recursos. */
enum class ConsentPurpose(
    val wire: String,
) {
    Location("location"),
    Marketing("marketing"),
}

enum class PrivacyStep { Main, History, EditHandle, Delete }

/**
 * Privacidade (LGPD). Nada comeca concedido. `marketingForbidden` e aprendido do servidor (403
 * `marketing_forbidden_for_minors` ou faixa etaria devolvida pelo `PATCH /me`) e desliga o interruptor.
 */
data class PrivacyState(
    val step: PrivacyStep = PrivacyStep.Main,
    val location: Boolean = false,
    val marketing: Boolean = false,
    val needsReconsent: Boolean = false,
    val marketingForbidden: Boolean = false,
    val history: List<ConsentEvent> = emptyList(),
    val newHandle: String = "",
    val password: String = "",
    /** JSON do export, so em memoria ate a tela entregar ao compartilhamento do sistema. */
    val exportJson: String? = null,
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    val retryAfterSeconds: Long? = null,
    val sessionLost: Boolean = false,
    /** True depois da exclusao; a tela avisa o fluxo principal. */
    val deleted: Boolean = false,
) {
    val canSaveHandle get() = !busy && isHandleValid(newHandle)
    val canDelete get() = !busy && password.isNotEmpty() && password.length <= PASSWORD_MAX
}

sealed interface PrivacyIntent {
    data object Open : PrivacyIntent

    data class Toggle(
        val purpose: ConsentPurpose,
        val on: Boolean,
    ) : PrivacyIntent

    data object ShowHistory : PrivacyIntent

    data object StartEditHandle : PrivacyIntent

    data object StartDelete : PrivacyIntent

    data object Back : PrivacyIntent

    data class HandleChanged(
        val value: String,
    ) : PrivacyIntent

    data class PasswordChanged(
        val value: String,
    ) : PrivacyIntent

    data object SubmitHandle : PrivacyIntent

    data object SubmitDelete : PrivacyIntent

    data object Export : PrivacyIntent

    /** A tela ja entregou o JSON ao compartilhamento: descarta da memoria. */
    data object ExportConsumed : PrivacyIntent

    data class Loaded(
        val data: ConsentList,
    ) : PrivacyIntent

    data class Toggled(
        val purpose: ConsentPurpose,
        val on: Boolean,
    ) : PrivacyIntent

    data class HistoryLoaded(
        val events: List<ConsentEvent>,
    ) : PrivacyIntent

    /** [adult] e null quando o servidor nao informou a faixa etaria. */
    data class HandleSaved(
        val adult: Boolean?,
    ) : PrivacyIntent

    data class Exported(
        val json: String,
    ) : PrivacyIntent

    data object Deleted : PrivacyIntent

    data class Failed(
        val error: AuthError,
    ) : PrivacyIntent
}

fun reducePrivacy(
    state: PrivacyState,
    intent: PrivacyIntent,
): PrivacyState =
    when (intent) {
        PrivacyIntent.Open -> PrivacyState(busy = true)
        is PrivacyIntent.Toggle -> state.copy(busy = true, message = null)
        PrivacyIntent.ShowHistory -> state.copy(step = PrivacyStep.History, busy = true, message = null)
        PrivacyIntent.StartEditHandle -> clean(state).copy(step = PrivacyStep.EditHandle)
        PrivacyIntent.StartDelete -> clean(state).copy(step = PrivacyStep.Delete)
        PrivacyIntent.Back -> clean(state)
        PrivacyIntent.SubmitHandle -> if (state.canSaveHandle) state.copy(busy = true, message = null) else state
        PrivacyIntent.SubmitDelete -> if (state.canDelete) state.copy(busy = true, message = null) else state
        PrivacyIntent.Export -> state.copy(busy = true, message = null)
        PrivacyIntent.ExportConsumed -> state.copy(exportJson = null)
        else -> reducePrivacyEdit(state, intent)
    }

private fun reducePrivacyEdit(
    state: PrivacyState,
    intent: PrivacyIntent,
): PrivacyState =
    when (intent) {
        is PrivacyIntent.HandleChanged -> state.copy(newHandle = intent.value.trim(), message = null)
        is PrivacyIntent.PasswordChanged -> state.copy(password = intent.value, message = null)
        else -> reducePrivacyResult(state, intent)
    }

/** Volta ao menu sem segredo digitado. */
private fun clean(state: PrivacyState) =
    state.copy(
        step = PrivacyStep.Main,
        newHandle = "",
        password = "",
        busy = false,
        message = null,
        retryAfterSeconds = null,
    )

private fun reducePrivacyResult(
    state: PrivacyState,
    intent: PrivacyIntent,
): PrivacyState =
    when (intent) {
        is PrivacyIntent.Loaded ->
            state.copy(
                busy = false,
                location = intent.data.consents.any { it.purpose == ConsentPurpose.Location.wire && it.granted },
                marketing = intent.data.consents.any { it.purpose == ConsentPurpose.Marketing.wire && it.granted },
                needsReconsent = intent.data.consents.any { it.needsReconsent },
            )
        is PrivacyIntent.Toggled ->
            if (intent.purpose == ConsentPurpose.Location) {
                state.copy(location = intent.on, busy = false, message = AuthMessage.ConsentSaved)
            } else {
                state.copy(marketing = intent.on, busy = false, message = AuthMessage.ConsentSaved)
            }
        is PrivacyIntent.HistoryLoaded -> state.copy(busy = false, history = intent.events)
        is PrivacyIntent.HandleSaved ->
            clean(state).copy(
                message = AuthMessage.HandleUpdated,
                marketingForbidden = state.marketingForbidden || intent.adult == false,
            )
        is PrivacyIntent.Exported ->
            state.copy(busy = false, exportJson = intent.json, message = AuthMessage.ExportReady)
        PrivacyIntent.Deleted -> PrivacyState(deleted = true)
        is PrivacyIntent.Failed -> reducePrivacyFailure(state, intent)
        else -> state
    }

private fun reducePrivacyFailure(
    state: PrivacyState,
    intent: PrivacyIntent.Failed,
): PrivacyState =
    state.copy(
        busy = false,
        sessionLost = intent.error == AuthError.Unauthorized,
        marketingForbidden = state.marketingForbidden || intent.error == AuthError.MarketingForbiddenForMinors,
        message = intent.error.toMessage(),
        retryAfterSeconds = (intent.error as? AuthError.RateLimited)?.retryAfterSeconds,
    )
