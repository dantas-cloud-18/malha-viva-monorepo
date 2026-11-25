package br.malhaviva.feature.auth

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.MalhaVivaSpacing

/** Privacidade: consentimentos, historico, exportacao, correcao do @nome e exclusao da conta. */
@Composable
internal fun PrivacyFlow(
    viewModel: PrivacyViewModel,
    onMain: (AuthIntent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    FlowEffects({ viewModel.act(PrivacyIntent.Open) }, state.sessionLost) { onMain(AuthIntent.SessionLost) }
    LaunchedEffect(state.exportJson) {
        state.exportJson?.let {
            shareExport(context, it)
            viewModel.act(PrivacyIntent.ExportConsumed)
        }
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onMain(AuthIntent.AccountDeleted) }
    when (state.step) {
        PrivacyStep.Main -> PrivacyMain(state, viewModel::act) { onMain(AuthIntent.GoDevices) }
        PrivacyStep.History -> HistoryList(state, viewModel::act)
        PrivacyStep.EditHandle -> EditHandleForm(state, viewModel::act)
        PrivacyStep.EditGender -> EditGenderForm(state, viewModel::act)
        PrivacyStep.Delete -> DeleteForm(state, viewModel::act)
    }
    StatusLine(state.busy, state.message, state.retryAfterSeconds)
}

/** Entrega o JSON ao compartilhamento do sistema (salvar em arquivos, e-mail...), sem permissoes nem libs novas. */
private fun shareExport(
    context: Context,
    json: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.auth_privacy_export_subject))
            .putExtra(Intent.EXTRA_TEXT, json)
    val chooser = Intent.createChooser(send, context.getString(R.string.auth_privacy_export_chooser))
    context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun PrivacyMain(
    state: PrivacyState,
    onIntent: (PrivacyIntent) -> Unit,
    onBack: () -> Unit,
) {
    Title(stringResource(R.string.auth_privacy_title))
    Hint(stringResource(R.string.auth_privacy_intro))
    ConsentRow(
        stringResource(R.string.auth_privacy_location_title),
        stringResource(
            if (state.locationForbidden) R.string.auth_privacy_location_minor else R.string.auth_privacy_location_hint,
        ),
        state.location && !state.locationForbidden,
        !state.busy && !state.locationForbidden,
    ) { onIntent(PrivacyIntent.Toggle(ConsentPurpose.Location, it)) }
    // Menor de 18 anos nunca recebe marketing: o interruptor fica desligado e explicado.
    ConsentRow(
        stringResource(R.string.auth_privacy_marketing_title),
        stringResource(marketingHint(state.marketingForbidden)),
        state.marketing && !state.marketingForbidden,
        !state.busy && !state.marketingForbidden,
    ) { onIntent(PrivacyIntent.Toggle(ConsentPurpose.Marketing, it)) }
    if (state.needsReconsent) Hint(stringResource(R.string.auth_privacy_reconsent))
    OutlinedButton({ onIntent(PrivacyIntent.ShowHistory) }, enabled = !state.busy) {
        Text(stringResource(R.string.auth_privacy_history))
    }
    Title(stringResource(R.string.auth_privacy_rights_title))
    OutlinedButton({ onIntent(PrivacyIntent.Export) }, enabled = !state.busy) {
        Text(stringResource(R.string.auth_privacy_export))
    }
    OutlinedButton({ onIntent(PrivacyIntent.StartEditHandle) }, enabled = !state.busy) {
        Text(stringResource(R.string.auth_privacy_edit_handle))
    }
    if (state.genderLoaded) {
        Hint(stringResource(R.string.auth_privacy_gender_current, stringResource(genderLabel(state.gender))))
    }
    OutlinedButton({ onIntent(PrivacyIntent.StartEditGender) }, enabled = !state.busy && state.genderLoaded) {
        Text(stringResource(R.string.auth_privacy_edit_gender))
    }
    OutlinedButton({ onIntent(PrivacyIntent.StartDelete) }, enabled = !state.busy) {
        Text(stringResource(R.string.auth_privacy_delete))
    }
    TextButton(onBack) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun ConsentRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun HistoryList(
    state: PrivacyState,
    onIntent: (PrivacyIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_privacy_history))
    if (state.history.isEmpty() && !state.busy) Hint(stringResource(R.string.auth_privacy_history_empty))
    state.history.forEach { event ->
        val purpose =
            stringResource(
                if (event.purpose == ConsentPurpose.Location.wire) {
                    R.string.auth_privacy_location_title
                } else {
                    R.string.auth_privacy_marketing_title
                },
            )
        val action =
            stringResource(
                if (event.action == "granted") R.string.auth_privacy_granted else R.string.auth_privacy_revoked,
            )
        Text(stringResource(R.string.auth_privacy_history_item, purpose, action, event.occurredAt))
    }
    TextButton({ onIntent(PrivacyIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun EditHandleForm(
    state: PrivacyState,
    onIntent: (PrivacyIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_privacy_edit_handle))
    Field(stringResource(R.string.auth_privacy_new_handle), state.newHandle, {
        onIntent(PrivacyIntent.HandleChanged(it))
    })
    Hint(stringResource(R.string.auth_signup_handle_hint))
    CheckInButton(stringResource(R.string.auth_privacy_save), {
        onIntent(PrivacyIntent.SubmitHandle)
    }, enabled = state.canSaveHandle)
    TextButton({ onIntent(PrivacyIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun EditGenderForm(
    state: PrivacyState,
    onIntent: (PrivacyIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_privacy_edit_gender))
    GenderPicker(state.draftGender, !state.busy) { onIntent(PrivacyIntent.GenderPicked(it)) }
    CheckInButton(stringResource(R.string.auth_privacy_save), {
        onIntent(PrivacyIntent.SubmitGender)
    }, enabled = state.canSaveGender)
    TextButton({ onIntent(PrivacyIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}

@Composable
private fun DeleteForm(
    state: PrivacyState,
    onIntent: (PrivacyIntent) -> Unit,
) {
    Title(stringResource(R.string.auth_privacy_delete))
    Text(stringResource(R.string.auth_privacy_delete_warning), color = MaterialTheme.colorScheme.error)
    Field(stringResource(R.string.auth_contacts_current_password), state.password, {
        onIntent(PrivacyIntent.PasswordChanged(it))
    }, secret = true)
    CheckInButton(stringResource(R.string.auth_privacy_delete_action), {
        onIntent(PrivacyIntent.SubmitDelete)
    }, enabled = state.canDelete)
    TextButton({ onIntent(PrivacyIntent.Back) }) { Text(stringResource(R.string.auth_back)) }
}

private fun marketingHint(forbidden: Boolean) =
    if (forbidden) R.string.auth_privacy_marketing_minor else R.string.auth_privacy_marketing_hint
