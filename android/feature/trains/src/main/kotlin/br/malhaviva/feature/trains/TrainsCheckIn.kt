package br.malhaviva.feature.trains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.MalhaVivaSpacing

/** Check-in anonimo para a lotacao: toque numa estacao e confirme; vale 10 min e pode ser cancelado. */
@Composable
internal fun CheckInPanel(
    checkIn: StationCheckIn?,
    picked: LineStation?,
    onCheckIn: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(MalhaVivaSpacing.l),
        verticalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.s),
    ) {
        if (checkIn != null) {
            Text(stringResource(R.string.trains_checkin_active), style = MaterialTheme.typography.bodyMedium)
            CheckInButton(stringResource(R.string.trains_checkin_cancel), onCancel)
        } else if (picked != null) {
            Text(
                stringResource(R.string.trains_checkin_picked, picked.name),
                style = MaterialTheme.typography.bodyMedium,
            )
            CheckInButton(stringResource(R.string.trains_checkin_button), { onCheckIn(picked.id) })
        } else {
            Text(stringResource(R.string.trains_checkin_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
