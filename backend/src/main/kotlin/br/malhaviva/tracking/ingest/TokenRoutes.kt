package br.malhaviva.tracking.ingest

import br.malhaviva.Problem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_TOKEN_BODY_BYTES = 4 * 1024
private const val RETRY_AFTER_TOKEN_S = "60"
private const val ISSUE_CAPACITY = 5.0
private const val ISSUE_REFILL_PER_S = 1.0 / 60

/** Limite estrito por IP: emitir token e barato para o cliente e caro de abusar. */
fun tokenIssueLimiter() = RateLimiter(ISSUE_CAPACITY, ISSUE_REFILL_PER_S)

@Serializable
private class TokenRequestDto(
    val platform: ClientPlatform,
    @SerialName("integrity_token") val integrityToken: String,
)

private val strictJson = Json { ignoreUnknownKeys = false }

private suspend fun RoutingCall.problem(
    status: HttpStatusCode,
    title: String,
) = respond(status, Problem("about:blank", title, status.value))

/** Corpo invalido (grande demais, JSON ruim, plataforma desconhecida) vira null. */
private fun parseRequest(body: ByteArray): TokenRequestDto? =
    if (body.size > MAX_TOKEN_BODY_BYTES) {
        null
    } else {
        runCatching { strictJson.decodeFromString<TokenRequestDto>(body.decodeToString()) }.getOrNull()
    }

fun Route.tokenRoutes(
    issuer: TokenIssuer,
    verifier: IntegrityVerifier,
    limiter: RateLimiter = tokenIssueLimiter(),
    clock: () -> Long = System::currentTimeMillis,
) {
    post("/telemetry/tokens") {
        // le no maximo limite + 1 byte; nunca registra corpo, token de integridade ou chave
        val body = call.receiveChannel().readRemaining(MAX_TOKEN_BODY_BYTES + 1L).readByteArray()
        val ip = call.request.local.remoteAddress
        if (!limiter.tryAcquire("ip:$ip", clock())) {
            call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_TOKEN_S)
            call.problem(HttpStatusCode.TooManyRequests, "Too many requests")
        } else {
            val req = parseRequest(body)
            when {
                req == null -> call.problem(HttpStatusCode.BadRequest, "Malformed request")
                !verifier.verify(req.platform, req.integrityToken) ->
                    call.problem(HttpStatusCode.Unauthorized, "Not attested")
                else -> call.respond(HttpStatusCode.Created, issuer.issue())
            }
        }
    }
}
