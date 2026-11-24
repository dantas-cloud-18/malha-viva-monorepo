package br.malhaviva.core.domain.sampling

import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.checkin.distanceM

/** Limites PROPOSTOS para pausar o envio quando o aparelho esta parado. */
object StationaryPolicy {
    const val PAUSE_RADIUS_M = 25.0
    const val PAUSE_AFTER_MS = 120_000L
    const val RESUME_RADIUS_M = 50.0
}

/**
 * Consome leituras e diz se o envio deve pausar: parado dentro de 25 m por 2 min ou mais.
 * Retoma ao se afastar mais de 50 m da ancora.
 */
class StationaryDetector {
    private var anchor: Reading? = null
    var shouldPause: Boolean = false
        private set

    fun update(r: Reading): Boolean {
        val a = anchor
        val dist = a?.let { distanceM(it.point, r.point) } ?: 0.0
        when {
            a == null -> anchor = r
            shouldPause -> if (dist > StationaryPolicy.RESUME_RADIUS_M) restart(r)
            dist > StationaryPolicy.PAUSE_RADIUS_M -> restart(r)
            r.timeMs - a.timeMs >= StationaryPolicy.PAUSE_AFTER_MS -> shouldPause = true
        }
        return shouldPause
    }

    private fun restart(r: Reading) {
        anchor = r
        shouldPause = false
    }
}
