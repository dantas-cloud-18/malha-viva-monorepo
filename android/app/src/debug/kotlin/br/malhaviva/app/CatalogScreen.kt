package br.malhaviva.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.ConfidenceRing
import br.malhaviva.core.designsystem.CrowdIndicator
import br.malhaviva.core.designsystem.CrowdLevel
import br.malhaviva.core.designsystem.EmptyState
import br.malhaviva.core.designsystem.LineHeader
import br.malhaviva.core.designsystem.LineTimeline
import br.malhaviva.core.designsystem.MalhaVivaSpacing
import br.malhaviva.core.designsystem.RefreshIndicator
import br.malhaviva.core.designsystem.StatusBadge
import br.malhaviva.core.designsystem.StopState
import br.malhaviva.core.designsystem.TimelineStop
import br.malhaviva.core.designsystem.TrainMarker
import br.malhaviva.core.designsystem.TrainStatus

private val labels =
    mapOf(
        TrainStatus.OnTime to "No horário",
        TrainStatus.Delayed to "Atrasado",
        TrainStatus.Interrupted to "Interrompido",
        TrainStatus.NoSignal to "Sem sinal",
        TrainStatus.LowConfidence to "Pouca confiança",
    )

private val confidences = listOf(HIGH, MID, LOW)
private const val HIGH = 0.9f
private const val MID = 0.6f
private const val LOW = 0.3f
private const val PERCENT = 100

private val stops =
    listOf(
        TimelineStop("Central do Brasil", "08:02", StopState.Passed),
        TimelineStop("Madureira", "08:21", StopState.Passed),
        TimelineStop("Bangu", "08:44", StopState.Current),
        TimelineStop("Santa Cruz", "09:12", StopState.Upcoming),
    )

/** Catalogo dos componentes e estados do design system (tela de avaliacao visual). */
@Composable
fun CatalogScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = MalhaVivaSpacing.xxl),
        ) {
            LineHeader(
                "Ramal Santa Cruz",
                "Central do Brasil → Santa Cruz",
                TrainStatus.OnTime,
                labels.getValue(TrainStatus.OnTime),
            )
            Section("Estados (cor + ícone + texto)") {
                TrainStatus.entries.forEach { s ->
                    Row(Modifier.padding(vertical = MalhaVivaSpacing.xs)) {
                        StatusBadge(s, labels.getValue(s))
                        Spacer(Modifier.padding(MalhaVivaSpacing.xs))
                        TrainMarker(s, labels.getValue(s))
                    }
                }
            }
            Section("Lotação") {
                CrowdIndicator(CrowdLevel.Empty, "Vazio")
                CrowdIndicator(CrowdLevel.Moderate, "Moderado")
                CrowdIndicator(CrowdLevel.Full, "Lotado")
            }
            Section("Confiança") {
                Row {
                    confidences.forEach {
                        ConfidenceRing(it, "Confiança ${(it * PERCENT).toInt()}%")
                        Spacer(Modifier.padding(MalhaVivaSpacing.xs))
                    }
                }
            }
            Section("Linha do tempo") { LineTimeline(stops) }
            Section("Atualização") {
                RefreshIndicator(true, "Atualizando…")
                RefreshIndicator(false, "Atualizado há 10 s")
            }
            Section("Ação") { CheckInButton("Estou no trem", onClick = {}) }
            Section("Vazio") { EmptyState("Nenhum trem agora", "Quando um trem for detectado ele aparece aqui.") }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(MalhaVivaSpacing.l))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = MalhaVivaSpacing.l, vertical = MalhaVivaSpacing.xs),
    )
    Column(Modifier.padding(horizontal = MalhaVivaSpacing.l)) { content() }
}
