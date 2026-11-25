package br.malhaviva.tracking.ingest

import br.malhaviva.Problem
import br.malhaviva.platform.clientIp
import br.malhaviva.platform.readCapped
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post

private const val BEARER_PREFIX = "Bearer "
private const val RETRY_AFTER_S = "5"
private const val TOO_MANY = 429

/** O IP vem do helper unico [clientIp] (XForwardedHeaders atras do ALB). */
private fun RoutingCall.toIngestRequest(body: ByteArray) =
    IngestRequest(
        bearer = request.header(HttpHeaders.Authorization)?.removePrefix(BEARER_PREFIX)?.takeIf { it.isNotBlank() },
        clientIp = clientIp(),
        timestamp = request.header("X-Timestamp"),
        nonce = request.header("X-Nonce"),
        signature = request.header("X-Signature"),
        body = body,
    )

fun Route.ingestRoutes(ingestor: BatchIngestor) {
    post("/telemetry/batches") {
        try {
            // limite por IP antes de ler o corpo; a leitura para em MAX_BODY_BYTES + 1 (excesso nao e carregado)
            ingestor.admitIp(call.clientIp())
            val body = call.readCapped(MAX_BODY_BYTES)
            call.respond(HttpStatusCode.Accepted, ingestor.ingest(call.toIngestRequest(body)))
        } catch (e: IngestRejected) {
            if (e.status == TOO_MANY) call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_S)
            call.respond(HttpStatusCode.fromValue(e.status), Problem("about:blank", e.title, e.status))
        }
    }
}
