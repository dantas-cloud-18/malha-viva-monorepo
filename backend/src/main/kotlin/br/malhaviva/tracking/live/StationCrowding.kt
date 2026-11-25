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

        /** Primeira contagem da faixa; a mais baixa comeca em K, entao e null aqui. */
        fun lowerBound(level: CrowdingLevel): Int? =
            when (level) {
                LOW -> null
                MEDIUM -> MEDIUM_FROM
                HIGH -> HIGH_FROM
            }

        /** Ultima contagem da faixa, ou null se aberta (alta). */
        fun upperBound(level: CrowdingLevel): Int? =
            when (level) {
                LOW -> MEDIUM_FROM - 1
                MEDIUM -> HIGH_FROM - 1
                HIGH -> null
            }

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

    /**
     * Anti-diferenciacao (RIPD 7.1): o nivel publicado so muda entre faixas visiveis (e de null para visivel) depois
     * que o novo nivel bruto ficou estavel por HOLD_MS; e so se a contagem sair da faixa atual por mais de
     * HYSTERESIS_MARGIN. Cair abaixo de K publica null na hora (nunca mostra nivel sob K).
     */
    const val HOLD_MS = 60_000L
    const val HYSTERESIS_MARGIN = 2

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
     * Conta so as paradas (velocidade baixa) dentro do raio da estacao.
     */
    fun counts(
        positions: Collection<PassengerPosition>,
        stations: List<StationRef>,
    ): List<Pair<String, Int>> {
        val dwelling = positions.filter { it.speedMps <= CrowdingPolicy.DWELL_MAX_SPEED_MPS }
        return stations.map { s ->
            s.id to dwelling.count { abs(it.alongM - s.alongM) <= CrowdingPolicy.STATION_RADIUS_M }
        }
    }

    /** Nivel bruto, sem suavizacao: abaixo de [minContributors] vira null. */
    fun estimate(
        positions: Collection<PassengerPosition>,
        stations: List<StationRef>,
    ): List<StationCrowding> =
        counts(positions, stations).map { (id, n) ->
            StationCrowding(id, if (n >= minContributors) CrowdingLevel.of(n) else null)
        }
}

/**
 * Nivel publicado por (corredor, sentido, estacao), com histerese e espera minima (CrowdingPolicy). Memoria limitada
 * as estacoes semeadas (a rota so chega aqui com corredor valido). Thread-safe: um lock, operacao curta em memoria.
 * shortcut: "estavel" e medido entre consultas, entao o hold so avanca com polling; upgrade: tick de fundo.
 */
class StationCrowdingSmoother(
    private val minContributors: Int = CrowdingPolicy.DEFAULT_MIN_CONTRIBUTORS,
    private val holdMs: Long = CrowdingPolicy.HOLD_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(
        var published: CrowdingLevel? = null,
        var candidate: CrowdingLevel? = null,
        var candidateSince: Long = 0,
    )

    private val states = HashMap<Triple<String, Direction, String>, State>()

    fun publish(
        key: LineKey,
        counts: List<Pair<String, Int>>,
    ): List<StationCrowding> =
        synchronized(states) {
            val now = clock()
            counts.map { (id, n) ->
                val st = states.getOrPut(Triple(key.corridor, key.direction, id)) { State() }
                StationCrowding(id, step(st, n, now))
            }
        }

    private fun step(
        st: State,
        n: Int,
        now: Long,
    ): CrowdingLevel? {
        val cur = st.published
        val target =
            when {
                n < minContributors -> null
                cur != null && inBand(cur, n) -> cur
                else -> CrowdingLevel.of(n)
            }
        when {
            target == cur -> st.candidate = null
            target == null -> {
                st.published = null
                st.candidate = null
            }
            else -> {
                if (st.candidate != target) {
                    st.candidate = target
                    st.candidateSince = now
                }
                if (now - st.candidateSince >= holdMs) {
                    st.published = target
                    st.candidate = null
                }
            }
        }
        return st.published
    }

    private fun inBand(
        level: CrowdingLevel,
        n: Int,
    ): Boolean {
        val lo = (CrowdingLevel.lowerBound(level) ?: minContributors) - CrowdingPolicy.HYSTERESIS_MARGIN
        val hi = CrowdingLevel.upperBound(level)?.plus(CrowdingPolicy.HYSTERESIS_MARGIN) ?: Int.MAX_VALUE
        return n in lo..hi
    }
}
