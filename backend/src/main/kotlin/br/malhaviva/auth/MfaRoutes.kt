package br.malhaviva.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

private const val BEARER = "Bearer "
private const val MAX_CODE_INPUT = 16
private const val MAX_TOKEN_INPUT = 512
private const val MAX_PASSWORD_INPUT = 1024

@Serializable
internal class MfaChallengeDto(
    @SerialName("mfa_required") val mfaRequired: Boolean = true,
    @SerialName("mfa_token") val mfaToken: String,
    @SerialName("expires_in") val expiresIn: Long = MFA_TOKEN_TTL_SECONDS,
)

@Serializable
internal class MfaEnrollmentDto(
    @SerialName("mfa_enrollment_required") val enrollmentRequired: Boolean = true,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = ACCESS_TOKEN_TTL_SECONDS,
    val scope: String = SCOPE_MFA_ENROLL,
)

@Serializable
private class LoginMfaDto(
    @SerialName("mfa_token") val mfaToken: String,
    val code: String,
)

@Serializable
private class CodeDto(
    val code: String,
)

@Serializable
private class DisableTotpDto(
    val password: String,
    val code: String,
)

@Serializable
private class TotpEnrollmentDto(
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
)

@Serializable
private class StatusDto(
    val status: String = "ok",
)

/** Segundo passo do login, enrolamento de TOTP e a rota admin de exemplo. Codigos e segredos nunca sao logados. */
fun Route.mfaRoutes(c: AuthComponents) {
    post("/auth/login/mfa") {
        val dto = call.body<LoginMfaDto>()
        when {
            !c.limiters.ip.tryAcquire("ip:${call.ip()}", c.clock()) -> call.throttled()
            dto == null || dto.mfaToken.length > MAX_TOKEN_INPUT || dto.code.length > MAX_CODE_INPUT ->
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            else -> respondLogin(call, c.auth.loginMfa(call.ip(), dto.mfaToken, dto.code))
        }
    }
    post("/me/mfa/totp") { enrollRoute(call, c) }
    post("/me/mfa/totp/confirm") { confirmRoute(call, c) }
    post("/me/mfa/totp/disable") { disableRoute(call, c) }
    get("/admin/ping") { call.adminIdOrForbidden(c)?.let { call.respond(StatusDto()) } }
}

/**
 * Extrai e valida o access token. Sem token valido responde 401 e devolve null. O token restrito
 * `mfa_enroll_only` so passa com [allowEnrollOnly] (rotas de enrolamento de TOTP).
 */
suspend fun RoutingCall.principalOrUnauthorized(
    access: AccessTokenService,
    clock: () -> Long = System::currentTimeMillis,
    allowEnrollOnly: Boolean = false,
): AccessPrincipal? {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val token = if (header.startsWith(BEARER, ignoreCase = true)) header.substring(BEARER.length).trim() else ""
    val principal =
        if (token.isEmpty() || token.length > MAX_TOKEN_INPUT) {
            null
        } else {
            access.verifyPrincipal(token, clock())?.takeIf { it.scope == null || allowEnrollOnly }
        }
    if (principal == null) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        problem(HttpStatusCode.Unauthorized, "Unauthorized")
    }
    return principal
}

/** requireAdmin: papel admin E token com `amr` otp. 401 sem token valido, 403 caso contrario. */
suspend fun RoutingCall.adminIdOrForbidden(c: AuthComponents): UUID? {
    val principal = principalOrUnauthorized(c.access, c.clock)
    val allowed = principal != null && principal.mfa && c.auth.mfa.isAdmin(principal.accountId)
    if (principal != null && !allowed) problem(HttpStatusCode.Forbidden, "Forbidden", "admin_mfa_required")
    return principal?.accountId?.takeIf { allowed }
}

/** Resultados comuns ao login por senha e ao segundo passo. */
internal suspend fun respondLogin(
    call: RoutingCall,
    r: LoginResult,
) {
    when (r) {
        is LoginResult.Success -> call.respond(r.tokens)
        is LoginResult.MfaRequired -> call.respond(MfaChallengeDto(mfaToken = r.mfaToken))
        is LoginResult.MfaEnrollmentRequired -> call.respond(MfaEnrollmentDto(accessToken = r.accessToken))
        is LoginResult.Throttled -> call.throttled(r.retryAfterSeconds.toString())
        LoginResult.InvalidMfa -> call.problem(HttpStatusCode.Unauthorized, "Invalid code", "invalid_mfa")
        LoginResult.InvalidCredentials -> call.problem(HttpStatusCode.Unauthorized, "Invalid credentials")
        LoginResult.CaptchaRequired -> call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
    }
}

/** Limite por conta das rotas de TOTP; se estourou, ja respondeu 429. */
private suspend fun limited(
    call: RoutingCall,
    c: AuthComponents,
    accountId: String,
): Boolean {
    val over = !c.limiters.handle.tryAcquire("mfa:$accountId", c.clock())
    if (over) call.throttled()
    return over
}

private suspend fun enrollRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val p = call.principalOrUnauthorized(c.access, c.clock, allowEnrollOnly = true) ?: return
    if (!limited(call, c, p.accountId.toString())) {
        // o segredo sai so nesta resposta
        val started = c.auth.mfa.enroll(p.accountId)
        if (started == null) {
            call.problem(HttpStatusCode.Conflict, "TOTP already enabled")
        } else {
            call.respond(HttpStatusCode.Created, TotpEnrollmentDto(started.secretBase32, started.otpauthUri))
        }
    }
}

private suspend fun confirmRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val p = call.principalOrUnauthorized(c.access, c.clock, allowEnrollOnly = true) ?: return
    val dto = call.body<CodeDto>()
    when {
        limited(call, c, p.accountId.toString()) -> Unit
        dto == null || dto.code.length > MAX_CODE_INPUT -> call.problem(HttpStatusCode.BadRequest, "Malformed request")
        c.auth.mfa.confirm(p.accountId, dto.code) -> call.respond(HttpStatusCode.NoContent)
        else -> call.problem(HttpStatusCode.BadRequest, "Invalid or expired code", "invalid_or_expired_code")
    }
}

private suspend fun disableRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val id = call.accountIdOrUnauthorized(c.access, c.clock) ?: return
    val dto = call.body<DisableTotpDto>()
    when {
        limited(call, c, id.toString()) -> Unit
        dto == null || dto.code.length > MAX_CODE_INPUT || dto.password.length > MAX_PASSWORD_INPUT ->
            call.problem(HttpStatusCode.BadRequest, "Malformed request")
        else ->
            when (c.auth.mfa.disable(id, dto.password, dto.code)) {
                TotpDisableResult.DONE -> call.respond(HttpStatusCode.NoContent)
                TotpDisableResult.DENIED ->
                    call.problem(HttpStatusCode.Forbidden, "Invalid credentials", "invalid_credentials")
                TotpDisableResult.ADMIN_MANDATORY ->
                    call.problem(HttpStatusCode.Forbidden, "TOTP is mandatory for admin", "admin_mfa_mandatory")
            }
    }
}
