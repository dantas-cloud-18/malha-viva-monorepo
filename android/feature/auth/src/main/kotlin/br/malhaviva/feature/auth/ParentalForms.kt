package br.malhaviva.feature.auth

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import br.malhaviva.core.designsystem.CheckInButton

/** Codigo do responsavel; ao confirmar, devolve o controle ao fluxo principal (login com aviso). */
@Composable
internal fun ParentalFlow(
    viewModel: ParentalViewModel,
    handle: String,
    onMain: (AuthIntent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    FlowEffects({ viewModel.act(ParentalIntent.Open(handle)) }, state.done) { onMain(AuthIntent.ParentalDone) }
    Title(stringResource(R.string.auth_parental_title))
    Hint(stringResource(R.string.auth_parental_info))
    Field(stringResource(R.string.auth_handle_label), state.handle, { viewModel.act(ParentalIntent.HandleChanged(it)) })
    Field(
        stringResource(R.string.auth_parental_code_label),
        state.code,
        { viewModel.act(ParentalIntent.CodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    CheckInButton(stringResource(R.string.auth_parental_confirm_action), {
        viewModel.act(ParentalIntent.SubmitConfirm)
    }, enabled = state.canConfirm)
    OutlinedButton({ viewModel.act(ParentalIntent.Resend) }, enabled = state.canResend) {
        Text(stringResource(R.string.auth_parental_resend))
    }
    TextButton({ onMain(AuthIntent.GoLogin) }) { Text(stringResource(R.string.auth_back)) }
    StatusLine(state.busy, state.message, state.retryAfterSeconds)
}
