package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.Device
import br.malhaviva.feature.auth.net.LoginOutcome
import java.time.LocalDate

enum class AuthScreen { Login, Mfa, Signup, EnrollmentOnly, Devices, Recovery, Totp, Contacts, Parental, Privacy }

/** Mensagens da tela; o texto em pt-BR vem de recursos. */
enum class AuthMessage {
    BadCredentials,
    InvalidMfa,
    InvalidPassword,
    CaptchaRequired,
    CaptchaUnavailable,
    AdminMfaRequired,
    TooLarge,
    RateLimited,
    HandleTaken,
    InvalidInput,
    Network,
    Generic,
    SessionExpired,
    AccountCreated,
    AccountCreatedVerify,
    InvalidCode,
    InvalidCredentials,
    AdminMfaMandatory,
    EnrollmentCodeRequired,
    RecoveryRequested,
    RecoveryDone,
    EnrollmentDone,
    TotpDisabled,
    CodesRegenerated,
    ContactSent,
    ContactConfirmed,
    ContactChangePending,
    ContactChangeCanceled,
    ParentalPending,
    ParentalConfirmed,
    ParentalResent,
    AccountCreatedParental,
    PolicyMismatch,
    MarketingForbidden,
    ConsentSaved,
    HandleUpdated,
    GenderUpdated,
    AccountDeleted,
    ExportReady,
}

private val NOTICES =
    setOf(
        AuthMessage.AccountCreated,
        AuthMessage.AccountCreatedVerify,
        AuthMessage.RecoveryRequested,
        AuthMessage.RecoveryDone,
        AuthMessage.EnrollmentDone,
        AuthMessage.TotpDisabled,
        AuthMessage.CodesRegenerated,
        AuthMessage.ContactSent,
        AuthMessage.ContactConfirmed,
        AuthMessage.ContactChangePending,
        AuthMessage.ContactChangeCanceled,
        AuthMessage.ParentalConfirmed,
        AuthMessage.ParentalResent,
        AuthMessage.AccountCreatedParental,
        AuthMessage.ConsentSaved,
        AuthMessage.HandleUpdated,
        AuthMessage.GenderUpdated,
        AuthMessage.AccountDeleted,
        AuthMessage.ExportReady,
    )

/** Avisos de sucesso nao usam a cor de erro. */
val AuthMessage.isNotice: Boolean get() = this in NOTICES

data class AuthState(
    val screen: AuthScreen = AuthScreen.Login,
    val handle: String = "",
    val password: String = "",
    val email: String = "",
    val phone: String = DEFAULT_PHONE,
    /** Nascimento digitado como dd/mm/aaaa; so a faixa etaria sai do aparelho para o servidor. */
    val birthDate: String = "",
    /** Opcional: null = "Nao informar" (nada e enviado ao servidor). */
    val gender: Gender? = null,
    val guardianChannel: ContactChannel = ContactChannel.Phone,
    val guardian: String = DEFAULT_PHONE,
    val today: LocalDate = LocalDate.now(),
    val mfaToken: String? = null,
    val mfaCode: String = "",
    val useBackupCode: Boolean = false,
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    /** Preenchido junto de [AuthMessage.RateLimited] quando o servidor mandou Retry-After. */
    val retryAfterSeconds: Long? = null,
    val devices: List<Device> = emptyList(),
) {
    val canSubmitLogin get() = !busy && isHandleValid(handle) && isPasswordValid(password)
    val birth: LocalDate? get() = parseBirthDate(birthDate, today)

    /** Menor de 12 anos: exige o contato do responsavel. Data invalida conta como "sem informacao". */
    val needsGuardian: Boolean get() = birth?.let { ageOn(it, today) < GUARDIAN_AGE } == true
    val guardianValid: Boolean
        get() =
            when (guardianChannel) {
                ContactChannel.Phone -> isPhoneValid(guardian) && !isPhoneBlank(guardian)
                ContactChannel.Email -> isEmailValid(guardian)
            }
    val canSubmitSignup
        get() =
            canSubmitLogin &&
                birth != null &&
                (!needsGuardian || guardianValid) &&
                (email.isBlank() || '@' in email) &&
                (isPhoneBlank(phone) || isPhoneValid(phone))
    val canSubmitMfa
        get() = !busy && mfaCode.isNotBlank() && (useBackupCode || mfaCode.length == TOTP_LENGTH)
}

sealed interface AuthIntent {
    data class HandleChanged(
        val value: String,
    ) : AuthIntent

    data class PasswordChanged(
        val value: String,
    ) : AuthIntent

    data class EmailChanged(
        val value: String,
    ) : AuthIntent

    data class PhoneChanged(
        val value: String,
    ) : AuthIntent

    data class BirthDateChanged(
        val value: String,
    ) : AuthIntent

    data class GenderChanged(
        val value: Gender?,
    ) : AuthIntent

    data class GuardianChannelChanged(
        val value: ContactChannel,
    ) : AuthIntent

