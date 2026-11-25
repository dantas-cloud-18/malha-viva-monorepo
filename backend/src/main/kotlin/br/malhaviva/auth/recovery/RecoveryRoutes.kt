package br.malhaviva.auth.recovery

import br.malhaviva.auth.AuthComponents
import br.malhaviva.auth.problem
import br.malhaviva.auth.sensitiveAccountIdOrUnauthorized
import br.malhaviva.auth.throttled
import br.malhaviva.auth.withBody
import br.malhaviva.platform.clientIp
import br.malhaviva.tracking.ingest.RateLimiter
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
private const val MAX_PASSWORD_INPUT = 1024
private const val MAX_CODE_INPUT = 16
private const val CONTACT_CAPACITY = 10.0
private const val CONTACT_REFILL_PER_S = 1.0 / 60

/** Tudo da recuperacao que as rotas precisam; montado em [RecoveryConfig] (producao) ou nos testes. */
class RecoveryComponents(
    val service: RecoveryService,
    val contacts: ContactService,
    val contactLimiter: RateLimiter = RateLimiter(CONTACT_CAPACITY, CONTACT_REFILL_PER_S),
)

@Serializable
private class RecoveryRequestDto(
    val handle: String,
    @SerialName("captcha_token") val captchaToken: String? = null,
)

@Serializable
private class RecoveryCompleteDto(
    val handle: String,
    @SerialName("phone_code") val phoneCode: String? = null,
    @SerialName("email_code") val emailCode: String? = null,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
private class SetContactDto(
    val channel: String,
    val value: String,
    @SerialName("current_password") val currentPassword: String? = null,
)

@Serializable
private class ConfirmContactDto(
    val channel: String,
    val code: String,
    @SerialName("change_id") val changeId: String? = null,
    // exigida para verificar o contato gravado (sem change_id); a troca ja provou a senha ao ser pedida
    @SerialName("current_password") val currentPassword: String? = null,
)

@Serializable
private class AcceptedDto(
    val status: String = "accepted",
)

@Serializable
private class VerificationSentDto(
    val status: String = "verification_sent",
)

@Serializable
private class ChangePendingDto(
    val status: String = "change_pending",
    @SerialName("change_id") val changeId: String,
    @SerialName("effective_at") val effectiveAt: String,
)

@Serializable
private class FlagsDto(
    val phone: Boolean,
    val email: Boolean,
)

@Serializable
private class PendingChangeDto(
    val id: String,
    val channel: String,
    @SerialName("effective_at") val effectiveAt: String,
    val confirmed: Boolean,
)

@Serializable
private class ContactsStateDto(
    @SerialName("contacts_verified") val contactsVerified: FlagsDto,
    @SerialName("pending_changes") val pendingChanges: List<PendingChangeDto>,
)

fun Route.recoveryRoutes(
    c: AuthComponents,
    r: RecoveryComponents,
) {
    post("/auth/recovery") { requestRoute(call, r) }
    post("/auth/recovery/complete") { completeRoute(call, r) }
    get("/me/contacts") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            val (info, open) = r.contacts.state(id)
            val pending =
                open.map {
                    PendingChangeDto(
                        it.id.toString(),
                        it.channel.wire,
                        it.effectiveAt.toString(),
                        it.confirmedAt != null,
                    )
                }
            call.respond(ContactsStateDto(FlagsDto(info.phoneVerified, info.emailVerified), pending))
        }
    }
    post("/me/contacts") { setContactRoute(call, c, r) }
    post("/me/contacts/confirm") { confirmContactRoute(call, c, r) }
    delete("/me/contact-changes/{changeId}") {
        call.sensitiveAccountIdOrUnauthorized(c)?.let { id ->
            val changeId = runCatching { UUID.fromString(call.parameters["changeId"]) }.getOrNull()
            // alheia e inexistente respondem igual: 404 (anti-IDOR)
            if (changeId != null && r.contacts.cancel(id, changeId)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.problem(HttpStatusCode.NotFound, "Not found")
            }
        }
    }
}

private suspend fun requestRoute(
    call: RoutingCall,
    r: RecoveryComponents,
) {
    // limite por IP antes de ler o corpo; o do par IP+handle depende do corpo e vem depois
    if (!r.service.admitRequestIp(call.clientIp())) {
        call.throttled()
    } else {
        call.withBody<RecoveryRequestDto> { dto ->
            if (dto == null || dto.handle.length > MAX_HANDLE_LENGTH) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                respondRequest(call, r.service.request(call.clientIp(), dto.handle, dto.captchaToken))
            }
        }
    }
}

private suspend fun respondRequest(
    call: RoutingCall,
    result: RecoveryRequestResult,
) {
    when (result) {
        RecoveryRequestResult.Accepted -> call.respond(HttpStatusCode.Accepted, AcceptedDto())
        RecoveryRequestResult.CaptchaRequired ->
            call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
        is RecoveryRequestResult.Throttled -> call.throttled(result.retryAfterSeconds.toString())
    }
}

