package br.malhaviva.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.MalhaVivaSpacing

/** Contatos de recuperacao: lista, inclusao com senha, OTP e trocas pendentes. */
@Composable
internal fun ContactsFlow(
    viewModel: ContactsViewModel,
    onMain: (AuthIntent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    FlowEffects({ viewModel.act(ContactsIntent.Open) }, state.sessionLost) { onMain(AuthIntent.SessionLost) }
    when (state.step) {
        ContactsStep.List -> ContactsList(state, viewModel::act) { onMain(AuthIntent.GoDevices) }
        ContactsStep.Add -> AddContactForm(state, viewModel::act)
        ContactsStep.Confirm -> ConfirmContactForm(state, viewModel::act)
    }
    StatusLine(state.busy, state.message, state.retryAfterSeconds)
}

@Composable
private fun ContactsList(
    state: ContactsState,
    onIntent: (ContactsIntent) -> Unit,
    onBack: () -> Unit,
) {
    Title(stringResource(R.string.auth_contacts_title))
    ChannelRow(ContactChannel.Phone, state.phoneVerified, state.busy, onIntent)
    ChannelRow(ContactChannel.Email, state.emailVerified, state.busy, onIntent)
    if (state.pending.isNotEmpty()) {
        Text(stringResource(R.string.auth_contacts_pending_title), style = MaterialTheme.typography.titleMedium)
    }
    state.pending.forEach { change ->
        val channel = if (change.channel == ContactChannel.Phone.wire) ContactChannel.Phone else ContactChannel.Email
        Text(stringResource(R.string.auth_contacts_pending_item, channelName(channel), change.effectiveAt))
        Row(horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s)) {
            if (!change.confirmed) {
                Hint(stringResource(R.string.auth_contacts_pending_unconfirmed))
                OutlinedButton({ onIntent(ContactsIntent.StartConfirm(channel, change.id)) }, enabled = !state.busy) {
                    Text(stringResource(R.string.auth_contacts_enter_code))
                }
            }
            OutlinedButton({ onIntent(ContactsIntent.Cancel(change.id)) }, enabled = !state.busy) {
                Text(stringResource(R.string.auth_contacts_cancel_change))
            }
        }
    }
    TextButton(onBack) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun channelName(channel: ContactChannel) =
    stringResource(if (channel == ContactChannel.Phone) R.string.auth_contacts_phone else R.string.auth_contacts_email)

@Composable
private fun ChannelRow(
    channel: ContactChannel,
    verified: Boolean,
    busy: Boolean,
    onIntent: (ContactsIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(channelName(channel), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(if (verified) R.string.auth_contacts_verified else R.string.auth_contacts_unverified))
        OutlinedButton({ onIntent(ContactsIntent.StartAdd(channel)) }, enabled = !busy) {
            Text(stringResource(R.string.auth_contacts_add))
        }
        if (!verified) {
            TextButton({ onIntent(ContactsIntent.StartConfirm(channel, null)) }, enabled = !busy) {
                Text(stringResource(R.string.auth_contacts_have_code))
            }
        }
    }
}

@Composable
private fun AddContactForm(
    state: ContactsState,
    onIntent: (ContactsIntent) -> Unit,
) {
    val phone = state.channel == ContactChannel.Phone
    Title(stringResource(R.string.auth_contacts_add))
    Hint(stringResource(R.string.auth_contacts_add_hint))
    Field(
        stringResource(if (phone) R.string.auth_contacts_value_phone else R.string.auth_contacts_value_email),
        state.value,
        { onIntent(ContactsIntent.ValueChanged(it)) },
        keyboardType = if (phone) KeyboardType.Phone else KeyboardType.Email,
        mask = if (phone) Mask.Phone else Mask.None,
    )
    Field(stringResource(R.string.auth_contacts_current_password), state.password, {
        onIntent(ContactsIntent.PasswordChanged(it))
    }, secret = true)
    CheckInButton(stringResource(R.string.auth_contacts_send_action), {
        onIntent(ContactsIntent.SubmitAdd)
    }, enabled = state.canAdd)
    TextButton({ onIntent(ContactsIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun ConfirmContactForm(
    state: ContactsState,
    onIntent: (ContactsIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_contacts_confirm_action))
    Field(
        stringResource(R.string.auth_contacts_code_label),
        state.code,
        { onIntent(ContactsIntent.CodeChanged(it)) },
        keyboardType = KeyboardType.NumberPassword,
    )
    // Verificar um contato gravado exige a senha; confirmar uma troca ja a provou ao pedir.
    if (state.changeId == null) {
        Field(stringResource(R.string.auth_contacts_current_password), state.password, {
            onIntent(ContactsIntent.PasswordChanged(it))
        }, secret = true)
    }
    CheckInButton(stringResource(R.string.auth_contacts_confirm_action), {
        onIntent(ContactsIntent.SubmitConfirm)
    }, enabled = state.canConfirm)
    TextButton({ onIntent(ContactsIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}