    data class GuardianChanged(
        val value: String,
    ) : AuthIntent

    data class MfaCodeChanged(
        val value: String,
    ) : AuthIntent

    data object ToggleBackupCode : AuthIntent

    data object GoLogin : AuthIntent

    data object GoSignup : AuthIntent

    data object GoRecovery : AuthIntent

    data object GoTotp : AuthIntent

    data object GoContacts : AuthIntent

    data object GoDevices : AuthIntent

    data object GoParental : AuthIntent

    data object GoPrivacy : AuthIntent

    // Acoes que disparam rede: o reducer so liga `busy`, o ViewModel executa.
    data object SubmitLogin : AuthIntent

    data object SubmitMfa : AuthIntent

    data object SubmitSignup : AuthIntent

    data object LoadDevices : AuthIntent

    data class RevokeDevice(
        val id: String,
    ) : AuthIntent

    data object Logout : AuthIntent

    // Resultados.
    data class LoginSucceeded(
        val outcome: LoginOutcome,
    ) : AuthIntent

    data object MfaSucceeded : AuthIntent

    data class SignedUp(
        val withContact: Boolean,
        val parentalPending: Boolean = false,
    ) : AuthIntent

    /** Responsavel confirmou o codigo: a conta ja pode entrar. */
    data object ParentalDone : AuthIntent

    /** Conta excluida no servidor: volta ao login limpo, com aviso. */
    data object AccountDeleted : AuthIntent

    /** Recuperacao concluida: todas as sessoes foram encerradas no servidor. */
    data object RecoveryDone : AuthIntent

    /** TOTP ativado numa sessao restrita: e preciso entrar de novo. */
    data object EnrollmentFinished : AuthIntent

    data class DevicesLoaded(
        val devices: List<Device>,
    ) : AuthIntent

    data class DeviceRevoked(
        val id: String,
    ) : AuthIntent

    data class Failed(
        val error: AuthError,
    ) : AuthIntent

    data object SessionLost : AuthIntent

    data object SessionRestored : AuthIntent
}

fun AuthError.toMessage(): AuthMessage =
    when (this) {
        AuthError.Unauthorized -> AuthMessage.BadCredentials
        AuthError.InvalidMfa -> AuthMessage.InvalidMfa
        AuthError.InvalidPassword -> AuthMessage.InvalidPassword
        AuthError.CaptchaRequired -> AuthMessage.CaptchaRequired
        AuthError.CaptchaUnavailable -> AuthMessage.CaptchaUnavailable
        AuthError.AdminMfaRequired -> AuthMessage.AdminMfaRequired
        AuthError.PayloadTooLarge -> AuthMessage.TooLarge
        is AuthError.RateLimited -> AuthMessage.RateLimited
        AuthError.Conflict -> AuthMessage.HandleTaken
        AuthError.Network -> AuthMessage.Network
        is AuthError.Rejected -> if (status == STATUS_BAD_REQUEST) AuthMessage.InvalidInput else AuthMessage.Generic
        else -> toCodeMessage()
    }

/** Erros que o servidor identifica pelo campo `detail` (OTP, TOTP e enrolamento). */
private fun AuthError.toCodeMessage(): AuthMessage =
    when (this) {
        AuthError.InvalidCode -> AuthMessage.InvalidCode
        AuthError.ParentalConsentPending -> AuthMessage.ParentalPending
        AuthError.PolicyMismatch -> AuthMessage.PolicyMismatch
        AuthError.MarketingForbiddenForMinors -> AuthMessage.MarketingForbidden
        AuthError.InvalidCredentials -> AuthMessage.InvalidCredentials
        AuthError.AdminMfaMandatory -> AuthMessage.AdminMfaMandatory
        AuthError.EnrollmentCodeRequired -> AuthMessage.EnrollmentCodeRequired
        else -> AuthMessage.Generic
    }

private const val STATUS_BAD_REQUEST = 400

/** Reducer puro. Segredos (senha, codigo, tokens) saem do estado assim que deixam de ser necessarios. */
fun reduceAuth(
    state: AuthState,
    intent: AuthIntent,
): AuthState =
    when (intent) {
        AuthIntent.SubmitLogin -> if (state.canSubmitLogin) state.copy(busy = true, message = null) else state
        AuthIntent.SubmitSignup -> if (state.canSubmitSignup) state.copy(busy = true, message = null) else state
        AuthIntent.SubmitMfa -> if (state.canSubmitMfa) state.copy(busy = true, message = null) else state
        AuthIntent.LoadDevices -> state.copy(busy = true, message = null)
        is AuthIntent.RevokeDevice -> state.copy(busy = true, message = null)
        else -> reduceEdit(state, intent)
    }

