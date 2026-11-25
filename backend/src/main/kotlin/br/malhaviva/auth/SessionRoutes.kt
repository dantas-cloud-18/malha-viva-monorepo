package br.malhaviva.auth

import br.malhaviva.platform.clientIp
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

private const val MAX_HANDLE_LENGTH = 64
private const val MAX_TOKEN_LENGTH = 512

@Serializable
private class LoginDto(
    val handle: String,
    val password: String,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("captcha_token") val captchaToken: String? = null,
)

@Serializable
private class RefreshDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
private class DeviceDto(
    val id: String,
    val label: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String,
)

/** Login, refresh, logout e dispositivos. Todas consultam o limite por IP antes de ler o corpo. */
internal fun Route.sessionRoutes(c: AuthComponents) {
    post("/auth/token") { loginRoute(call, c) }
    post("/auth/refresh") { refreshRoute(call, c) }
    post("/auth/logout") { logoutRoute(call, c) }
    deviceRoutes(c)
}

private suspend fun refreshRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    if (!c.limiters.ip.tryAcquire("ip:${call.clientIp()}", c.clock())) {
        call.throttled()
    } else {
        call.withBody<RefreshDto> { dto ->
            val pair =
                dto?.takeIf { it.refreshToken.length <= MAX_TOKEN_LENGTH }?.let { c.sessions.refresh(it.refreshToken) }
            if (pair == null) call.problem(HttpStatusCode.Unauthorized, "Invalid token") else call.respond(pair)
        }
    }
}

private suspend fun logoutRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    if (!c.limiters.ip.tryAcquire("ip:${call.clientIp()}", c.clock())) {
        call.throttled()
    } else {
        call.withBody<RefreshDto> { dto ->
            dto?.refreshToken?.takeIf { it.length <= MAX_TOKEN_LENGTH }?.let(c.sessions::logout)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Ordem: limite por IP (sem corpo), corpo limitado, formato do handle, limite por IP+handle, autenticacao. */
private suspend fun loginRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    if (!c.limiters.ip.tryAcquire("ip:${call.clientIp()}", c.clock())) {
        call.throttled()
    } else {
        call.withBody<LoginDto> { dto -> attemptLogin(call, c, dto) }
    }
}

private suspend fun attemptLogin(
    call: RoutingCall,
    c: AuthComponents,
    dto: LoginDto?,
) {
    val ip = call.clientIp()
    when {
        dto == null || dto.handle.length > MAX_HANDLE_LENGTH || !c.auth.isLoginPasswordSane(dto.password) ->
            call.problem(HttpStatusCode.BadRequest, "Malformed request")
        // formato invalido nunca existe: responde igual a credencial errada, sem criar estado por chave
        !isValidHandle(dto.handle) -> call.problem(HttpStatusCode.Unauthorized, "Invalid credentials")
        // por IP+handle: outro IP nao tranca o dono; a protecao por conta e o CAPTCHA do LoginGuard
        !c.limiters.handle.tryAcquire("p:$ip|${dto.handle.lowercase()}", c.clock()) -> call.throttled()
        else -> respondLogin(call, c.auth.login(ip, dto.handle, dto.password, dto.deviceLabel, dto.captchaToken))
    }
}

private fun Route.deviceRoutes(c: AuthComponents) {
    get("/me/devices") {
        call.accountIdOrUnauthorized(c.access, c.clock)?.let { id ->
            val devices =
                c.sessions.listDevices(id).map {
                    DeviceDto(it.id.toString(), it.label, it.createdAt.toString(), it.lastUsedAt.toString())
                }
            call.respond(devices)
        }
    }
    delete("/me/devices/{deviceId}") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            val deviceId = runCatching { UUID.fromString(call.parameters["deviceId"]) }.getOrNull()
            // dispositivo alheio e inexistente respondem igual: 404, sem vazar existencia (anti-IDOR)
            if (deviceId != null && c.sessions.revokeDevice(id, deviceId)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.problem(HttpStatusCode.NotFound, "Not found")
            }
        }
    }
}