private suspend fun completeRoute(
    call: RoutingCall,
    r: RecoveryComponents,
) {
    if (!r.service.admitCompleteIp(call.clientIp())) {
        call.throttled()
    } else {
        call.withBody<RecoveryCompleteDto> { dto ->
            val valid =
                dto != null &&
                    dto.handle.length <= MAX_HANDLE_LENGTH &&
                    dto.newPassword.length <= MAX_PASSWORD_INPUT &&
                    (dto.phoneCode?.length ?: 0) <= MAX_CODE_INPUT &&
                    (dto.emailCode?.length ?: 0) <= MAX_CODE_INPUT
            if (dto == null || !valid) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                val codes = RecoveryCodes(dto.phoneCode, dto.emailCode)
                respondComplete(call, r.service.complete(call.clientIp(), dto.handle, codes, dto.newPassword))
            }
        }
    }
}

private suspend fun respondComplete(
    call: RoutingCall,
    result: RecoveryCompleteResult,
) {
    when (result) {
        RecoveryCompleteResult.Done -> call.respond(HttpStatusCode.NoContent)
        RecoveryCompleteResult.InvalidCode ->
            call.problem(HttpStatusCode.BadRequest, "Invalid or expired code", "invalid_or_expired_code")
        is RecoveryCompleteResult.PasswordRejected ->
            call.problem(HttpStatusCode.BadRequest, "Password rejected", result.reason.code)
        is RecoveryCompleteResult.Throttled -> call.throttled(result.retryAfterSeconds.toString())
    }
}

private suspend fun setContactRoute(
    call: RoutingCall,
    c: AuthComponents,
    r: RecoveryComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c)
    // limite por conta (id do token, nao do corpo) antes de ler o corpo
    if (id != null && !r.contactLimiter.tryAcquire("a:$id", c.clock())) {
        call.throttled()
    } else if (id != null) {
        call.withBody<SetContactDto> { dto ->
            val channel = Channel.fromWire(dto?.channel)
            if (dto == null || channel == null || dto.value.length > MAX_PASSWORD_INPUT) {
                call.problem(HttpStatusCode.BadRequest, "Malformed request")
            } else {
                respondSet(call, r.contacts.set(id, channel, dto.value, dto.currentPassword))
            }
        }
    }
}

private suspend fun respondSet(
    call: RoutingCall,
    result: SetContactResult,
) {
    when (result) {
        SetContactResult.Invalid -> call.problem(HttpStatusCode.BadRequest, "Invalid contact", "invalid_contact")
        SetContactResult.BadPassword -> call.problem(HttpStatusCode.Forbidden, "Invalid password", "invalid_password")
        SetContactResult.VerificationSent -> call.respond(HttpStatusCode.Accepted, VerificationSentDto())
        is SetContactResult.ChangePending ->
            call.respond(
                HttpStatusCode.Accepted,
                ChangePendingDto(changeId = result.changeId.toString(), effectiveAt = result.effectiveAt.toString()),
            )
    }
}

private suspend fun confirmContactRoute(
    call: RoutingCall,
    c: AuthComponents,
    r: RecoveryComponents,
) {
    val id = call.sensitiveAccountIdOrUnauthorized(c)
    if (id != null && !r.contactLimiter.tryAcquire("a:$id", c.clock())) {
        call.throttled()
    } else if (id != null) {
        call.withBody<ConfirmContactDto> { dto -> respondConfirm(call, r, id, dto) }
    }
}

private suspend fun respondConfirm(
    call: RoutingCall,
    r: RecoveryComponents,
    id: UUID,
    dto: ConfirmContactDto?,
) {
    val channel = Channel.fromWire(dto?.channel)
    val changeId = dto?.changeId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val badChangeId = dto?.changeId != null && changeId == null
    when {
        dto == null ||
            channel == null ||
            badChangeId ||
            dto.code.length > MAX_CODE_INPUT ||
            (dto.currentPassword?.length ?: 0) > MAX_PASSWORD_INPUT ->
            call.problem(HttpStatusCode.BadRequest, "Malformed request")
        // verificar o contato gravado exige a senha atual (so a troca ja a provou ao ser pedida)
        changeId == null && !r.contacts.verifyPassword(id, dto.currentPassword) ->
            call.problem(HttpStatusCode.Forbidden, "Invalid password", "invalid_password")
        r.contacts.confirm(id, channel, dto.code, changeId) -> call.respond(HttpStatusCode.NoContent)
        else -> call.problem(HttpStatusCode.BadRequest, "Invalid or expired code", "invalid_or_expired_code")
    }
}
