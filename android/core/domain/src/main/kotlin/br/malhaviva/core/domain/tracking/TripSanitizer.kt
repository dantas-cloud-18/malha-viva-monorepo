package br.malhaviva.core.domain.tracking

import br.malhaviva.core.domain.checkin.Reading
import kotlin.math.abs

object PrivacyPolicy {
    /** Metros cortados no inicio e no fim da viagem (protege casa e trabalho). */
    const val TRIM_M = 300.0
}

/**
 * Antes de enviar: descarta pontos fora do corredor e corta o inicio e o fim da viagem.
 * Viagem curta demais devolve lista vazia (nada e enviado).
 */
fun sanitizeTrip(
    readings: List<Reading>,
    route: Route,
    trimM: Double = PrivacyPolicy.TRIM_M,
): List<Reading> {
    val inCorridor =
        readings
            .map { it to route.project(it.point) }
            .filter { it.second.lateralM <= TrackPolicy.MAX_LATERAL_M }
    if (inCorridor.isEmpty()) return emptyList()
    val start = inCorridor.first().second.alongM
    val end = inCorridor.last().second.alongM
    return inCorridor
        .filter { (_, p) -> abs(p.alongM - start) >= trimM && abs(p.alongM - end) >= trimM }
        .map { it.first }
}
