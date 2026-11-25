package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.Device
import br.malhaviva.feature.auth.net.LoginOutcome

enum class AuthScreen { Login, Mfa, Signup, EnrollmentOnly, Devices, Recovery, Totp, Contacts }

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
    )

/** Avisos de sucesso nao usam a cor de erro. */
val AuthMessage.isNotice: Boolean get() = this in NOTICES

data class AuthState(
    val screen: AuthScreen = AuthScreen.Login,
    val handle: String = "",
    val password: String = "",
    val email: String = "",
    val phone: String = DEFAULT_PHONE,
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
    val canSubmitSignup
        get() = canSubmitLogin && (email.isBlank() || '@' in email) && (isPhoneBlank(phone) || isPhoneValid(phone))
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
    ) : AuthIntent

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
                message = if (intent.withContact) AuthMessage.AccountCreatedVerify else AuthMessage.AccountCreated,
            )
        is AuthIntent.DevicesLoaded -> state.copy(busy = false, devices = intent.devices)
        is AuthIntent.DeviceRevoked ->
            state.copy(busy = false, devices = state.devices.filterNot { it.id == intent.id })
        is AuthIntent.Failed ->
            state.copy(
                busy = false,
                message = intent.error.toMessage(),
                retryAfterSeconds = (intent.error as? AuthError.RateLimited)?.retryAfterSeconds,
            )
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
