package br.malhaviva.feature.auth

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import br.malhaviva.core.designsystem.CheckInButton
import kotlinx.coroutines.launch

/** TOTP: ativar, regenerar codigos de backup ou desligar. [enrollOnly] e a sessao restrita do admin. */
@Composable
internal fun TotpFlow(
    viewModel: TotpViewModel,
    enrollOnly: Boolean,
    onMain: (AuthIntent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    FlowEffects({ viewModel.act(TotpIntent.Open(enrollOnly)) }, state.sessionLost || state.relogin) {
        onMain(if (state.relogin) AuthIntent.EnrollmentFinished else AuthIntent.SessionLost)
    }
    val leave = { onMain(if (enrollOnly) AuthIntent.Logout else AuthIntent.GoDevices) }
    val onIntent = viewModel::act
    when (state.step) {
        TotpStep.Menu -> TotpMenu(onIntent, leave)
        TotpStep.EnrollStart -> EnrollStartForm(state, onIntent, leave)
        TotpStep.EnrollConfirm -> EnrollConfirmForm(state, onIntent)
        TotpStep.BackupCodes -> BackupCodesView(state, onIntent)
        TotpStep.Regenerate -> RegenerateForm(state, onIntent)
        TotpStep.Disable -> DisableForm(state, onIntent)
    }
    StatusLine(state.busy, state.message, state.retryAfterSeconds)
}

@Composable
private fun TotpMenu(
    onIntent: (TotpIntent) -> Unit,
    onBack: () -> Unit,
) {
    Title(stringResource(R.string.auth_totp_title))
    OutlinedButton({ onIntent(TotpIntent.Go(TotpStep.EnrollStart)) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.auth_totp_menu_enable))
    }
    OutlinedButton({ onIntent(TotpIntent.Go(TotpStep.Regenerate)) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.auth_totp_menu_regenerate))
    }
    OutlinedButton({ onIntent(TotpIntent.Go(TotpStep.Disable)) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.auth_totp_menu_disable))
    }
    TextButton(onBack) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun EnrollStartForm(
    state: TotpState,
    onIntent: (TotpIntent) -> Unit,
    onBack: () -> Unit,
) {
    Title(stringResource(R.string.auth_enroll_title))
    if (state.enrollOnly) Hint(stringResource(R.string.auth_enroll_message))
    Hint(stringResource(R.string.auth_totp_start_info))
    if (state.needsEnrollmentCode) {
        Field(stringResource(R.string.auth_totp_enrollment_code), state.enrollmentCode, {
            onIntent(TotpIntent.EnrollmentCodeChanged(it))
        }, secret = true)
    }
    CheckInButton(stringResource(R.string.auth_totp_start_action), {
        onIntent(TotpIntent.SubmitStart)
    }, enabled = state.canStart)
    TextButton({ if (state.enrollOnly) onBack() else onIntent(TotpIntent.Go(TotpStep.Menu)) }) {
        Text(stringResource(if (state.enrollOnly) R.string.auth_logout else R.string.auth_back))
    }
}

@Composable
private fun EnrollConfirmForm(
    state: TotpState,
    onIntent: (TotpIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_totp_title))
    state.enrollment?.let {
        // Sem dependencia nova de QR: segredo e URI como texto selecionavel para entrada manual.
        Text(stringResource(R.string.auth_totp_secret_title), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(it.secret, style = MaterialTheme.typography.bodyLarge) }
        Text(stringResource(R.string.auth_totp_uri_title), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(it.otpauthUri, style = MaterialTheme.typography.bodySmall) }
    }
    Field(
        stringResource(R.string.auth_totp_confirm_label),
        state.code,
        { onIntent(TotpIntent.CodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    CheckInButton(stringResource(R.string.auth_totp_confirm_action), {
        onIntent(TotpIntent.SubmitConfirm)
    }, enabled = state.canConfirm)
}

/** Unica saida: "Guardei os codigos". Nao ha botao de voltar enquanto os codigos estao na tela. */
@Composable
private fun BackupCodesView(
    state: TotpState,
    onIntent: (TotpIntent) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Title(stringResource(R.string.auth_totp_codes_title))
    Hint(stringResource(R.string.auth_totp_codes_warning))
    SelectionContainer {
        Text(state.backupCodes.joinToString("\n"), style = MaterialTheme.typography.titleMedium)
    }
    OutlinedButton({ scope.launch { clipboard.setClipEntry(sensitiveClip(state.backupCodes.joinToString("\n"))) } }) {
        Text(stringResource(R.string.auth_totp_codes_copy))
    }
    CheckInButton(stringResource(R.string.auth_totp_codes_saved), { onIntent(TotpIntent.Acknowledge) })
}

/** Marca o clip como sensivel para o teclado e o Android 13+ esconderem a previa. */
private fun sensitiveClip(text: String): ClipEntry {
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    return ClipEntry(clip)
}

@Composable
private fun RegenerateForm(
    state: TotpState,
    onIntent: (TotpIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_totp_menu_regenerate))
    Hint(stringResource(R.string.auth_totp_regenerate_info))
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(TotpIntent.PasswordChanged(it))
    }, secret = true)
    Field(
        stringResource(if (state.useBackupCode) R.string.auth_mfa_backup_label else R.string.auth_mfa_totp_label),
        state.code,
        { onIntent(TotpIntent.CodeChanged(it)) },
        keyboardType = if (state.useBackupCode) KeyboardType.Text else KeyboardType.NumberPassword,
    )
    TextButton({ onIntent(TotpIntent.ToggleBackupCode) }) {
        Text(stringResource(if (state.useBackupCode) R.string.auth_mfa_use_totp else R.string.auth_mfa_use_backup))
    }
    CheckInButton(stringResource(R.string.auth_totp_regenerate_action), {
        onIntent(TotpIntent.SubmitRegenerate)
    }, enabled = state.canRegenerate)
    TextButton({ onIntent(TotpIntent.Go(TotpStep.Menu)) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun DisableForm(
    state: TotpState,
    onIntent: (TotpIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_totp_menu_disable))
    Hint(stringResource(R.string.auth_totp_disable_info))
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(TotpIntent.PasswordChanged(it))
    }, secret = true)
    Field(
        stringResource(R.string.auth_mfa_totp_label),
        state.code,
        { onIntent(TotpIntent.CodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    CheckInButton(stringResource(R.string.auth_totp_disable_action), {
        onIntent(TotpIntent.SubmitDisable)
    }, enabled = state.canDisable)
    TextButton({ onIntent(TotpIntent.Go(TotpStep.Menu)) }) { Text(stringResource(R.string.auth_back)) }
}
