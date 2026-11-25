package br.malhaviva.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import br.malhaviva.core.designsystem.MalhaVivaSpacing

private val MIN_TOUCH_TARGET = 48.dp

/** Rotulo do genero para exibir o valor atual; null e "nao informado". */
internal fun genderLabel(gender: Gender?): Int =
    when (gender) {
        Gender.Male -> R.string.auth_gender_male
        Gender.Female -> R.string.auth_gender_female
        Gender.NonBinary -> R.string.auth_gender_non_binary
        Gender.PreferNotToSay -> R.string.auth_gender_prefer_not
        null -> R.string.auth_gender_not_informed
    }

/** Grupo de radio acessivel (a linha inteira e o alvo de toque). A primeira opcao, "Nao informar", e null. */
@Composable
internal fun GenderPicker(
    selected: Gender?,
    enabled: Boolean,
    onSelect: (Gender?) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.auth_gender_label), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(R.string.auth_gender_hint))
        GenderOption(stringResource(R.string.auth_gender_none), selected == null, enabled) { onSelect(null) }
        Gender.entries.forEach { option ->
            GenderOption(stringResource(genderLabel(option)), selected == option, enabled) { onSelect(option) }
        }
    }
}

@Composable
private fun GenderOption(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = null, enabled = enabled)
        Text(label)
    }
}
