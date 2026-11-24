package br.malhaviva.auth

import br.malhaviva.auth.recovery.recoveryRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
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
private const val RETRY_AFTER_LIMIT_S = "60"
private const val BEARER = "Bearer "

@Serializable
private class CreateAccountDto(
    val handle: String,
    val password: String,
    // canais de recuperacao: gravados sem verificar e verificados por OTP quando a recuperacao esta ligada
    val phone: String? = null,
    val email: String? = null,
)

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
private class ContactsVerified(
    val phone: Boolean = false,
    val email: Boolean = false,
)

@Serializable
private class AccountDto(
    val handle: String,
    @SerialName("contacts_verified") val contactsVerified: ContactsVerified = ContactsVerified(),
)

@Serializable
private class DeviceDto(
    val id: String,
    val label: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String,
)

@Serializable
private class AuthProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
)

internal suspend fun RoutingCall.problem(
    status: HttpStatusCode,
    title: String,
    detail: String? = null,
) = respond(status, AuthProblem("about:blank", title, status.value, detail))

internal suspend fun RoutingCall.throttled(retryAfterSeconds: String = RETRY_AFTER_LIMIT_S) {
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds)
    problem(HttpStatusCode.TooManyRequests, "Too many requests")
}

/** Corpo invalido vira null (400); nunca propaga excecao com trecho do corpo. */
internal suspend inline fun <reified T : Any> RoutingCall.body(): T? = runCatching { receive<T>() }.getOrNull()

internal fun RoutingCall.ip(): String = request.local.remoteAddress

/** Extrai e valida o access token. Sem token valido responde 401 e devolve null. */
suspend fun RoutingCall.accountIdOrUnauthorized(
    access: AccessTokenService,
    clock: () -> Long = System::currentTimeMillis,
): UUID? {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val token = if (header.startsWith(BEARER, ignoreCase = true)) header.substring(BEARER.length).trim() else ""
    val id = if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) null else access.verify(token, clock())
    if (id == null) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        problem(HttpStatusCode.Unauthorized, "Unauthorized")
    }
    return id
}

fun Route.authRoutes(c: AuthComponents) {
    post("/auth/accounts") {
        val dto = call.body<CreateAccountDto>()
        when {
            !c.limiters.signup.tryAcquire("ip:${call.ip()}", c.clock()) -> call.throttled()
            dto == null -> call.problem(HttpStatusCode.BadRequest, "Malformed request")
            c.recovery?.contacts?.validSignupContacts(dto.phone, dto.email) == false ->
                call.problem(HttpStatusCode.BadRequest, "Invalid contact", "invalid_contact")
            else -> registerAccount(call, c, dto)
        }
    }
    post("/auth/token") { loginRoute(call, c) }
    post("/auth/refresh") {
        val dto = call.body<RefreshDto>()
        if (!c.limiters.ip.tryAcquire("ip:${call.ip()}", c.clock())) {
            call.throttled()
        } else {
            val pair =
                dto?.takeIf { it.refreshToken.length <= MAX_TOKEN_LENGTH }?.let {
                    c.sessions.refresh(
                        it.refreshToken,
                    )
                }
            if (pair == null) call.problem(HttpStatusCode.Unauthorized, "Invalid token") else call.respond(pair)
        }
    }
    post("/auth/logout") {
        call
            .body<RefreshDto>()
            ?.refreshToken
            ?.takeIf { it.length <= MAX_TOKEN_LENGTH }
            ?.let(c.sessions::logout)
        call.respond(HttpStatusCode.NoContent)
    }
    deviceRoutes(c)
    c.recovery?.let { recoveryRoutes(c, it) }
}

private suspend fun registerAccount(
    call: RoutingCall,
    c: AuthComponents,
    dto: CreateAccountDto,
) {
    val result = c.auth.register(dto.handle, dto.password)
    if (result == RegisterResult.Created) c.recovery?.contacts?.registerContacts(dto.handle, dto.phone, dto.email)
    respondRegister(call, result, dto.handle)
}

private suspend fun respondRegister(
    call: RoutingCall,
    result: RegisterResult,
    handle: String,
) {
    when (result) {
        RegisterResult.Created -> call.respond(HttpStatusCode.Created, AccountDto(handle))
        RegisterResult.InvalidHandle -> call.problem(HttpStatusCode.BadRequest, "Invalid handle", "invalid_handle")
        RegisterResult.HandleTaken -> call.problem(HttpStatusCode.Conflict, "Handle unavailable")
        is RegisterResult.PasswordRejected ->
            call.problem(HttpStatusCode.BadRequest, "Password rejected", result.reason.code)
    }
}

private suspend fun loginRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val dto = call.body<LoginDto>()
    val valid = dto != null && dto.handle.length <= MAX_HANDLE_LENGTH && c.auth.isLoginPasswordSane(dto.password)
    when {
        !c.limiters.ip.tryAcquire("ip:${call.ip()}", c.clock()) -> call.throttled()
        dto == null || !valid -> call.problem(HttpStatusCode.BadRequest, "Malformed request")
        !c.limiters.handle.tryAcquire("h:${dto.handle.lowercase()}", c.clock()) -> call.throttled()
        else ->
            when (val r = c.auth.login(call.ip(), dto.handle, dto.password, dto.deviceLabel, dto.captchaToken)) {
                is LoginResult.Success -> call.respond(r.tokens)
                LoginResult.InvalidCredentials -> call.problem(HttpStatusCode.Unauthorized, "Invalid credentials")
                LoginResult.CaptchaRequired ->
                    call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
                is LoginResult.Throttled -> call.throttled(r.retryAfterSeconds.toString())
            }
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
        call.accountIdOrUnauthorized(c.access, c.clock)?.let { id ->
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
