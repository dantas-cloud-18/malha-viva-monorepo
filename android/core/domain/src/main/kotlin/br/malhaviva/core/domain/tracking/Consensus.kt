package br.malhaviva.core.domain.tracking

import kotlin.math.abs
import kotlin.math.max

data class Consensus(
    val alongM: Double,
    val signals: Int,
    val inliers: Int,
    /** 0..1. Um sinal so = baixa confianca (BRIEF 3.3). */
    val confidence: Double,
)

object ConsensusPolicy {
    const val MIN_BAND_M = 100.0
    const val MAD_FACTOR = 3.0
    const val CONF_ONE = 0.3
    const val CONF_TWO = 0.5
    const val CONF_BASE = 0.6
    const val CONF_RANGE = 0.4
    const val MIN_TRUSTED = 3
}

private fun median(sorted: List<Double>): Double {
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

/**
 * Posicao robusta de um trem a partir das posicoes (ao longo do traçado) de varios
 * passageiros: mediana dos que estao proximos da mediana; descarta outliers (ate ~30% falsos).
 */
fun consensus(positions: List<Double>): Consensus? {
    if (positions.isEmpty()) return null
    val sorted = positions.sorted()
    val med = median(sorted)
    val mad = median(sorted.map { abs(it - med) }.sorted())
    val band = max(ConsensusPolicy.MIN_BAND_M, ConsensusPolicy.MAD_FACTOR * mad)
    val inliers = sorted.filter { abs(it - med) <= band }
    val ratio = inliers.size.toDouble() / sorted.size
    val confidence =
        when {
            sorted.size == 1 -> ConsensusPolicy.CONF_ONE
            sorted.size == 2 -> ConsensusPolicy.CONF_TWO
            else -> ConsensusPolicy.CONF_BASE + ConsensusPolicy.CONF_RANGE * ratio
        }
    return Consensus(median(inliers), sorted.size, inliers.size, confidence)
}
