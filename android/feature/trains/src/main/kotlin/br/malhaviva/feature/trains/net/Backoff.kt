package br.malhaviva.feature.trains.net

import kotlin.random.Random

/**
 * Espera entre tentativas de reconexao: exponencial (1 s, 2 s, 4 s ... ate [capMs]) com jitter "equal"
 * (metade fixa + metade sorteada), para nao sincronizar milhares de clientes apos uma queda.
 * [Retry-After] do servidor e piso da espera. [random] devolve valores em [0, 1).
 */
class Backoff(
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
    private val random: () -> Double = { Random.nextDouble() },
) {
    /** [attempt] e 1 na primeira falha seguida. */
    fun delayMs(
        attempt: Int,
        retryAfterMs: Long? = null,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val ceiling = minOf(capMs, baseMs shl exponent)
        val half = ceiling / 2
        val jittered = half + (random().coerceIn(0.0, 1.0) * (ceiling - half)).toLong()
        val floor = (retryAfterMs ?: 0L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        return maxOf(jittered, floor)
    }

    companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 30_000L
        const val MAX_RETRY_AFTER_MS = 60_000L
        private const val MAX_EXPONENT = 20
    }
}
