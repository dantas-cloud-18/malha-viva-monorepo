package br.malhaviva.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.RoutingCall
import java.util.UUID

private const val BEARER = "Bearer "
private const val MAX_BEARER_LENGTH = 512

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
        if (token.isEmpty() || token.length > MAX_BEARER_LENGTH) {
            null
        } else {
            access.verifyPrincipal(token, clock())?.takeIf { it.scope == null || allowEnrollOnly }
        }
    if (principal == null) unauthorized()
    return principal
}

private suspend fun RoutingCall.unauthorized() {
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    problem(HttpStatusCode.Unauthorized, "Unauthorized")
}

/**
 * Rota sensivel (contatos, MFA, admin, revogar dispositivo): alem do token valido, consulta o banco e recusa (401)
 * o access de uma sessao (familia de refresh) ja revogada, sem esperar os 15 min do token. O token restrito de
 * enrolamento nao tem sessao e passa so quando [allowEnrollOnly].
 */
suspend fun RoutingCall.sensitivePrincipalOrUnauthorized(
    c: AuthComponents,
    allowEnrollOnly: Boolean = false,
): AccessPrincipal? {
    val principal = principalOrUnauthorized(c.access, c.clock, allowEnrollOnly)
    val sid = principal?.sessionId
    val live = principal == null || principal.scope != null || (sid != null && c.sessions.isActive(sid))
    if (!live) unauthorized()
    return principal?.takeIf { live }
}

suspend fun RoutingCall.sensitiveAccountIdOrUnauthorized(c: AuthComponents): UUID? =
    sensitivePrincipalOrUnauthorized(c)?.accountId

/** requireAdmin: papel admin E token com `amr` otp E sessao viva. 401 sem token valido, 403 caso contrario. */
suspend fun RoutingCall.adminIdOrForbidden(c: AuthComponents): UUID? {
    val principal = sensitivePrincipalOrUnauthorized(c)
    val allowed = principal != null && principal.mfa && c.auth.mfa.isAdmin(principal.accountId)
    if (principal != null && !allowed) problem(HttpStatusCode.Forbidden, "Forbidden", "admin_mfa_required")
    return principal?.accountId?.takeIf { allowed }
}
