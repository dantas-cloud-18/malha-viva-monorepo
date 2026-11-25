package br.malhaviva.feature.trains

import br.malhaviva.core.designsystem.TrainStatus

/** Estado da conexao ao vivo. Reconnecting inclui as tentativas com polling de reserva. */
enum class Link { Idle, Connecting, Live, Reconnecting }

data class TrainsState(
    val direction: Direction = Direction.CentralToSantaCruz,
    val line: LineNetwork? = null,
    val networkError: Boolean = false,
    val trains: Map<String, TrackedTrain> = emptyMap(),
    val link: Link = Link.Idle,
    val nowMs: Long = 0L,
    val reconnectAttempt: Int = 0,
    val lastUpdateMs: Long? = null,
)

sealed interface TrainsIntent {
    data class DirectionSelected(
        val direction: Direction,
    ) : TrainsIntent

    data class NetworkLoaded(
        val line: LineNetwork,
    ) : TrainsIntent

    data object NetworkFailed : TrainsIntent

    /** Tela fora de vista (ou app em segundo plano): sem conexao nem tick. */
    data object Paused : TrainsIntent

    data object Connecting : TrainsIntent

    data object StreamOpened : TrainsIntent

    data class TrainReceived(
        val train: LiveTrain,
        val atMs: Long,
    ) : TrainsIntent

    data class SnapshotReceived(
        val trains: List<LiveTrain>,
        val atMs: Long,
    ) : TrainsIntent

    data class StreamLost(
        val attempt: Int,
    ) : TrainsIntent

    data class Tick(
        val nowMs: Long,
    ) : TrainsIntent
}

/** Reducer puro. A posicao exibida nao entra no estado: sai de [projectTrain] a partir de [TrainsState.nowMs]. */
fun reduceTrains(
    state: TrainsState,
    intent: TrainsIntent,
): TrainsState =
    when (intent) {
        is TrainsIntent.DirectionSelected -> selectDirection(state, intent.direction)
        is TrainsIntent.NetworkLoaded -> state.copy(line = intent.line, networkError = false)
        TrainsIntent.NetworkFailed -> state.copy(networkError = true)
        TrainsIntent.Paused -> state.copy(link = Link.Idle)
        TrainsIntent.Connecting ->
            state.copy(link = if (state.reconnectAttempt > 0) Link.Reconnecting else Link.Connecting)
        TrainsIntent.StreamOpened -> state.copy(link = Link.Live)
        is TrainsIntent.TrainReceived -> receive(state, intent.train, intent.atMs)
        is TrainsIntent.SnapshotReceived ->
            state.copy(
                trains = intent.trains.associate { it.id to TrackedTrain(it, intent.atMs) },
                nowMs = maxOf(state.nowMs, intent.atMs),
                lastUpdateMs = intent.atMs,
            )
        is TrainsIntent.StreamLost -> state.copy(link = Link.Reconnecting, reconnectAttempt = intent.attempt)
        is TrainsIntent.Tick ->
            state.copy(
                nowMs = intent.nowMs,
                trains = state.trains.filterValues { intent.nowMs - it.receivedAtMs <= TrainsPolicy.DROP_MS },
            )
    }

private fun selectDirection(
    state: TrainsState,
    direction: Direction,
): TrainsState =
    if (direction == state.direction) {
        state
    } else {
        state.copy(
            direction = direction,
            trains = emptyMap(),
            lastUpdateMs = null,
            reconnectAttempt = 0,
            link = if (state.link == Link.Idle) Link.Idle else Link.Connecting,
        )
    }

private fun receive(
    state: TrainsState,
    train: LiveTrain,
    atMs: Long,
): TrainsState {
    val known = state.trains[train.id]
    // Evento atrasado nao desfaz um mais novo.
    if (known != null && known.train.updatedAtMs > train.updatedAtMs) return state
    return state.copy(
        trains = state.trains + (train.id to TrackedTrain(train, atMs)),
        nowMs = maxOf(state.nowMs, atMs),
        lastUpdateMs = atMs,
        reconnectAttempt = 0,
    )
}

/** Saude da linha para o cabecalho. */
enum class LineHealth(
    val status: TrainStatus,
) {
    Connecting(TrainStatus.NoSignal),
    Live(TrainStatus.OnTime),
    Estimated(TrainStatus.LowConfidence),
    NoSignal(TrainStatus.NoSignal),
}

fun lineHealth(
    state: TrainsState,
    views: List<TrainView>,
): LineHealth =
    when {
        views.any { it.freshness == Freshness.Live } -> LineHealth.Live
        views.any { it.freshness == Freshness.Estimated } -> LineHealth.Estimated
        views.isNotEmpty() || state.link == Link.Reconnecting -> LineHealth.NoSignal
        state.link == Link.Live -> LineHealth.Live
        else -> LineHealth.Connecting
    }

/** Trens projetados em [TrainsState.nowMs], na ordem de viagem. Vazio enquanto a malha nao chegou. */
fun TrainsState.views(): List<TrainView> {
    val network = line ?: return emptyList()
    return trains.values
        .map { projectTrain(it, network, direction, nowMs) }
        .sortedBy { direction.sign * it.alongM }
}
