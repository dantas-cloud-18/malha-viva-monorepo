package br.malhaviva.feature.trains

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.trains.net.Backoff
import br.malhaviva.feature.trains.net.TrainsFeed
import br.malhaviva.feature.trains.net.TrainsSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Liga o [TrainsSource] ao estado. Tudo (conexao, polling, tick) so roda entre [setActive] true e false:
 * a tela chama conforme o ciclo de vida, entao nada consome rede ou bateria fora de vista.
 */
class TrainsViewModel(
    private val source: TrainsSource,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val backoff: Backoff = Backoff(),
) : MviViewModel<TrainsState, TrainsIntent>(TrainsState()) {
    private var session: Job? = null

    fun setActive(active: Boolean) {
        if (active == (session?.isActive == true)) return
        if (active) start() else stop()
    }

    fun selectDirection(direction: Direction) {
        if (direction == state.value.direction) return
        onIntent(TrainsIntent.DirectionSelected(direction))
        if (session?.isActive == true) start()
    }

    /** Tenta de novo agora (botao da tela de erro): zera o backoff da malha e reabre o stream. */
    fun retry() {
        if (session?.isActive == true) start()
    }

    override fun reduce(
        state: TrainsState,
        intent: TrainsIntent,
    ): TrainsState = reduceTrains(state, intent)

    private fun start() {
        session?.cancel()
        onIntent(TrainsIntent.Tick(clock()))
        session =
            viewModelScope.launch {
                launch { tick() }
                launch { loadNetwork() }
                source.feed(state.value.direction).collect { onIntent(it.toIntent()) }
            }
    }

    private fun stop() {
        session?.cancel()
        session = null
        onIntent(TrainsIntent.Paused)
    }

    private suspend fun tick() {
        while (true) {
            delay(TrainsPolicy.TICK_MS)
            onIntent(TrainsIntent.Tick(clock()))
        }
    }

    private suspend fun loadNetwork() {
        var attempt = 0
        while (state.value.line == null) {
            val line = source.network()
            if (line != null) {
                onIntent(TrainsIntent.NetworkLoaded(line))
            } else {
                onIntent(TrainsIntent.NetworkFailed)
                delay(backoff.delayMs(++attempt))
            }
        }
    }

    private fun TrainsFeed.toIntent(): TrainsIntent =
        when (this) {
            TrainsFeed.Connecting -> TrainsIntent.Connecting
            TrainsFeed.Opened -> TrainsIntent.StreamOpened
            is TrainsFeed.Train -> TrainsIntent.TrainReceived(train, clock())
            is TrainsFeed.Snapshot -> TrainsIntent.SnapshotReceived(trains, clock())
            is TrainsFeed.Lost -> TrainsIntent.StreamLost(attempt)
        }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
