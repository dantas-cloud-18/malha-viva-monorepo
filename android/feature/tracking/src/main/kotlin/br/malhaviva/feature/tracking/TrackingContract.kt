package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.sampling.StopReason

enum class TripStatus { Waiting, Sending, Paused }

enum class StopCause {
    UserStopped,
    PermissionDenied,
    ConsentRevoked,
    NoRoute,
    LeftCorridor,
    StoppedOutsideStation,
    MaxDuration,
}

fun StopReason.toCause(): StopCause =
    when (this) {
        StopReason.LeftCorridor -> StopCause.LeftCorridor
        StopReason.StoppedOutsideStation -> StopCause.StoppedOutsideStation
        StopReason.MaxDuration -> StopCause.MaxDuration
    }

sealed interface TrackingState {
    data object Off : TrackingState

    /** Consentimento de localizacao (Privacidade) ausente ou revogado: nada e pedido nem iniciado. */
    data object NeedsConsent : TrackingState

    data object NeedsDisclosure : TrackingState

    data object NeedsPermission : TrackingState

    data object Starting : TrackingState

    data class Active(
        val sinceMs: Long,
        val lastStatus: TripStatus,
    ) : TrackingState

    data class Stopped(
        val reason: StopCause,
    ) : TrackingState
}

sealed interface TrackingIntent {
    data object OptIn : TrackingIntent

    data object DisclosureAccepted : TrackingIntent

    data object DisclosureDeclined : TrackingIntent

    data class PermissionResult(
        val granted: Boolean,
    ) : TrackingIntent

    data class ServiceStarted(
        val sinceMs: Long,
    ) : TrackingIntent

    /** Servico ja rodava quando a UI nasceu (ex.: tela recriada); vale mesmo fora do estado Starting. */
    data class ServiceAlreadyRunning(
        val sinceMs: Long,
        val status: TripStatus,
    ) : TrackingIntent

    data class StatusChanged(
        val status: TripStatus,
    ) : TrackingIntent

    data object StopRequested : TrackingIntent

    data class ServiceStopped(
        val cause: StopCause,
    ) : TrackingIntent

    data object Dismissed : TrackingIntent

    /** Consentimento de localizacao mudou na tela de Privacidade (ou a sessao terminou). */
    data class ConsentChanged(
        val granted: Boolean,
    ) : TrackingIntent
}

private fun TrackingState.running(): Boolean = this is TrackingState.Active || this == TrackingState.Starting

/**
 * Reducer puro: compartilhamento nasce desligado e so avanca pelos passos de consentimento, aceite e
 * permissao. [consentGranted] e o consentimento `location` da conta; sem ele nada avanca e, se for
 * revogado no meio do caminho, tudo para (falha fechada).
 */
fun reduceTracking(
    state: TrackingState,
    intent: TrackingIntent,
    sharingBlocked: Boolean,
    consentGranted: Boolean,
): TrackingState =
    when (intent) {
        TrackingIntent.OptIn,
        TrackingIntent.DisclosureAccepted,
        TrackingIntent.DisclosureDeclined,
        is TrackingIntent.PermissionResult,
        -> reduceSetup(state, intent, sharingBlocked, consentGranted)
        is TrackingIntent.ConsentChanged -> reduceConsent(state, intent.granted)
        else -> reduceRunning(state, intent)
    }

private fun reduceConsent(
    state: TrackingState,
    granted: Boolean,
): TrackingState {
    val revokable = state.running() || state == TrackingState.NeedsDisclosure || state == TrackingState.NeedsPermission
    return when {
        !granted && revokable -> TrackingState.Stopped(StopCause.ConsentRevoked)
        granted && state == TrackingState.NeedsConsent -> TrackingState.Off
        else -> state
    }
}

private fun dismissed(state: TrackingState): TrackingState =
    if (state is TrackingState.Stopped || state == TrackingState.NeedsConsent) TrackingState.Off else state

private fun reduceSetup(
    state: TrackingState,
    intent: TrackingIntent,
    sharingBlocked: Boolean,
    consentGranted: Boolean,
): TrackingState =
    when (intent) {
        TrackingIntent.OptIn -> optIn(state, sharingBlocked, consentGranted)
        TrackingIntent.DisclosureAccepted -> disclosureAccepted(state, consentGranted)
        TrackingIntent.DisclosureDeclined ->
            if (state == TrackingState.NeedsDisclosure) TrackingState.Off else state
        is TrackingIntent.PermissionResult -> permissionResult(state, intent.granted, consentGranted)
        else -> state
    }

private fun optIn(
    state: TrackingState,
    sharingBlocked: Boolean,
    consentGranted: Boolean,
): TrackingState =
    when {
        sharingBlocked || !(state == TrackingState.Off || state is TrackingState.Stopped) -> state
        !consentGranted -> TrackingState.NeedsConsent
        else -> TrackingState.NeedsDisclosure
    }

private fun disclosureAccepted(
    state: TrackingState,
    consentGranted: Boolean,
): TrackingState =
    when {
        state != TrackingState.NeedsDisclosure -> state
        !consentGranted -> TrackingState.Stopped(StopCause.ConsentRevoked)
        else -> TrackingState.NeedsPermission
    }

private fun permissionResult(
    state: TrackingState,
    granted: Boolean,
    consentGranted: Boolean,
): TrackingState =
    when {
        state != TrackingState.NeedsPermission -> state
        !consentGranted -> TrackingState.Stopped(StopCause.ConsentRevoked)
        granted -> TrackingState.Starting
        else -> TrackingState.Stopped(StopCause.PermissionDenied)
    }

private fun reduceRunning(
    state: TrackingState,
    intent: TrackingIntent,
): TrackingState =
    when (intent) {
        is TrackingIntent.ServiceStarted ->
            if (state == TrackingState.Starting) TrackingState.Active(intent.sinceMs, TripStatus.Waiting) else state
        is TrackingIntent.ServiceAlreadyRunning ->
            if (state is TrackingState.Active) state else TrackingState.Active(intent.sinceMs, intent.status)
        is TrackingIntent.StatusChanged ->
            if (state is TrackingState.Active) state.copy(lastStatus = intent.status) else state
        TrackingIntent.StopRequested ->
            if (state.running()) TrackingState.Stopped(StopCause.UserStopped) else state
        is TrackingIntent.ServiceStopped ->
            if (state.running()) TrackingState.Stopped(intent.cause) else state
        TrackingIntent.Dismissed -> dismissed(state)
        else -> state
    }
