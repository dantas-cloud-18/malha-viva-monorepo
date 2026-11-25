package br.malhaviva.tracking.live

import br.malhaviva.Problem
import br.malhaviva.platform.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private const val CORRIDOR = "RAMAL_SANTA_CRUZ"
private val HEARTBEAT_PERIOD = 15.seconds
private const val SSE_RETRY_AFTER_S = "5"

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

fun Route.liveRoutes(
    hub: LiveHub,
    connections: ConnectionLimiter = ConnectionLimiter(),
) {
    get("/corridors/{corridor}/trains") {
        val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
        if (key == null) {
            call.badLine()
        } else {
            call.respond(TrainsSnapshot(key.corridor, key.direction, hub.snapshot(key)))
        }
    }
    route("/corridors/{corridor}/trains/stream") {
        install(connectionCap(connections))
        sse {
            val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
            try {
                if (key != null) stream(hub, key)
            } finally {
                call.releaseConnection(connections)
            }
        }
    }
}

private val HELD_IP = AttributeKey<String>("sse.connection.ip")

private fun ApplicationCall.releaseConnection(connections: ConnectionLimiter) {
    attributes.getOrNull(HELD_IP)?.let {
        attributes.remove(HELD_IP)
        connections.release(it)
    }
}

/**
 * Teto de conexoes SSE: acima dele a conexao nem abre (503). A vaga sai ao fechar o stream (finally da rota) ou
 * se a chamada falhar antes de o stream comecar; o atributo garante que sai uma vez so.
 */
private fun connectionCap(connections: ConnectionLimiter) =
    createRouteScopedPlugin("SseConnectionCap") {
        onCall { call ->
            val ip = call.clientIp()
            if (connections.tryAcquire(ip)) {
                call.attributes.put(HELD_IP, ip)
            } else {
                call.response.header(HttpHeaders.RetryAfter, SSE_RETRY_AFTER_S)
                val status = HttpStatusCode.ServiceUnavailable
                call.respond(status, Problem("about:blank", "Too many streams", status.value))
            }
        }
        on(CallFailed) { call, _ -> call.releaseConnection(connections) }
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
