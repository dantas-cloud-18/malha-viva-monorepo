package br.malhaviva.feature.trains

/** Sentido de viagem. Em `along_m` (metros desde Central do Brasil), Central -> Santa Cruz e crescente. */
enum class Direction(
    val wire: String,
    val sign: Int,
) {
    CentralToSantaCruz("CENTRAL_TO_SANTA_CRUZ", 1),
    SantaCruzToCentral("SANTA_CRUZ_TO_CENTRAL", -1),
}

/** `TrainState` do contrato. O id e efemero e nao identifica ninguem. */
data class LiveTrain(
    val id: String,
    val alongM: Double,
    val speedMps: Double,
    val signals: Int,
    val confidence: Double,
    val updatedAtMs: Long,
)

/** [receivedAtMs] vem do relogio do aparelho (monotonico): a idade do dado nao depende do relogio do servidor. */
data class TrackedTrain(
    val train: LiveTrain,
    val receivedAtMs: Long,
)

data class LineStation(
    val id: String,
    val name: String,
    val alongM: Double,
)

/** Estacoes em ordem crescente de `along_m` (Central -> Santa Cruz). */
data class LineNetwork(
    val stations: List<LineStation>,
    val lengthM: Double,
)

/** Limites PROPOSTOS; calibrar com o ritmo real de eventos do servidor. */
object TrainsPolicy {
    const val FRESH_MS = 15_000L
    const val NO_SIGNAL_MS = 60_000L
    const val DROP_MS = 180_000L
    const val ARRIVAL_M = 80.0
    const val STOPPED_MPS = 2.0
    const val MOVING_MPS = 1.0
    const val STATION_EPS_M = 1.0
    const val LOW_CONFIDENCE = 0.5
    const val TICK_MS = 1_000L
    const val MS_PER_S = 1000.0
    const val S_PER_MIN = 60.0
    const val MAX_ETA_MIN = 99
}

enum class Freshness { Live, Estimated, NoSignal }

/** Posicao exibida de um trem em um instante: ja extrapolada e limitada pela proxima estacao. */
data class TrainView(
    val id: String,
    val alongM: Double,
    val speedMps: Double,
    val signals: Int,
    val confidence: Double,
    val freshness: Freshness,
    val atStationId: String?,
)
