package br.malhaviva.tracking.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Faixa grossa de lotacao; a contagem exata nunca sai do servidor. */
@Serializable
enum class CrowdingLevel {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
    ;

    companion object {
        /** K..9 -> baixa (o degrau mais baixo ja exige K), 10..19 -> media, 20 ou mais -> alta. */
        const val MEDIUM_FROM = 10
        const val HIGH_FROM = 20

        fun of(contributors: Int): CrowdingLevel =
            when {
                contributors >= HIGH_FROM -> HIGH
                contributors >= MEDIUM_FROM -> MEDIUM
                else -> LOW
            }
    }
}

object CrowdingPolicy {
    /** Estacao = ate 100 m ao longo da via; parado = ate 1 m/s. Limites propostos, calibrar com dado real. */
    const val STATION_RADIUS_M = 100.0
    const val DWELL_MAX_SPEED_MPS = 1.0

    /** k-anonimato da lotacao (RIPD R3, k_lot = 5): nunca publica nivel com menos de 5 viagens distintas. */
    const val DEFAULT_MIN_CONTRIBUTORS = 5
    const val MIN_CONTRIBUTORS_FLOOR = 5
    const val MIN_CONTRIBUTORS_ENV = "MALHAVIVA_STATION_MIN_CONTRIBUTORS"

    /** Invalido ou abaixo do piso (5) cai no piso; ausente usa o padrao. */
    fun minContributorsFromEnv(env: (String) -> String?): Int =
        (env(MIN_CONTRIBUTORS_ENV)?.toIntOrNull() ?: DEFAULT_MIN_CONTRIBUTORS).coerceAtLeast(MIN_CONTRIBUTORS_FLOOR)
}

/** Estacao no eixo da via (metros desde Central do Brasil, igual ao `along_m` das posicoes). */
data class StationRef(
    val id: String,
    val alongM: Double,
)

/** `level` nulo = "sem dados de lotacao". */
@Serializable
data class StationCrowding(
    @SerialName("station_id") val stationId: String,
    val level: CrowdingLevel?,
)

@Serializable
data class StationCrowdingSnapshot(
    val corridor: String,
    val direction: Direction,
    val stations: List<StationCrowding>,
)

class StationCrowdingEstimator(
    private val minContributors: Int = CrowdingPolicy.DEFAULT_MIN_CONTRIBUTORS,
) {
    /**
     * [positions] vem do LiveHub: uma por id efemero de viagem, entao cada viagem conta uma vez por estacao.
     * Conta so as paradas (velocidade baixa) dentro do raio da estacao; abaixo de [minContributors] vira null.
     */
    fun estimate(
        positions: Collection<PassengerPosition>,
        stations: List<StationRef>,
    ): List<StationCrowding> {
        val dwelling = positions.filter { it.speedMps <= CrowdingPolicy.DWELL_MAX_SPEED_MPS }
        return stations.map { s ->
            val n = dwelling.count { abs(it.alongM - s.alongM) <= CrowdingPolicy.STATION_RADIUS_M }
            StationCrowding(s.id, if (n >= minContributors) CrowdingLevel.of(n) else null)
        }
    }
}
