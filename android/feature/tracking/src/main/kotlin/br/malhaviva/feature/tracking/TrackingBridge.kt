package br.malhaviva.feature.tracking

import br.malhaviva.core.domain.sampling.SampleBatch
import br.malhaviva.core.domain.tracking.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

/** Canal servico -> ViewModel no mesmo processo. Sem replay: evento so vale se a UI estiver ouvindo. */
object TrackingBridge {
    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: Flow<ServiceEvent> = mutableEvents.asSharedFlow()

    fun publish(event: ServiceEvent) {
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

/** Envio do lote. A implementacao real (HTTP assinado) vem depois; false significa falha. */
interface BatchUploader {
    suspend fun upload(batch: SampleBatch): Boolean
}

object NoopBatchUploader : BatchUploader {
    override suspend fun upload(batch: SampleBatch): Boolean = true
}

/**
 * Dependencias do servico, preenchidas pelo app (ex.: no Application) antes de iniciar a viagem.
 * shortcut: holder global mutavel, trocar por injecao quando o Hilt chegar neste modulo.
 */
object TrackingEnvironment {
    @Volatile var uploader: BatchUploader = NoopBatchUploader

    @Volatile var routeProvider: RouteProvider = RouteProvider { null }
}
