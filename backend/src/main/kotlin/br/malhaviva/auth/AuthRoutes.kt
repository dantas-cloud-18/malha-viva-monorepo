package br.malhaviva.auth

import br.malhaviva.auth.recovery.recoveryRoutes
import br.malhaviva.lgpd.GuardianDto
import br.malhaviva.lgpd.SignupAge
import br.malhaviva.lgpd.evaluateSignupAge
import br.malhaviva.lgpd.lgpdRoutes
import br.malhaviva.platform.DEFAULT_MAX_BODY_BYTES
import br.malhaviva.platform.clientIp
import br.malhaviva.platform.readBoundedOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val RETRY_AFTER_LIMIT_S = "60"

// campos do contrato de cadastro; agrupar quebraria o JSON
@Suppress("LongParameterList")
@Serializable
private class CreateAccountDto(
    val handle: String,
    val password: String,
    // canais de recuperacao: gravados sem verificar e verificados por OTP quando a recuperacao esta ligada
    val phone: String? = null,
    val email: String? = null,
    // exigido quando ha telefone ou e-mail: o cadastro envia SMS/e-mail de verificacao
    @SerialName("captcha_token") val captchaToken: String? = null,
    // autodeclarada (LGPD art. 14): so a faixa etaria e guardada, nunca a data
    @SerialName("birth_date") val birthDate: String? = null,
    val guardian: GuardianDto? = null,
    // opcional: so estatistica agregada anonima; ausente = nao informado
    val gender: String? = null,
)

@Serializable
private class ContactsVerified(
    val phone: Boolean = false,
    val email: Boolean = false,
)

@Serializable
private class AccountDto(
    val handle: String,
    @SerialName("contacts_verified") val contactsVerified: ContactsVerified,
    @SerialName("age_bracket") val ageBracket: String,
    val status: String,
    val gender: String? = null,
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

/**
 * Le o corpo JSON com teto de [DEFAULT_MAX_BODY_BYTES] (le no maximo teto + 1 byte do canal). Maior que o teto
 * responde 413; JSON invalido chega ao [block] como null (400); nunca propaga excecao com trecho do corpo.
 * As rotas consultam os limitadores que nao dependem do corpo ANTES de chamar isto.
 */
internal suspend inline fun <reified T : Any> RoutingCall.withBody(block: (T?) -> Unit) {
    val bytes = readBoundedOrNull()
    if (bytes == null) {
        problem(HttpStatusCode.PayloadTooLarge, "Payload too large")
    } else {
        block(runCatching { DefaultJson.decodeFromString<T>(bytes.decodeToString()) }.getOrNull())
    }
}

/** Token completo valido (nunca o restrito `mfa_enroll_only`); senao 401 e null. */
suspend fun RoutingCall.accountIdOrUnauthorized(
    access: AccessTokenService,
    clock: () -> Long = System::currentTimeMillis,
): UUID? = principalOrUnauthorized(access, clock)?.accountId

fun Route.authRoutes(c: AuthComponents) {
    post("/auth/accounts") { signupRoute(call, c) }
    sessionRoutes(c)
    mfaRoutes(c)
    c.recovery?.let { recoveryRoutes(c, it) }
    c.lgpd?.let { lgpdRoutes(c, it) }
}

private suspend fun signupRoute(
    call: RoutingCall,
    c: AuthComponents,
) {
    if (!c.limiters.signup.tryAcquire("ip:${call.clientIp()}", c.clock())) {
        call.throttled()
    } else {
        call.withBody<CreateAccountDto> { dto ->
            if (c.genderEnabled && dto?.gender != null && Gender.fromWire(dto.gender) == null) {
                call.problem(HttpStatusCode.BadRequest, "Invalid gender", "invalid_gender")
            } else {
                respondSignup(call, c, dto)
            }
        }
    }
}

private suspend fun respondSignup(
    call: RoutingCall,
    c: AuthComponents,
    dto: CreateAccountDto?,
) {
    val contacts = c.recovery?.contacts
    val sendsMessages = dto != null && (dto.phone != null || dto.email != null)
    val age =
        if (dto == null || c.lgpd == null) {
            null
        } else {
            evaluateSignupAge(
                dto.birthDate,
                dto.guardian,
                listOf(dto.phone, dto.email),
                LocalDate.ofInstant(java.time.Instant.ofEpochMilli(c.clock()), ZoneOffset.UTC),
            )
        }
    val guardianSends = age is SignupAge.Ok && age.guardian != null
    when {
        dto == null -> call.problem(HttpStatusCode.BadRequest, "Malformed request")
        age is SignupAge.Rejected -> call.problem(HttpStatusCode.BadRequest, "Invalid age declaration", age.code)
        contacts?.validSignupContacts(dto.phone, dto.email) == false ->
            call.problem(HttpStatusCode.BadRequest, "Invalid contact", "invalid_contact")
        // o cadastro com contato (ou com responsavel) dispara SMS/e-mail: CAPTCHA antes de gastar a cota de envio
        ((contacts != null && sendsMessages) || guardianSends) &&
            !c.auth.captchaOk(
                dto.captchaToken,
                call.clientIp(),
            ) ->
            call.problem(HttpStatusCode.Forbidden, "Captcha required", "captcha_required")
        else -> registerAccount(call, c, dto, age as? SignupAge.Ok)
    }
}

private suspend fun registerAccount(
    call: RoutingCall,
    c: AuthComponents,
    dto: CreateAccountDto,
    age: SignupAge.Ok?,
) {
    val bracket = age?.bracket ?: AgeBracket.ADULT
    // stand-by: com a flag desligada o campo e ignorado
    val gender = if (c.genderEnabled) Gender.fromWire(dto.gender) else null
    val result = c.auth.register(dto.handle, dto.password, bracket, gender)
    if (result == RegisterResult.Created) afterSignup(c, dto, age, call.clientIp())
    respondRegister(call, result, dto.handle, bracket, gender)
}

/** Contatos do cadastro, registro de acesso e, para menor de 12, o OTP ao responsavel. */
private suspend fun afterSignup(
    c: AuthComponents,
    dto: CreateAccountDto,
    age: SignupAge.Ok?,
    ip: String,
) {
    c.recovery?.contacts?.registerContacts(dto.handle, dto.phone, dto.email, ip)
    val l = c.lgpd ?: return
    val id = l.accounts.findByHandle(dto.handle)?.id ?: return
    runCatching { l.accessLogs.record(id, "signup", ip) }
    age?.guardian?.let { l.parental.start(id, it, ip) }
}

private suspend fun respondRegister(
    call: RoutingCall,
    result: RegisterResult,
    handle: String,
    bracket: AgeBracket,
    gender: Gender?,
) {
    when (result) {
        RegisterResult.Created -> {
            val minor = bracket == AgeBracket.UNDER_12
            val status = if (minor) AccountStatus.PENDING_PARENTAL_CONSENT else AccountStatus.ACTIVE
            call.respond(
                HttpStatusCode.Created,
                AccountDto(handle, ContactsVerified(), bracket.wire, status.wire, gender?.wire),
            )
        }
        RegisterResult.InvalidHandle -> call.problem(HttpStatusCode.BadRequest, "Invalid handle", "invalid_handle")
        RegisterResult.HandleTaken -> call.problem(HttpStatusCode.Conflict, "Handle unavailable")
        is RegisterResult.PasswordRejected ->
            call.problem(HttpStatusCode.BadRequest, "Password rejected", result.reason.code)
    }
}