private fun reduceEdit(
    state: AuthState,
    intent: AuthIntent,
): AuthState =
    when (intent) {
        is AuthIntent.HandleChanged -> state.copy(handle = intent.value, message = null)
        is AuthIntent.PasswordChanged -> state.copy(password = intent.value, message = null)
        is AuthIntent.EmailChanged -> state.copy(email = intent.value, message = null)
        is AuthIntent.PhoneChanged -> state.copy(phone = intent.value, message = null)
        is AuthIntent.BirthDateChanged -> state.copy(birthDate = intent.value.trim(), message = null)
        is AuthIntent.GenderChanged -> state.copy(gender = intent.value, message = null)
        is AuthIntent.GuardianChannelChanged ->
            state.copy(
                guardianChannel = intent.value,
                guardian = if (intent.value == ContactChannel.Phone) DEFAULT_PHONE else "",
                message = null,
            )
        is AuthIntent.GuardianChanged -> state.copy(guardian = intent.value.trim(), message = null)
        is AuthIntent.MfaCodeChanged -> state.copy(mfaCode = intent.value.trim(), message = null)
        AuthIntent.ToggleBackupCode -> state.copy(useBackupCode = !state.useBackupCode, mfaCode = "", message = null)
        else -> reduceNav(state, intent)
    }

private fun reduceNav(
    state: AuthState,
    intent: AuthIntent,
): AuthState =
    when (intent) {
        AuthIntent.GoLogin -> AuthState(handle = state.handle)
        AuthIntent.GoSignup -> AuthState(screen = AuthScreen.Signup, handle = state.handle)
        AuthIntent.GoRecovery -> AuthState(screen = AuthScreen.Recovery, handle = state.handle)
        AuthIntent.GoTotp -> state.copy(screen = AuthScreen.Totp, busy = false, message = null)
        AuthIntent.GoContacts -> state.copy(screen = AuthScreen.Contacts, busy = false, message = null)
        AuthIntent.GoDevices -> state.copy(screen = AuthScreen.Devices, busy = false, message = null)
        AuthIntent.GoPrivacy -> state.copy(screen = AuthScreen.Privacy, busy = false, message = null)
        AuthIntent.GoParental -> AuthState(screen = AuthScreen.Parental, handle = state.handle)
        AuthIntent.ParentalDone -> AuthState(handle = state.handle, message = AuthMessage.ParentalConfirmed)
        AuthIntent.AccountDeleted -> AuthState(message = AuthMessage.AccountDeleted)
        AuthIntent.RecoveryDone -> AuthState(handle = state.handle, message = AuthMessage.RecoveryDone)
        AuthIntent.EnrollmentFinished -> AuthState(handle = state.handle, message = AuthMessage.EnrollmentDone)
        else -> reduceResult(state, intent)
    }

private fun reduceResult(
    state: AuthState,
    intent: AuthIntent,
): AuthState =
    when (intent) {
        is AuthIntent.LoginSucceeded -> reduceLogin(state, intent.outcome)
        AuthIntent.MfaSucceeded,
        AuthIntent.SessionRestored,
        -> AuthState(screen = AuthScreen.Devices, handle = state.handle)
        is AuthIntent.SignedUp ->
            AuthState(
                handle = state.handle,
                message = signedUpMessage(intent),
            )
        is AuthIntent.DevicesLoaded -> state.copy(busy = false, devices = intent.devices)
        is AuthIntent.DeviceRevoked ->
            state.copy(busy = false, devices = state.devices.filterNot { it.id == intent.id })
        is AuthIntent.Failed ->
            if (intent.error == AuthError.ParentalConsentPending) {
                // Senha certa, conta ainda sem autorizacao do responsavel: leva direto a tela do codigo.
                AuthState(screen = AuthScreen.Parental, handle = state.handle, message = AuthMessage.ParentalPending)
            } else {
                reduceFailure(state, intent.error)
            }
        AuthIntent.SessionLost,
        AuthIntent.Logout,
        ->
            AuthState(
                handle = state.handle,
                message =
                    if (intent ==
                        AuthIntent.SessionLost
                    ) {
                        AuthMessage.SessionExpired
                    } else {
                        null
                    },
            )
        else -> state
    }

private fun reduceFailure(
    state: AuthState,
    error: AuthError,
) = state.copy(
    busy = false,
    message = error.toMessage(),
    retryAfterSeconds = (error as? AuthError.RateLimited)?.retryAfterSeconds,
)

private fun signedUpMessage(intent: AuthIntent.SignedUp) =
    when {
        intent.parentalPending -> AuthMessage.AccountCreatedParental
        intent.withContact -> AuthMessage.AccountCreatedVerify
        else -> AuthMessage.AccountCreated
    }

private fun reduceLogin(
    state: AuthState,
    outcome: LoginOutcome,
): AuthState =
    when (outcome) {
        is LoginOutcome.Success -> AuthState(screen = AuthScreen.Devices, handle = state.handle)
        is LoginOutcome.MfaRequired ->
            AuthState(screen = AuthScreen.Mfa, handle = state.handle, mfaToken = outcome.mfaToken)
        is LoginOutcome.EnrollmentRequired -> AuthState(screen = AuthScreen.EnrollmentOnly, handle = state.handle)
    }
