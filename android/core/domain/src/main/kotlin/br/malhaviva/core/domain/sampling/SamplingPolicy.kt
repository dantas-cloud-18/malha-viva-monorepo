package br.malhaviva.core.domain.sampling

/** Intervalos de amostragem do GPS (BRIEF 3.1: adaptativo de 5 a 15 s); valores PROPOSTOS, calibrar em campo. */
object SamplingPolicy {
    const val FAST_SPEED_MS = 10.0

    /** Periodo do envio de lotes durante a viagem (posicao quase em tempo real); o contrato descreve 5 s. */
    const val LIVE_UPLOAD_INTERVAL_MS = 5_000L
    const val FAST_INTERVAL_MS = 5_000L
    const val SLOW_INTERVAL_MS = 10_000L
    const val IDLE_INTERVAL_MS = 15_000L
}

/** Proximo intervalo: parado ou bateria baixa economiza; rapido amostra mais. */
fun nextIntervalMs(
    speedMs: Double,
    stationary: Boolean,
    batteryLow: Boolean,
): Long =
    when {
        stationary || batteryLow -> SamplingPolicy.IDLE_INTERVAL_MS
        speedMs > SamplingPolicy.FAST_SPEED_MS -> SamplingPolicy.FAST_INTERVAL_MS
        else -> SamplingPolicy.SLOW_INTERVAL_MS
    }
