package br.malhaviva.auth

import br.malhaviva.platform.clientIp
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

private const val MAX_CODE_INPUT = 16
private const val MAX_TOKEN_INPUT = 512
private const val MAX_PASSWORD_INPUT = 1024
private const val MAX_ENROLLMENT_CODE_INPUT = 128

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
    // exatamente um dos dois: o TOTP ou um codigo de recuperacao
    val code: String? = null,
    @SerialName("backup_code") val backupCode: String? = null,
)

@Serializable
private class EnrollTotpDto(
    // so o primeiro enrolamento de admin exige; vem fora de banda (docs/THREAT-MODEL.md)
    @SerialName("enrollment_code") val enrollmentCode: String? = null,
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
private class RegenerateBackupDto(
    val password: String,
    val code: String? = null,
    @SerialName("backup_code") val backupCode: String? = null,
)

@Serializable
private class BackupCodesDto(
    @SerialName("backup_codes") val backupCodes: List<String>,
)

@Serializable
private class StatusDto(
    val status: String = "ok",
)

/** Segundo passo do login, enrolamento de TOTP e a rota admin de exemplo. Codigos e segredos nunca sao logados. */
fun Route.mfaRoutes(c: AuthComponents) {
    post("/auth/login/mfa") {
        if (!c.limiters.ip.tryAcquire("ip:${call.clientIp()}", c.clock())) {
            call.throttled()
        } else {
            call.withBody<LoginMfaDto> { dto ->
                val factor = dto?.let { factorOf(it.code, it.backupCode) }
                if (dto == null || factor == null || dto.mfaToken.length > MAX_TOKEN_INPUT) {
                    call.problem(HttpStatusCode.BadRequest, "Malformed request")
                } else {
                    respondLogin(call, c.auth.loginMfa(call.clientIp(), dto.mfaToken, factor.second, factor.first))
                }
            }
        }
    }
    post("/me/mfa/totp") { enrollRoute(call, c) }
    post("/me/mfa/totp/confirm") { confirmRoute(call, c) }
    post("/me/mfa/totp/disable") { disableRoute(call, c) }
    post("/me/mfa/totp/backup-codes") { regenerateRoute(call, c) }
    get("/admin/ping") { call.adminIdOrForbidden(c)?.let { call.respond(StatusDto()) } }
}

/** O fator e o valor, se veio exatamente um dos dois e dentro do limite de tamanho; senao null. */
private fun factorOf(
    code: String?,
    backupCode: String?,
): Pair<MfaFactor, String>? =
    when {
        code != null && backupCode == null -> MfaFactor.TOTP to code
        code == null && backupCode != null -> MfaFactor.BACKUP to backupCode
        else -> null
    }?.takeIf { it.second.length <= MAX_CODE_INPUT }

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
        LoginResult.ParentalConsentPending ->
            call.problem(HttpStatusCode.Forbidden, "Parental consent pending", "parental_consent_pending")
        LoginResult.InvalidMfa -> call.problem(HttpStatusCode.Unauthorized, "Invalid code", "invalid_mfa")
        LoginResult.InvalidCredentials -> call.problem(HttpStatusCode.Unauthorized, "Invalid credentials")
        LoginResult.CaptchaRequired -> call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
    }
}

/** Limite por conta e trava de TOTP das rotas de TOTP; se estourou, ja respondeu 429. */
private suspend fun limited(
    call: RoutingCall,
    c: AuthComponents,
    accountId: UUID,
): Boolean {
    val locked = c.auth.mfa.totpLockedSeconds(accountId)
    val over = !c.limiters.handle.tryAcquire("mfa:$accountId", c.clock())
    if (locked > 0) {
        call.throttled(locked.toString())
    } else if (over) {
        call.throttled()
    }
    return over || locked > 0
}

private suspend fun enrollRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val p = call.sensitivePrincipalOrUnauthorized(c, allowEnrollOnly = true) ?: return
    if (!limited(call, c, p.accountId)) {
        call.withBody<EnrollTotpDto> { dto ->
            val code = dto?.enrollmentCode?.takeIf { it.length <= MAX_ENROLLMENT_CODE_INPUT }
            when (val started = c.auth.mfa.enroll(p.accountId, code)) {
                // o segredo sai so nesta resposta
                is TotpEnrollResult.Started ->
                    call.respond(
                        HttpStatusCode.Created,
                        TotpEnrollmentDto(started.enrollment.secretBase32, started.enrollment.otpauthUri),
                    )
                TotpEnrollResult.AlreadyEnabled -> call.problem(HttpStatusCode.Conflict, "TOTP already enabled")
                TotpEnrollResult.CodeRequired ->
                    call.problem(HttpStatusCode.Forbidden, "Enrollment code required", "enrollment_code_required")
            }
        }
    }
}

private suspend fun confirmRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val p = call.sensitivePrincipalOrUnauthorized(c, allowEnrollOnly = true) ?: return
    if (!limited(call, c, p.accountId)) {
        call.withBody<CodeDto> { dto ->
            when {
                dto == null || dto.code.length > MAX_CODE_INPUT ->
                    call.problem(HttpStatusCode.BadRequest, "Malformed request")
                else ->
                    // os codigos de recuperacao saem so nesta resposta
                    c.auth.mfa
                        .confirm(p.accountId, dto.code)
                        ?.let { call.respond(BackupCodesDto(it)) }
                        ?: call.problem(HttpStatusCode.BadRequest, "Invalid or expired code", "invalid_or_expired_code")
            }
        }
    }
}

private suspend fun disableRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    if (!limited(call, c, id)) {
        call.withBody<DisableTotpDto> { dto ->
            if (dto == null || dto.code.length > MAX_CODE_INPUT || dto.password.length > MAX_PASSWORD_INPUT) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                when (c.auth.mfa.disable(id, dto.password, dto.code)) {
                    TotpDisableResult.DONE -> call.respond(HttpStatusCode.NoContent)
                    TotpDisableResult.DENIED ->
                        call.problem(HttpStatusCode.Forbidden, "Invalid credentials", "invalid_credentials")
                    TotpDisableResult.ADMIN_MANDATORY ->
                        call.problem(HttpStatusCode.Forbidden, "TOTP is mandatory for admin", "admin_mfa_mandatory")
                }
            }
        }
    }
}

private suspend fun regenerateRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    if (!limited(call, c, id)) {
        call.withBody<RegenerateBackupDto> { dto ->
            val factor = dto?.let { factorOf(it.code, it.backupCode) }
            if (dto == null || factor == null || dto.password.length > MAX_PASSWORD_INPUT) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                when (val r = c.auth.mfa.regenerateBackupCodes(id, dto.password, factor.second, factor.first)) {
                    is BackupRegenerateResult.Done -> call.respond(BackupCodesDto(r.codes))
                    BackupRegenerateResult.Denied ->
                        call.problem(HttpStatusCode.Forbidden, "Invalid credentials", "invalid_credentials")
                }
            }
        }
    }
}
