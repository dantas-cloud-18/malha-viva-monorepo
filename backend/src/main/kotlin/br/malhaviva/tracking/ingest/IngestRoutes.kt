package br.malhaviva.tracking.ingest

import br.malhaviva.Problem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private const val BEARER_PREFIX = "Bearer "
private const val RETRY_AFTER_S = "5"
private const val TOO_MANY = 429

/** IP do vizinho de rede; atras de proxy, instalar tratamento de X-Forwarded-For antes. */
private fun RoutingCall.toIngestRequest(body: ByteArray) =
    IngestRequest(
        bearer = request.header(HttpHeaders.Authorization)?.removePrefix(BEARER_PREFIX)?.takeIf { it.isNotBlank() },
        clientIp = request.local.remoteAddress,
        timestamp = request.header("X-Timestamp"),
        nonce = request.header("X-Nonce"),
        signature = request.header("X-Signature"),
        body = body,
    )

fun Route.ingestRoutes(ingestor: BatchIngestor) {
    post("/telemetry/batches") {
        // le no maximo MAX_BODY_BYTES + 1: o excesso e rejeitado sem carregar o corpo todo
        val body = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
        try {
            call.respond(HttpStatusCode.Accepted, ingestor.ingest(call.toIngestRequest(body)))
        } catch (e: IngestRejected) {
            if (e.status == TOO_MANY) call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_S)
            call.respond(HttpStatusCode.fromValue(e.status), Problem("about:blank", e.title, e.status))
        }
    }
}
