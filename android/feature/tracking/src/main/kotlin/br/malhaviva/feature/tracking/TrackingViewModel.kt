package br.malhaviva.feature.tracking

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [sharingBlocked] vem do perfil (menor de 18 anos ou faixa desconhecida): o compartilhamento nunca sai do Off. */
class TrackingViewModel(
    val sharingBlocked: StateFlow<Boolean> = MutableStateFlow(false),
    serviceEvents: Flow<ServiceEvent> = TrackingBridge.events,
    runningNow: StateFlow<TrackingState.Active?> = TrackingBridge.active,
    private val consent: StateFlow<Boolean> = TrackingEnvironment.consent.granted,
) : MviViewModel<TrackingState, TrackingIntent>(TrackingState.Off) {
    init {
        runningNow.value?.let { onIntent(TrackingIntent.ServiceAlreadyRunning(it.sinceMs, it.lastStatus)) }
        viewModelScope.launch { serviceEvents.collect { onIntent(it.toIntent()) } }
        viewModelScope.launch { consent.collect { onIntent(TrackingIntent.ConsentChanged(it)) } }
    }

    override fun reduce(
        state: TrackingState,
        intent: TrackingIntent,
    ): TrackingState = reduceTracking(state, intent, sharingBlocked.value, consent.value)
}
