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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.SecureScreen

/** ViewModels dos subfluxos; cada um e reiniciado ao entrar na tela correspondente. */
class AuthFlows(
    val recovery: RecoveryViewModel,
    val totp: TotpViewModel,
    val contacts: ContactsViewModel,
    val parental: ParentalViewModel,
    val privacy: PrivacyViewModel,
)

/** Entrada do fluxo de conta; a ligacao na navegacao do app fica para depois. */
@Composable
fun AuthEntry(
    viewModel: AuthViewModel,
    flows: AuthFlows,
    modifier: Modifier = Modifier,
    onTitleLongPress: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    AuthContent(state, viewModel::act, flows, modifier, onTitleLongPress)
}

/** Login, MFA, cadastro, recuperacao, TOTP, contatos e dispositivos mostram dados sensiveis: tudo sob FLAG_SECURE. */
@Composable
fun AuthContent(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
    flows: AuthFlows,
    modifier: Modifier = Modifier,
    onTitleLongPress: (() -> Unit)? = null,
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
                AuthScreen.EnrollmentOnly -> TotpFlow(flows.totp, enrollOnly = true, onMain = onIntent)
                AuthScreen.Devices -> DevicesList(state, onIntent, onTitleLongPress)
                AuthScreen.Recovery -> RecoveryFlow(flows.recovery, state.handle, onIntent)
                AuthScreen.Totp -> TotpFlow(flows.totp, enrollOnly = false, onMain = onIntent)
                AuthScreen.Contacts -> ContactsFlow(flows.contacts, onIntent)
                AuthScreen.Parental -> ParentalFlow(flows.parental, state.handle, onIntent)
                AuthScreen.Privacy -> PrivacyFlow(flows.privacy, onIntent)
            }
            if (state.screen in MAIN_SCREENS) StatusLine(state.busy, state.message, state.retryAfterSeconds)
        }
    }
}

// Os subfluxos mostram a propria mensagem; as telas do fluxo principal usam a do AuthState.
private val MAIN_SCREENS = setOf(AuthScreen.Login, AuthScreen.Mfa, AuthScreen.Signup, AuthScreen.Devices)

/**
 * Executa [open] uma vez ao entrar e dispara [onSignal] quando [signal] vira true. O sinal so vale depois
 * do [open]: o estado do ViewModel pode trazer um sinal velho da visita anterior.
 */
@Composable
internal fun FlowEffects(
    open: () -> Unit,
    signal: Boolean,
    onSignal: () -> Unit,
) {
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        open()
        opened = true
    }
    LaunchedEffect(opened, signal) { if (opened && signal) onSignal() }
}

@Composable
internal fun StatusLine(
    busy: Boolean,
    message: AuthMessage?,
    retryAfterSeconds: Long?,
) {
    if (busy) Text(stringResource(R.string.auth_loading), style = MaterialTheme.typography.bodyMedium)
    message?.let {
        val color = if (it.isNotice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Text(messageText(it, retryAfterSeconds), color = color)
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
        AuthMessage.AccountCreatedVerify to R.string.auth_msg_account_created_verify,
        AuthMessage.InvalidCode to R.string.auth_msg_invalid_code,
        AuthMessage.InvalidCredentials to R.string.auth_msg_invalid_credentials,
        AuthMessage.AdminMfaMandatory to R.string.auth_msg_admin_mfa_mandatory,
        AuthMessage.EnrollmentCodeRequired to R.string.auth_msg_enrollment_code_required,
        AuthMessage.RecoveryRequested to R.string.auth_msg_recovery_requested,
        AuthMessage.RecoveryDone to R.string.auth_msg_recovery_done,
        AuthMessage.EnrollmentDone to R.string.auth_msg_enrollment_done,
        AuthMessage.TotpDisabled to R.string.auth_msg_totp_disabled,
        AuthMessage.CodesRegenerated to R.string.auth_msg_codes_regenerated,
        AuthMessage.ContactSent to R.string.auth_msg_contact_sent,
        AuthMessage.ContactConfirmed to R.string.auth_msg_contact_confirmed,
        AuthMessage.ContactChangePending to R.string.auth_msg_contact_change_pending,
        AuthMessage.ContactChangeCanceled to R.string.auth_msg_contact_change_canceled,
        AuthMessage.ParentalPending to R.string.auth_msg_parental_pending,
        AuthMessage.ParentalConfirmed to R.string.auth_msg_parental_confirmed,
        AuthMessage.ParentalResent to R.string.auth_msg_parental_resent,
        AuthMessage.AccountCreatedParental to R.string.auth_msg_account_created_parental,
        AuthMessage.PolicyMismatch to R.string.auth_msg_policy_mismatch,
        AuthMessage.MarketingForbidden to R.string.auth_msg_marketing_forbidden,
        AuthMessage.ConsentSaved to R.string.auth_msg_consent_saved,
        AuthMessage.HandleUpdated to R.string.auth_msg_handle_updated,
        AuthMessage.GenderUpdated to R.string.auth_msg_gender_updated,
        AuthMessage.AccountDeleted to R.string.auth_msg_account_deleted,
        AuthMessage.ExportReady to R.string.auth_msg_export_ready,
    )
