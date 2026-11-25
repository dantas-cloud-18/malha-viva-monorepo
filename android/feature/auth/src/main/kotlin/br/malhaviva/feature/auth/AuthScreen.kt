package br.malhaviva.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.SecureScreen

/** Entrada minima do fluxo de conta; a ligacao na navegacao do app fica para depois. */
@Composable
fun AuthEntry(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    AuthContent(state, viewModel::act, modifier)
}

/** Login, MFA, cadastro e dispositivos mostram dados sensiveis: tudo sob FLAG_SECURE. */
@Composable
fun AuthContent(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MalhaVivaSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.m),
        ) {
            when (state.screen) {
                AuthScreen.Login -> LoginForm(state, onIntent)
                AuthScreen.Mfa -> MfaForm(state, onIntent)
                AuthScreen.Signup -> SignupForm(state, onIntent)
                AuthScreen.EnrollmentOnly -> EnrollmentNotice(onIntent)
                AuthScreen.Devices -> DevicesList(state, onIntent)
            }
            StatusLine(state)
        }
    }
}

@Composable
private fun StatusLine(state: AuthState) {
    if (state.busy) Text(stringResource(R.string.auth_loading), style = MaterialTheme.typography.bodyMedium)
    state.message?.let {
        Text(messageText(it, state.retryAfterSeconds), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun messageText(
    message: AuthMessage,
    retryAfterSeconds: Long?,
): String =
    when (message) {
        AuthMessage.RateLimited ->
            if (retryAfterSeconds != null) {
                stringResource(R.string.auth_msg_rate_limited_seconds, retryAfterSeconds)
            } else {
                stringResource(R.string.auth_msg_rate_limited)
            }
        else -> stringResource(MESSAGE_RES.getValue(message))
    }

private val MESSAGE_RES =
    mapOf(
        AuthMessage.BadCredentials to R.string.auth_msg_bad_credentials,
        AuthMessage.InvalidMfa to R.string.auth_msg_invalid_mfa,
        AuthMessage.InvalidPassword to R.string.auth_msg_invalid_password,
        AuthMessage.CaptchaRequired to R.string.auth_msg_captcha_required,
        AuthMessage.CaptchaUnavailable to R.string.auth_msg_captcha_unavailable,
        AuthMessage.AdminMfaRequired to R.string.auth_msg_admin_mfa,
        AuthMessage.TooLarge to R.string.auth_msg_too_large,
        AuthMessage.RateLimited to R.string.auth_msg_rate_limited,
        AuthMessage.HandleTaken to R.string.auth_msg_handle_taken,
        AuthMessage.InvalidInput to R.string.auth_msg_invalid_input,
        AuthMessage.Network to R.string.auth_msg_network,
        AuthMessage.Generic to R.string.auth_msg_generic,
        AuthMessage.SessionExpired to R.string.auth_msg_session_expired,
        AuthMessage.AccountCreated to R.string.auth_msg_account_created,
    )
