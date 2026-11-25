package br.malhaviva.feature.auth

import androidx.compose.foundation.combinedClickable
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
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.MalhaVivaTheme

// Entrada oculta (piloto): pressionar o titulo abre o dialogo do servidor.
internal fun Modifier.serverLongPress(onLongPress: (() -> Unit)?): Modifier =
    if (onLongPress == null) this else combinedClickable(onClick = {}, onLongClick = onLongPress)

@Composable
internal fun Title(
    text: String,
    modifier: Modifier = Modifier,
) = Text(text, modifier, style = MaterialTheme.typography.headlineSmall)

@Composable
internal fun Field(
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
internal fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MalhaVivaTheme.colors.textMuted)

@Composable
internal fun LoginForm(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
    onTitleLongPress: (() -> Unit)? = null,
) {
    Title(stringResource(R.string.auth_login_title), Modifier.serverLongPress(onTitleLongPress))
    Field(stringResource(R.string.auth_handle_label), state.handle, { onIntent(AuthIntent.HandleChanged(it)) })
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(AuthIntent.PasswordChanged(it))
    }, secret = true)
    CheckInButton(stringResource(R.string.auth_login_action), {
        onIntent(AuthIntent.SubmitLogin)
    }, enabled = state.canSubmitLogin)
    TextButton({ onIntent(AuthIntent.GoSignup) }) { Text(stringResource(R.string.auth_go_signup)) }
    TextButton({ onIntent(AuthIntent.GoRecovery) }) { Text(stringResource(R.string.auth_go_recovery)) }
    TextButton({ onIntent(AuthIntent.GoParental) }) { Text(stringResource(R.string.auth_go_parental)) }
}

@Composable
internal fun SignupForm(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
    onTitleLongPress: (() -> Unit)? = null,
) {
    Title(stringResource(R.string.auth_signup_title), Modifier.serverLongPress(onTitleLongPress))
    Field(stringResource(R.string.auth_handle_label), state.handle, { onIntent(AuthIntent.HandleChanged(it)) })
    Hint(stringResource(R.string.auth_signup_handle_hint))
    Field(stringResource(R.string.auth_password_label), state.password, {
        onIntent(AuthIntent.PasswordChanged(it))
    }, secret = true)
    Hint(stringResource(R.string.auth_password_hint))
    Field(
        stringResource(R.string.auth_birth_label),
        state.birthDate,
        { onIntent(AuthIntent.BirthDateChanged(it)) },
        keyboardType = KeyboardType.Number,
    )
    Hint(stringResource(R.string.auth_birth_hint))
    if (state.needsGuardian) GuardianFields(state, onIntent)
    if (GENDER_ENABLED) GenderPicker(state.gender, !state.busy) { onIntent(AuthIntent.GenderChanged(it)) }
    Field(
        stringResource(R.string.auth_email_label),
        state.email,
        { onIntent(AuthIntent.EmailChanged(it)) },
        keyboardType = KeyboardType.Email,
    )
    Field(
        stringResource(R.string.auth_phone_label),
        state.phone,
        { onIntent(AuthIntent.PhoneChanged(it)) },
        keyboardType = KeyboardType.Phone,
    )
    Hint(stringResource(R.string.auth_phone_hint))
    CheckInButton(stringResource(R.string.auth_signup_action), {
        onIntent(AuthIntent.SubmitSignup)
    }, enabled = state.canSubmitSignup)
    TextButton({ onIntent(AuthIntent.GoLogin) }) { Text(stringResource(R.string.auth_go_login)) }
}

/** Menor de 12 anos: pede o contato do responsavel, que precisa autorizar a conta. */
@Composable
private fun GuardianFields(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_guardian_title))
    Hint(stringResource(R.string.auth_guardian_info))
    val phone = state.guardianChannel == ContactChannel.Phone
    Row(horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s)) {
        OutlinedButton({ onIntent(AuthIntent.GuardianChannelChanged(ContactChannel.Phone)) }, enabled = !phone) {
            Text(stringResource(R.string.auth_contacts_phone))
        }
        OutlinedButton({ onIntent(AuthIntent.GuardianChannelChanged(ContactChannel.Email)) }, enabled = phone) {
            Text(stringResource(R.string.auth_contacts_email))
        }
    }
    Field(
        stringResource(if (phone) R.string.auth_guardian_phone_label else R.string.auth_guardian_email_label),
        state.guardian,
        { onIntent(AuthIntent.GuardianChanged(it)) },
        keyboardType = if (phone) KeyboardType.Phone else KeyboardType.Email,
    )
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
internal fun DevicesList(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
    onTitleLongPress: (() -> Unit)? = null,
) {
    // Entrada oculta (piloto): pressionar o titulo da conta abre o dialogo do servidor.
    val titleModifier =
        if (onTitleLongPress ==
            null
        ) {
            Modifier
        } else {
            Modifier.combinedClickable(onClick = {}, onLongClick = onTitleLongPress)
        }
    Title(stringResource(R.string.auth_devices_title), titleModifier)
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
    TextButton({ onIntent(AuthIntent.GoContacts) }) { Text(stringResource(R.string.auth_go_contacts)) }
    TextButton({ onIntent(AuthIntent.GoPrivacy) }) { Text(stringResource(R.string.auth_go_privacy)) }
    TextButton({ onIntent(AuthIntent.GoTotp) }) { Text(stringResource(R.string.auth_go_totp)) }
    TextButton({ onIntent(AuthIntent.Logout) }) { Text(stringResource(R.string.auth_logout)) }
}
