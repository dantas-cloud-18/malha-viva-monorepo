package br.malhaviva.feature.trains.net

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.feature.trains.CrowdingLevel
import br.malhaviva.feature.trains.Direction
import br.malhaviva.feature.trains.LineNetwork
import br.malhaviva.feature.trains.LiveTrain
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.IOException
import java.net.HttpURLConnection

/** O que o cliente informa a quem consome o stream. */
sealed interface TrainsFeed {
    data object Connecting : TrainsFeed

    data object Opened : TrainsFeed

    data class Train(
        val train: LiveTrain,
    ) : TrainsFeed

    /** Polling de reserva: a lista inteira substitui a anterior. */
    data class Snapshot(
        val trains: List<LiveTrain>,
    ) : TrainsFeed

    /** Conexao caiu ou nao abriu; [attempt] conta falhas seguidas. A proxima tentativa vem apos o backoff. */
    data class Lost(
        val attempt: Int,
    ) : TrainsFeed
}

/** Fonte de dados da tela; o ViewModel depende so disto. */
interface TrainsSource {
    fun feed(direction: Direction): Flow<TrainsFeed>

    /** Malha do corredor; null se falhar. */
    suspend fun network(): LineNetwork?

    /** Lotacao por estacao (polling); null se falhar. */
    suspend fun crowding(direction: Direction): Map<String, CrowdingLevel?>?
}

/**
 * SSE `GET /v1/corridors/{corridor}/trains/stream`, com reconexao por backoff exponencial e jitter. Enquanto o
 * stream esta fora, cada falha busca tambem o snapshot (`GET .../trains`), entao o mapa continua atualizando
 * por polling com o mesmo ritmo do backoff (1 s ate 30 s). Uma conexao so conta como estavel (zera o backoff)
 * depois de [stableAfterMs] aberta, para nao martelar o servidor se ele fechar logo apos abrir.
 */
class LiveTrainsClient(
    baseUrl: String,
    private val transport: HttpTransport,
    private val source: SseSource,
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val stableAfterMs: Long = STABLE_AFTER_MS,
) : TrainsSource {
    private val base = baseUrl.trimEnd('/')

    override fun feed(direction: Direction): Flow<TrainsFeed> =
        channelFlow {
            var attempt = 0
            while (true) {
                send(TrainsFeed.Connecting)
                var openedAt: Long? = null
                var retryAfterMs: Long? = null
                try {
                    val parser = SseParser()
                    source.collect(
                        streamUrl(direction),
                        onOpen = {
                            openedAt = clock()
                            send(TrainsFeed.Opened)
                        },
                        onChunk = { chunk -> parser.feed(chunk).forEach { emit(it) } },
                    )
                } catch (e: SseHttpException) {
                    retryAfterMs = e.retryAfterMs
                } catch (_: IOException) {
                    // cai no backoff abaixo
                }
                ensureActive()
                val stable = openedAt?.let { clock() - it >= stableAfterMs } == true
                attempt = if (stable) 1 else attempt + 1
                send(TrainsFeed.Lost(attempt))
                snapshot(direction)?.let { send(TrainsFeed.Snapshot(it)) }
                delay(backoff.delayMs(attempt, retryAfterMs))
            }
        }

    override suspend fun network(): LineNetwork? =
        get("$base/v1/network/corridors/$CORRIDOR")?.let(TrainsWire::parseNetwork)

    override suspend fun crowding(direction: Direction): Map<String, CrowdingLevel?>? =
        get("$base/v1/corridors/$CORRIDOR/stations/crowding?direction=${direction.wire}")
            ?.let(TrainsWire::parseCrowding)

    private suspend fun ProducerScope<TrainsFeed>.emit(event: SseEvent) {
        if (event.type == TRAIN_EVENT) TrainsWire.parseTrain(event.data)?.let { send(TrainsFeed.Train(it)) }
    }

    private suspend fun snapshot(direction: Direction): List<LiveTrain>? =
        get("$base/v1/corridors/$CORRIDOR/trains?direction=${direction.wire}")?.let(TrainsWire::parseSnapshot)

    private suspend fun get(url: String): String? =
        try {
            val response = transport.execute(HttpRequest("GET", url, mapOf("Accept" to "application/json")))
            if (response.status == HttpURLConnection.HTTP_OK) response.body else null
        } catch (_: IOException) {
            null
        }

    private fun streamUrl(direction: Direction) =
        "$base/v1/corridors/$CORRIDOR/trains/stream?direction=${direction.wire}"

    companion object {
        const val CORRIDOR = "RAMAL_SANTA_CRUZ"
        const val STABLE_AFTER_MS = 30_000L
        private const val TRAIN_EVENT = "train"
    }
}
