package br.malhaviva.feature.trains

/** Sentido de viagem. Em `along_m` (metros desde Central do Brasil), Central -> Santa Cruz e crescente. */
enum class Direction(
    val wire: String,
    val sign: Int,
) {
    CentralToSantaCruz("CENTRAL_TO_SANTA_CRUZ", 1),
    SantaCruzToCentral("SANTA_CRUZ_TO_CENTRAL", -1),
}

private const val LEVEL_LOW = 0.3
private const val LEVEL_MEDIUM = 0.6
private const val LEVEL_HIGH = 0.9

/** Faixa de confianca do contrato (`low|medium|high`); [level] e so a intensidade exibida no anel. */
enum class ConfidenceBand(
    val wire: String,
    val level: Double,
) {
    Low("low", LEVEL_LOW),
    Medium("medium", LEVEL_MEDIUM),
    High("high", LEVEL_HIGH),
    ;

    companion object {
        fun fromWire(wire: String): ConfidenceBand? = entries.firstOrNull { it.wire == wire }
    }
}

/** `situation` do contrato; `StoppedBetweenStations` e o alerta de trem parado fora de estacao. */
enum class Situation(
    val wire: String,
) {
    Moving("moving"),
    AtStation("at_station"),
    StoppedBetweenStations("stopped_between_stations"),
    ;

    companion object {
        fun fromWire(wire: String): Situation? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `TrainState` do contrato. O id e efemero e nao identifica ninguem. [stationId] e a estacao atual (parado em
 * estacao) ou a proxima; [etasS] traz segundos ate cada estacao a frente, contados do instante da resposta.
 */
data class LiveTrain(
    val id: String,
    val alongM: Double,
    val speedMps: Double,
    val confidence: ConfidenceBand,
    val updatedAtMs: Long,
    val situation: Situation = Situation.Moving,
    val stationId: String? = null,
    val etasS: Map<String, Int> = emptyMap(),
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
    /** Geometria geografica para o mapa; ausente se a malha nao trouxe plataformas/trechos. */
    val shape: LineShape? = null,
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
    const val CROWDING_POLL_MS = 15_000L

    /** Validade do "Estou nesta estacao" (igual ao servidor, RIPD R3). */
    const val CHECKIN_TTL_MS = 10 * 60_000L
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
    val band: ConfidenceBand,
    val confidence: Double,
    val freshness: Freshness,
    val atStationId: String?,
    val situation: Situation = Situation.Moving,
    val stationId: String? = null,
    /** ETA do servidor por estacao, em segundos, ja descontada a idade do dado. */
    val etasS: Map<String, Int> = emptyMap(),
)

/** Faixa grossa de lotacao do contrato (`low|medium|high`). Sem dados = ausente do mapa ou nulo. */
enum class CrowdingLevel(
    val wire: String,
) {
    Low("low"),
    Medium("medium"),
    High("high"),
    ;

    companion object {
        fun fromWire(wire: String): CrowdingLevel? = entries.firstOrNull { it.wire == wire }
    }
}
