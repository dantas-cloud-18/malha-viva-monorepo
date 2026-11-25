package br.malhaviva.feature.auth

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import br.malhaviva.core.designsystem.CheckInButton

/** Recuperacao de conta; ao concluir, devolve o controle ao fluxo principal (login com aviso). */
@Composable
internal fun RecoveryFlow(
    viewModel: RecoveryViewModel,
    handle: String,
    onMain: (AuthIntent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    FlowEffects({ viewModel.act(RecoveryIntent.Open(handle)) }, state.done) { onMain(AuthIntent.RecoveryDone) }
    RecoveryForm(state, viewModel::act) { onMain(AuthIntent.GoLogin) }
    StatusLine(state.busy, state.message, state.retryAfterSeconds)
}

@Composable
private fun RecoveryForm(
    state: RecoveryState,
    onIntent: (RecoveryIntent) -> Unit,
    onBack: () -> Unit,
) {
    Title(stringResource(R.string.auth_recovery_title))
    Field(stringResource(R.string.auth_handle_label), state.handle, { onIntent(RecoveryIntent.HandleChanged(it)) })
    if (state.step == RecoveryStep.Request) {
        Hint(stringResource(R.string.auth_recovery_request_info))
        CheckInButton(stringResource(R.string.auth_recovery_request_action), {
            onIntent(RecoveryIntent.SubmitRequest)
        }, enabled = state.canRequest)
    } else {
        RecoveryComplete(state, onIntent)
    }
    TextButton(onBack) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun RecoveryComplete(
    state: RecoveryState,
    onIntent: (RecoveryIntent) -> Unit,
) {
    Hint(stringResource(R.string.auth_recovery_codes_hint))
    Field(
        stringResource(R.string.auth_recovery_phone_code),
        state.phoneCode,
        { onIntent(RecoveryIntent.PhoneCodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    Field(
        stringResource(R.string.auth_recovery_email_code),
        state.emailCode,
        { onIntent(RecoveryIntent.EmailCodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    Field(stringResource(R.string.auth_recovery_new_password), state.newPassword, {
        onIntent(RecoveryIntent.PasswordChanged(it))
    }, secret = true)
    Hint(stringResource(R.string.auth_password_hint))
    Hint(stringResource(R.string.auth_recovery_complete_hint))
    CheckInButton(stringResource(R.string.auth_recovery_complete_action), {
        onIntent(RecoveryIntent.SubmitComplete)
    }, enabled = state.canComplete)
}
