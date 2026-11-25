package br.malhaviva.lgpd

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.AuthComponents
import br.malhaviva.auth.problem
import br.malhaviva.auth.recovery.ContactRepository
import br.malhaviva.auth.sensitiveAccountIdOrUnauthorized
import br.malhaviva.auth.throttled
import br.malhaviva.auth.withBody
import br.malhaviva.platform.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val MAX_FIELD = 64
private const val MAX_PASSWORD_INPUT = 1024

/** Tudo da Fase 5 que as rotas precisam; montado em AuthConfig (producao) ou nos testes. */
class LgpdComponents(
    val consents: ConsentService,
    val rights: DataRightsService,
    val parental: ParentalConsentService,
    val accessLogs: AccessLogRepository,
    val accounts: AccountRepository,
    val contacts: ContactRepository,
)

@Serializable
private class ConfirmParentalDto(
    val handle: String,
    val code: String,
)

@Serializable
private class ResendParentalDto(
    val handle: String,
    @SerialName("captcha_token") val captchaToken: String? = null,
)

@Serializable
private class GrantDto(
    @SerialName("policy_version") val policyVersion: String,
)

@Serializable
private class PatchMeDto(
    val handle: String? = null,
)

@Serializable
private class DeleteMeDto(
    @SerialName("current_password") val currentPassword: String,
)

@Serializable
private class AcceptedBody(
    val status: String = "accepted",
)

fun Route.lgpdRoutes(
    c: AuthComponents,
    l: LgpdComponents,
) {
    post("/auth/parental-consent/confirm") { confirmParental(call, c, l) }
    post("/auth/parental-consent/resend") { resendParental(call, c, l) }
    get("/me") { call.sensitiveAccountIdOrUnauthorized(c)?.let { respondAccount(call, l, it) } }
    get("/me/consents") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            call.respond(
                buildJsonObject {
                    put("policy_version", l.consents.policyVersion)
                    put("consents", JsonArray(l.consents.list(id).map { l.rights.consentJson(it) }))
                },
            )
        }
    }
    get("/me/consents/history") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            call.respond(
                JsonArray(
                    l.consents.history(id).map { l.rights.eventJson(it) },
                ),
            )
        }
    }
    put("/me/consents/{purpose}") { grantConsent(call, c, l) }
    delete("/me/consents/{purpose}") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            val purpose = ConsentPurpose.fromWire(call.parameters["purpose"])
            if (purpose == null) {
                call.problem(HttpStatusCode.NotFound, "Not found")
            } else {
                l.consents.revoke(id, purpose)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    get("/me/export") { exportMe(call, c, l) }
    patch("/me") { patchMe(call, c, l) }
    delete("/me") { deleteMe(call, c, l) }
}

private suspend fun confirmParental(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    if (!c.limiters.ip.tryAcquire("ip:${call.clientIp()}", c.clock())) {
        call.throttled()
    } else {
        call.withBody<ConfirmParentalDto> { dto ->
            val ok = dto != null && dto.handle.length <= MAX_FIELD && l.parental.confirm(dto.handle, dto.code)
            if (ok) {
                call.respond(
                    HttpStatusCode.NoContent,
                )
            } else {
                call.problem(HttpStatusCode.BadRequest, "Invalid code", "invalid_code")
            }
        }
    }
}

private suspend fun resendParental(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    val ip = call.clientIp()
    if (!c.limiters.ip.tryAcquire("ip:$ip", c.clock())) {
        call.throttled()
    } else {
        call.withBody<ResendParentalDto> { dto ->
            when {
                dto == null || dto.handle.length > MAX_FIELD ->
                    call.problem(
                        HttpStatusCode.BadRequest,
                        "Malformed request",
                    )
                !c.auth.captchaOk(dto.captchaToken, ip) ->
                    call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
                !c.limiters.handle.tryAcquire("parental:${dto.handle.lowercase()}", c.clock()) -> call.throttled()
                else -> {
                    l.parental.resend(dto.handle, ip)
                    call.respond(HttpStatusCode.Accepted, AcceptedBody())
                }
            }
        }
    }
}

private suspend fun grantConsent(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    val purpose = ConsentPurpose.fromWire(call.parameters["purpose"])
    if (purpose == null) {
        call.problem(HttpStatusCode.NotFound, "Not found")
    } else {
        call.withBody<GrantDto> { dto ->
            if (dto == null || dto.policyVersion.length > MAX_FIELD) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                when (val r = l.consents.grant(id, purpose, dto.policyVersion)) {
                    is GrantResult.Granted -> call.respond(l.rights.consentJson(r.state))
                    GrantResult.PolicyMismatch ->
                        call.problem(HttpStatusCode.Conflict, "Policy version mismatch", "policy_version_mismatch")
                    GrantResult.MarketingForbiddenForMinors ->
                        call.problem(HttpStatusCode.Forbidden, "Forbidden", "marketing_forbidden_for_minors")
                }
            }
        }
    }
}

private suspend fun exportMe(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    if (!c.limiters.handle.tryAcquire("export:$id", c.clock())) {
        call.throttled()
    } else {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(l.rights.export(id))
    }
}

private suspend fun patchMe(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    call.withBody<PatchMeDto> { dto ->
        val handle = dto?.handle
        if (handle == null) {
            call.problem(HttpStatusCode.BadRequest, "Malformed request")
        } else {
            when (l.rights.rename(id, handle)) {
                RenameResult.Invalid -> call.problem(HttpStatusCode.BadRequest, "Invalid handle", "invalid_handle")
                RenameResult.Taken -> call.problem(HttpStatusCode.Conflict, "Handle unavailable")
                RenameResult.Done -> respondAccount(call, l, id)
            }
        }
    }
}

private suspend fun respondAccount(
    call: RoutingCall,
    l: LgpdComponents,
    id: UUID,
) {
    val account = l.accounts.findById(id)
    val info = l.contacts.find(id)
    call.respond(
        buildJsonObject {
            put("handle", account?.handle.orEmpty())
            put(
                "contacts_verified",
                buildJsonObject {
                    put("phone", info.phoneVerified)
                    put("email", info.emailVerified)
                },
            )
            put("age_bracket", account?.ageBracket?.wire.orEmpty())
            put("status", account?.status?.wire.orEmpty())
        },
    )
}

private suspend fun deleteMe(
    call: RoutingCall,
    c: AuthComponents,
    l: LgpdComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c) ?: return
    if (!c.limiters.handle.tryAcquire("erase:$id", c.clock())) {
        call.throttled()
    } else {
        call.withBody<DeleteMeDto> { dto ->
            when {
                dto == null || dto.currentPassword.length > MAX_PASSWORD_INPUT ->
                    call.problem(HttpStatusCode.BadRequest, "Malformed request")
                !l.rights.passwordMatches(id, dto.currentPassword) ->
                    call.problem(HttpStatusCode.Forbidden, "Forbidden", "invalid_password")
                else -> {
                    l.rights.erase(id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
