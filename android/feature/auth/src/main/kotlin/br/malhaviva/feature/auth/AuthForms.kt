package br.malhaviva.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.MalhaVivaTheme

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
    )
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MalhaVivaTheme.colors.textMuted)

@Composable
internal fun LoginForm(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_login_title))
    Field(stringResource(R.string.auth_handle_label), state.handle, { onIntent(AuthIntent.HandleChanged(it)) })
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(AuthIntent.PasswordChanged(it))
    }, secret = true)
    CheckInButton(stringResource(R.string.auth_login_action), {
        onIntent(AuthIntent.SubmitLogin)
    }, enabled = state.canSubmitLogin)
    TextButton({ onIntent(AuthIntent.GoSignup) }) { Text(stringResource(R.string.auth_go_signup)) }
}

@Composable
internal fun SignupForm(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_signup_title))
    Field(stringResource(R.string.auth_handle_label), state.handle, { onIntent(AuthIntent.HandleChanged(it)) })
    Hint(stringResource(R.string.auth_signup_handle_hint))
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(AuthIntent.PasswordChanged(it))
    }, secret = true)
    Hint(stringResource(R.string.auth_password_hint))
    Field(
        stringResource(R.string.auth_email_label),
        state.email,
        { onIntent(AuthIntent.EmailChanged(it)) },
        keyboardType = KeyboardType.Email,
    )
    CheckInButton(stringResource(R.string.auth_signup_action), {
        onIntent(AuthIntent.SubmitSignup)
    }, enabled = state.canSubmitSignup)
    TextButton({ onIntent(AuthIntent.GoLogin) }) { Text(stringResource(R.string.auth_go_login)) }
}

@Composable
internal fun MfaForm(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_mfa_title))
    val label = if (state.useBackupCode) R.string.auth_mfa_backup_label else R.string.auth_mfa_totp_label
    Field(
        stringResource(label),
        state.mfaCode,
        { onIntent(AuthIntent.MfaCodeChanged(it)) },
        keyboardType = if (state.useBackupCode) KeyboardType.Text else KeyboardType.NumberPassword,
    )
    CheckInButton(stringResource(R.string.auth_mfa_action), {
        onIntent(AuthIntent.SubmitMfa)
    }, enabled = state.canSubmitMfa)
    TextButton({ onIntent(AuthIntent.ToggleBackupCode) }) {
        Text(stringResource(if (state.useBackupCode) R.string.auth_mfa_use_totp else R.string.auth_mfa_use_backup))
    }
    TextButton({ onIntent(AuthIntent.GoLogin) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
internal fun EnrollmentNotice(onIntent: (AuthIntent) -> Unit) {
    Title(stringResource(R.string.auth_enroll_title))
    Text(stringResource(R.string.auth_enroll_message), style = MaterialTheme.typography.bodyLarge)
    TextButton({ onIntent(AuthIntent.GoLogin) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
internal fun DevicesList(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_devices_title))
    if (state.devices.isEmpty() && !state.busy) Hint(stringResource(R.string.auth_devices_empty))
    state.devices.forEach { device ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(device.label, style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(R.string.auth_devices_last_used, device.lastUsedAt))
            }
            OutlinedButton({ onIntent(AuthIntent.RevokeDevice(device.id)) }, enabled = !state.busy) {
                Text(stringResource(R.string.auth_device_revoke))
            }
        }
    }
    TextButton({ onIntent(AuthIntent.Logout) }) { Text(stringResource(R.string.auth_logout)) }
}
