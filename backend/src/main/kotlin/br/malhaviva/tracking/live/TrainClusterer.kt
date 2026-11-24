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
    val signals: Int,
    val confidence: Double,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

object ClusterPolicy {
    const val GROUP_GAP_M = 150.0
    const val MATCH_RADIUS_M = 300.0
    const val MAX_AGE_MS = 30_000L
    const val MIN_BAND_M = 100.0
    const val MAD_FACTOR = 3.0
    const val CONF_ONE = 0.3
    const val CONF_TWO = 0.5
    const val CONF_BASE = 0.6
    const val CONF_RANGE = 0.4
}

private fun median(sorted: List<Double>): Double {
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

private fun confidence(
    signals: Int,
    inlierRatio: Double,
): Double =
    when (signals) {
        1 -> ClusterPolicy.CONF_ONE
        2 -> ClusterPolicy.CONF_TWO
        else -> ClusterPolicy.CONF_BASE + ClusterPolicy.CONF_RANGE * inlierRatio
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
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Estima os trens de um corredor+sentido. [previous] serve so para manter ids estaveis:
     * cada grupo herda o id do trem anterior mais proximo (ate MATCH_RADIUS_M).
     */
    fun cluster(
        positions: Collection<PassengerPosition>,
        nowMs: Long,
        previous: List<TrainState> = emptyList(),
    ): List<TrainState> {
        val fresh = positions.filter { nowMs - it.tsMs <= ClusterPolicy.MAX_AGE_MS }.sortedBy { it.alongM }
        val drafts = groupByGap(fresh).map { draft(it) }
        val ids = assignIds(drafts.map { it.alongM }, previous)
        return drafts.mapIndexed { i, d -> d.copy(id = ids[i]) }
    }

    private fun draft(group: List<PassengerPosition>): TrainState {
        val inliers = inliersOf(group)
        return TrainState(
            id = "",
            alongM = median(inliers.map { it.alongM }),
            speedMps = median(inliers.map { it.speedMps }.sorted()),
            signals = group.size,
            confidence = confidence(group.size, inliers.size.toDouble() / group.size),
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
