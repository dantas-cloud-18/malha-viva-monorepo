package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.sampling.SampleBatch
import br.malhaviva.core.domain.tracking.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Eventos do servico para a UI. */
sealed interface ServiceEvent {
    data class Started(
        val sinceMs: Long,
    ) : ServiceEvent

    data class Status(
        val status: TripStatus,
    ) : ServiceEvent

    data class Stopped(
        val cause: StopCause,
    ) : ServiceEvent
}

/**
 * Canal servico -> ViewModel no mesmo processo. [events] nao tem replay: evento so vale se a UI estiver
 * ouvindo. [active] guarda a viagem em curso para um ViewModel novo saber que o servico ja roda.
 */
object TrackingBridge {
    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: Flow<ServiceEvent> = mutableEvents.asSharedFlow()

    private val mutableActive = MutableStateFlow<TrackingState.Active?>(null)
    val active: StateFlow<TrackingState.Active?> = mutableActive.asStateFlow()

    fun publish(event: ServiceEvent) {
        mutableActive.update { current ->
            when (event) {
                is ServiceEvent.Started -> TrackingState.Active(event.sinceMs, TripStatus.Waiting)
                is ServiceEvent.Status -> current?.copy(lastStatus = event.status)
                is ServiceEvent.Stopped -> null
            }
        }
        mutableEvents.tryEmit(event)
    }

    private const val EVENT_BUFFER = 16
}

fun ServiceEvent.toIntent(): TrackingIntent =
    when (this) {
        is ServiceEvent.Started -> TrackingIntent.ServiceStarted(sinceMs)
        is ServiceEvent.Status -> TrackingIntent.StatusChanged(status)
        is ServiceEvent.Stopped -> TrackingIntent.ServiceStopped(cause)
    }

fun interface RouteProvider {
    fun current(): Route?
}

/** Envio do lote; false significa falha. A implementacao real e `net.HttpBatchUploader`. */
interface BatchUploader {
    suspend fun upload(batch: SampleBatch): Boolean
}

object NoopBatchUploader : BatchUploader {
    override suspend fun upload(batch: SampleBatch): Boolean = true
}

/**
 * Consentimento de localizacao da conta. [granted] e fonte unica da verdade: o servico e a UI leem daqui,
 * entao revogar na tela de Privacidade encerra a viagem mesmo com a tela de rastreamento fechada.
 */
interface LocationConsent {
    val granted: StateFlow<Boolean>
}

/** Padrao falha fechada: sem implementacao do app, o consentimento conta como nao concedido. */
object DeniedLocationConsent : LocationConsent {
    override val granted: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}

/**
 * Dependencias do servico, preenchidas pelo app (ex.: no Application) antes de iniciar a viagem.
 * shortcut: holder global mutavel, trocar por injecao quando o Hilt chegar neste modulo.
 */
object TrackingEnvironment {
    @Volatile var uploader: BatchUploader = NoopBatchUploader

    @Volatile var routeProvider: RouteProvider = RouteProvider { null }

    @Volatile var consent: LocationConsent = DeniedLocationConsent
}
