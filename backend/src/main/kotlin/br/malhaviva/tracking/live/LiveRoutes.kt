package br.malhaviva.tracking.live

import br.malhaviva.Problem
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private const val CORRIDOR = "RAMAL_SANTA_CRUZ"
private val HEARTBEAT_PERIOD = 15.seconds

@Serializable
data class TrainsSnapshot(
    val corridor: String,
    val direction: Direction,
    val trains: List<TrainState>,
)

private fun lineKey(
    corridor: String?,
    direction: String?,
): LineKey? {
    val dir = Direction.entries.firstOrNull { it.name == direction }
    return if (corridor == CORRIDOR && dir != null) LineKey(corridor, dir) else null
}

fun Route.liveRoutes(hub: LiveHub) {
    get("/corridors/{corridor}/trains") {
        val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
        if (key == null) {
            call.badLine()
        } else {
            call.respond(TrainsSnapshot(key.corridor, key.direction, hub.snapshot(key)))
        }
    }
    sse("/corridors/{corridor}/trains/stream") {
        val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
        if (key != null) stream(hub, key)
    }
}

private suspend fun RoutingCall.badLine() {
    val status = HttpStatusCode.BadRequest
    respond(status, Problem("about:blank", "Invalid corridor or direction", status.value))
}

private suspend fun ServerSSESession.stream(
    hub: LiveHub,
    key: LineKey,
) {
    heartbeat {
        period = HEARTBEAT_PERIOD
        event = ServerSentEvent(comments = "heartbeat")
    }
    hub.snapshot(key).forEach { send(trainEvent(it)) }
    hub.changes.collect { if (it.key == key) send(trainEvent(it.train)) }
}

private fun trainEvent(train: TrainState) =
    ServerSentEvent(data = Json.encodeToString(TrainState.serializer(), train), event = "train")
