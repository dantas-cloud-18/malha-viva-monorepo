package br.malhaviva.feature.tracking

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [sharingBlocked] vem do perfil (menor de 18 anos): o compartilhamento nunca sai do estado Off. */
class TrackingViewModel(
    val sharingBlocked: Boolean = false,
    serviceEvents: Flow<ServiceEvent> = TrackingBridge.events,
    runningNow: StateFlow<TrackingState.Active?> = TrackingBridge.active,
) : MviViewModel<TrackingState, TrackingIntent>(TrackingState.Off) {
    init {
        runningNow.value?.let { onIntent(TrackingIntent.ServiceAlreadyRunning(it.sinceMs, it.lastStatus)) }
        viewModelScope.launch { serviceEvents.collect { onIntent(it.toIntent()) } }
    }

    override fun reduce(
        state: TrackingState,
        intent: TrackingIntent,
    ): TrackingState = reduceTracking(state, intent, sharingBlocked)
}
