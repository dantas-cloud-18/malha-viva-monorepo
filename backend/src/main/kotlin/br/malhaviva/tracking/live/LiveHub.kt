package br.malhaviva.tracking.live

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class Direction { SANTA_CRUZ_TO_CENTRAL, CENTRAL_TO_SANTA_CRUZ }

data class LineKey(
    val corridor: String,
    val direction: Direction,
)

/** Mudanca publicada no SharedFlow: o trem novo ou atualizado de um corredor+sentido. */
data class TrainChange(
    val key: LineKey,
    val train: TrainState,
)

private const val CHANGE_BUFFER = 256

/**
 * Estado em memoria dos trens. As chaves de passageiro (ids efemeros) ficam so aqui dentro
 * e nunca saem em TrainState. Sem persistencia: reinicio zera tudo.
 */
class LiveHub(
    private val clock: () -> Long = System::currentTimeMillis,
    private val clusterer: TrainClusterer = TrainClusterer(),
) {
    private val positions = HashMap<LineKey, MutableMap<String, PassengerPosition>>()
    private val trains = HashMap<LineKey, List<TrainState>>()
    private val flow =
        MutableSharedFlow<TrainChange>(
            extraBufferCapacity = CHANGE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val changes: SharedFlow<TrainChange> = flow.asSharedFlow()

    fun update(
        key: LineKey,
        ephemeralId: String,
        position: PassengerPosition,
    ) {
        val changed =
            synchronized(this) {
                positions.getOrPut(key) { HashMap() }[ephemeralId] = position
                val before = trains[key].orEmpty()
                recompute(key).filter { it !in before }
            }
        changed.forEach { flow.tryEmit(TrainChange(key, it)) }
    }

    fun snapshot(key: LineKey): List<TrainState> = synchronized(this) { recompute(key) }

    /** Remove posicoes velhas, reagrupa e guarda o resultado. Chamar dentro do lock. */
    private fun recompute(key: LineKey): List<TrainState> {
        val now = clock()
        val current = positions[key].orEmpty().filterValues { now - it.tsMs <= ClusterPolicy.MAX_AGE_MS }
        positions[key] = current.toMutableMap()
        val result = clusterer.cluster(current.values, now, trains[key].orEmpty())
        trains[key] = result
        return result
    }
}
