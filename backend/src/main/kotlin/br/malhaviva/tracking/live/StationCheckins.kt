package br.malhaviva.tracking.live

import br.malhaviva.Problem
import br.malhaviva.platform.clientIp
import br.malhaviva.tracking.ingest.RateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

object CheckinPolicy {
    /** Validade de um check-in "estou nesta estacao" (RIPD R3). */
    const val TTL_MS = 10 * 60_000L

    /** Teto de ids vivos por estacao+sentido: o limite por IP ja freia abuso, isto protege a memoria. */
    const val MAX_PER_STATION = 1_000
    const val RATE_CAPACITY = 5.0
    const val RATE_REFILL_PER_S = 1.0 / 60
}

/**
 * Check-ins anonimos por (corredor, sentido, estacao): so ids aleatorios efemeros com validade, em memoria, sem
 * conta, sem IP, sem posicao. Reinicio zera tudo. Thread-safe (um lock, operacoes curtas).
 */
class StationCheckins(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = CheckinPolicy.TTL_MS,
) {
    private val byStation = HashMap<Triple<String, Direction, String>, MutableMap<String, Long>>()

    /** Repetir o mesmo id so renova a validade (idempotente); id novo acima do teto e ignorado. */
    fun add(
        key: LineKey,
        stationId: String,
        id: String,
    ) = synchronized(byStation) {
        val ids = live(key, stationId)
        if (id in ids || ids.size < CheckinPolicy.MAX_PER_STATION) ids[id] = clock() + ttlMs
    }

    fun cancel(
        key: LineKey,
        stationId: String,
        id: String,
    ) = synchronized(byStation) { live(key, stationId).remove(id) }

    /** Check-ins vivos por estacao (so contagem; os ids nao saem daqui). */
    fun counts(key: LineKey): Map<String, Int> =
        synchronized(byStation) {
            byStation.keys
                .filter { it.first == key.corridor && it.second == key.direction }
                .associate { it.third to live(key, it.third).size }
        }

    private fun live(
        key: LineKey,
        stationId: String,
    ): MutableMap<String, Long> {
        val now = clock()
        val ids = byStation.getOrPut(Triple(key.corridor, key.direction, stationId)) { HashMap() }
        ids.values.removeAll { it <= now }
        return ids
    }
}

@Serializable
private class CheckinRequest(
    val direction: Direction,
    @SerialName("checkin_id") val checkinId: String,
)

private fun validId(id: String) = runCatching { UUID.fromString(id) }.isSuccess

/** [stations] devolve as estacoes do corredor, ou null se a malha nao existir. Sem autenticacao, de proposito. */
fun Route.checkinRoutes(
    checkins: StationCheckins,
    stations: (String) -> List<StationRef>?,
    limiter: RateLimiter = RateLimiter(CheckinPolicy.RATE_CAPACITY, CheckinPolicy.RATE_REFILL_PER_S),
    clock: () -> Long = System::currentTimeMillis,
) {
    post("/corridors/{corridor}/stations/{station_id}/checkins") {
        // limite por IP (o IP nunca e guardado com o check-in); o id e so um UUID aleatorio gerado no aparelho
        if (!limiter.tryAcquire("ip:${call.clientIp()}", clock())) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.problem(HttpStatusCode.TooManyRequests, "Too many requests")
        } else {
            val body = runCatching { call.receive<CheckinRequest>() }.getOrNull()
            val key = body?.let { lineKey(call.parameters["corridor"], it.direction.name) }
            val stationId = call.parameters["station_id"]
            when {
                body == null || key == null || !validId(body.checkinId) ->
                    call.problem(HttpStatusCode.BadRequest, "Invalid check-in")
                stations(key.corridor)?.any { it.id == stationId } != true ->
                    call.problem(HttpStatusCode.NotFound, "Unknown station")
                else -> {
                    checkins.add(key, stationId!!, body.checkinId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
    delete("/corridors/{corridor}/stations/{station_id}/checkins/{checkin_id}") {
        val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
        val id = call.parameters["checkin_id"].orEmpty()
        if (key == null || !validId(id)) {
            call.badLine()
        } else {
            checkins.cancel(key, call.parameters["station_id"].orEmpty(), id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun RoutingCall.problem(
    status: HttpStatusCode,
    title: String,
) = respond(status, Problem("about:blank", title, status.value))
