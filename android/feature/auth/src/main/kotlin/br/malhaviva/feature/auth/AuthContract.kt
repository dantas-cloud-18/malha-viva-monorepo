package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.Device
import br.malhaviva.feature.auth.net.LoginOutcome

enum class AuthScreen { Login, Mfa, Signup, EnrollmentOnly, Devices }

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
}

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 128
private val HANDLE_PATTERN = Regex("^@[a-zA-Z0-9_]{3,20}$")
private const val TOTP_LENGTH = 6

/** Politica NIST 800-63B: so o tamanho conta, sem regras de composicao. */
fun isPasswordValid(password: String): Boolean = password.length in PASSWORD_MIN..PASSWORD_MAX

fun normalizeHandle(raw: String): String = raw.trim().let { if (it.isEmpty() || it.startsWith("@")) it else "@$it" }

fun isHandleValid(raw: String): Boolean = HANDLE_PATTERN.matches(normalizeHandle(raw))

data class AuthState(
    val screen: AuthScreen = AuthScreen.Login,
    val handle: String = "",
    val password: String = "",
    val email: String = "",
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
    val canSubmitSignup get() = canSubmitLogin && (email.isBlank() || '@' in email)
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

    data class MfaCodeChanged(
        val value: String,
    ) : AuthIntent

    data object ToggleBackupCode : AuthIntent

    data object GoLogin : AuthIntent

    data object GoSignup : AuthIntent

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

    data object SignedUp : AuthIntent

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
        AuthError.NotFound -> AuthMessage.Generic
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
        is AuthIntent.MfaCodeChanged -> state.copy(mfaCode = intent.value.trim(), message = null)
        AuthIntent.ToggleBackupCode -> state.copy(useBackupCode = !state.useBackupCode, mfaCode = "", message = null)
        AuthIntent.GoLogin -> AuthState(handle = state.handle)
        AuthIntent.GoSignup -> AuthState(screen = AuthScreen.Signup, handle = state.handle)
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
        AuthIntent.SignedUp -> AuthState(handle = state.handle, message = AuthMessage.AccountCreated)
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
