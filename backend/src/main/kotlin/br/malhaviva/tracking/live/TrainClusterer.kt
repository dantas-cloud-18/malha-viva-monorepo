package br.malhaviva.tracking.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Ultima posicao de um passageiro anonimo; nao carrega nenhum identificador. */
data class PassengerPosition(
    val alongM: Double,
    val speedMps: Double,
    val tsMs: Long,
)

@Serializable
data class TrainState(
    val id: String,
    @SerialName("along_m") val alongM: Double,
    @SerialName("speed_mps") val speedMps: Double,
    val confidence: ConfidenceBand,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

/** Faixa grossa de confianca; o numero exato de contribuintes nunca sai do servidor. */
@Serializable
enum class ConfidenceBand {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
    ;

    companion object {
        /** K..4 -> baixa, 5..9 -> media, 10 ou mais -> alta. */
        const val MEDIUM_FROM = 5
        const val HIGH_FROM = 10

        fun of(contributors: Int): ConfidenceBand =
            when {
                contributors >= HIGH_FROM -> HIGH
                contributors >= MEDIUM_FROM -> MEDIUM
                else -> LOW
            }
    }
}

object ClusterPolicy {
    const val GROUP_GAP_M = 150.0
    const val MATCH_RADIUS_M = 300.0
    const val MAX_AGE_MS = 30_000L
    const val MIN_BAND_M = 100.0
    const val MAD_FACTOR = 3.0

    /** k-anonimato: trem so e publicado com pelo menos k ids efemeros distintos (RIPD R1/R2). */
    const val DEFAULT_MIN_CONTRIBUTORS = 3
    const val MIN_CONTRIBUTORS_FLOOR = 2
    const val MIN_CONTRIBUTORS_ENV = "MALHAVIVA_TRAIN_MIN_CONTRIBUTORS"

    /** Invalido ou abaixo do piso (2) cai no piso; ausente usa o padrao. Nunca publica trem de 1 pessoa. */
    fun minContributorsFromEnv(env: (String) -> String?): Int =
        (env(MIN_CONTRIBUTORS_ENV)?.toIntOrNull() ?: DEFAULT_MIN_CONTRIBUTORS).coerceAtLeast(MIN_CONTRIBUTORS_FLOOR)
}

private fun median(sorted: List<Double>): Double {
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

/** Agrupa posicoes ordenadas: um novo trem comeca quando o salto passa de GROUP_GAP_M. */
private fun groupByGap(sorted: List<PassengerPosition>): List<List<PassengerPosition>> {
    val groups = mutableListOf<MutableList<PassengerPosition>>()
    for (p in sorted) {
        val last = groups.lastOrNull()
        if (last != null && p.alongM - last.last().alongM <= ClusterPolicy.GROUP_GAP_M) {
            last += p
        } else {
            groups += mutableListOf(p)
        }
    }
    return groups
}

/** Mediana dos inliers (banda em torno da mediana, igual ao consenso do app). */
private fun inliersOf(group: List<PassengerPosition>): List<PassengerPosition> {
    val sorted = group.sortedBy { it.alongM }
    val med = median(sorted.map { it.alongM })
    val mad = median(sorted.map { abs(it.alongM - med) }.sorted())
    val band = max(ClusterPolicy.MIN_BAND_M, ClusterPolicy.MAD_FACTOR * mad)
    return sorted.filter { abs(it.alongM - med) <= band }
}

class TrainClusterer(
    private val minContributors: Int = ClusterPolicy.DEFAULT_MIN_CONTRIBUTORS,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Estima os trens de um corredor+sentido. [previous] serve so para manter ids estaveis:
     * cada grupo herda o id do trem anterior mais proximo (ate MATCH_RADIUS_M). Grupos com menos de
     * [minContributors] posicoes (cada uma de um id efemero distinto) nao sao trens publicaveis e saem aqui.
     */
    fun cluster(
        positions: Collection<PassengerPosition>,
        nowMs: Long,
        previous: List<TrainState> = emptyList(),
    ): List<TrainState> {
        val fresh = positions.filter { nowMs - it.tsMs <= ClusterPolicy.MAX_AGE_MS }.sortedBy { it.alongM }
        val drafts = groupByGap(fresh).filter { it.size >= minContributors }.map { draft(it) }
        val ids = assignIds(drafts.map { it.alongM }, previous)
        return drafts.mapIndexed { i, d -> d.copy(id = ids[i]) }
    }

    private fun draft(group: List<PassengerPosition>): TrainState {
        val inliers = inliersOf(group)
        return TrainState(
            id = "",
            alongM = median(inliers.map { it.alongM }),
            speedMps = median(inliers.map { it.speedMps }.sorted()),
            confidence = ConfidenceBand.of(group.size),
            updatedAtMs = group.maxOf { it.tsMs },
        )
    }

    /** Casamento guloso por distancia crescente; sobras recebem id novo. */
    private fun assignIds(
        alongs: List<Double>,
        previous: List<TrainState>,
    ): List<String> {
        val ids = arrayOfNulls<String>(alongs.size)
        val usedPrev = mutableSetOf<String>()
        val pairs =
            alongs.indices
                .flatMap { i -> previous.map { Triple(i, it, abs(it.alongM - alongs[i])) } }
                .filter { it.third <= ClusterPolicy.MATCH_RADIUS_M }
                .sortedBy { it.third }
        for ((i, prev, _) in pairs) {
            if (ids[i] == null && usedPrev.add(prev.id)) ids[i] = prev.id
        }
        return ids.map { it ?: newId() }
    }
}
