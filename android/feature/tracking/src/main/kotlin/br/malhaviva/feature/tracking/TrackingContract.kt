package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.sampling.StopReason

enum class TripStatus { Waiting, Sending, Paused }

enum class StopCause {
    UserStopped,
    PermissionDenied,
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

    data class StatusChanged(
        val status: TripStatus,
    ) : TrackingIntent

    data object StopRequested : TrackingIntent

    data class ServiceStopped(
        val cause: StopCause,
    ) : TrackingIntent

    data object Dismissed : TrackingIntent
}

private fun TrackingState.running(): Boolean = this is TrackingState.Active || this == TrackingState.Starting

/** Reducer puro: compartilhamento nasce desligado e so avanca pelos passos de aceite e permissao. */
fun reduceTracking(
    state: TrackingState,
    intent: TrackingIntent,
    sharingBlocked: Boolean = false,
): TrackingState =
    when (intent) {
        TrackingIntent.OptIn,
        TrackingIntent.DisclosureAccepted,
        TrackingIntent.DisclosureDeclined,
        is TrackingIntent.PermissionResult,
        -> reduceSetup(state, intent, sharingBlocked)
        else -> reduceRunning(state, intent)
    }

private fun reduceSetup(
    state: TrackingState,
    intent: TrackingIntent,
    sharingBlocked: Boolean,
): TrackingState =
    when (intent) {
        TrackingIntent.OptIn ->
            if (!sharingBlocked && (state == TrackingState.Off || state is TrackingState.Stopped)) {
                TrackingState.NeedsDisclosure
            } else {
                state
            }
        TrackingIntent.DisclosureAccepted ->
            if (state == TrackingState.NeedsDisclosure) TrackingState.NeedsPermission else state
        TrackingIntent.DisclosureDeclined ->
            if (state == TrackingState.NeedsDisclosure) TrackingState.Off else state
        is TrackingIntent.PermissionResult ->
            when {
                state != TrackingState.NeedsPermission -> state
                intent.granted -> TrackingState.Starting
                else -> TrackingState.Stopped(StopCause.PermissionDenied)
            }
        else -> state
    }

private fun reduceRunning(
    state: TrackingState,
    intent: TrackingIntent,
): TrackingState =
    when (intent) {
        is TrackingIntent.ServiceStarted ->
            if (state == TrackingState.Starting) TrackingState.Active(intent.sinceMs, TripStatus.Waiting) else state
        is TrackingIntent.StatusChanged ->
            if (state is TrackingState.Active) state.copy(lastStatus = intent.status) else state
        TrackingIntent.StopRequested ->
            if (state.running()) TrackingState.Stopped(StopCause.UserStopped) else state
        is TrackingIntent.ServiceStopped ->
            if (state.running()) TrackingState.Stopped(intent.cause) else state
        TrackingIntent.Dismissed -> if (state is TrackingState.Stopped) TrackingState.Off else state
        else -> state
    }
