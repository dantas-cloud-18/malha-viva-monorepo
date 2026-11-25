package br.malhaviva.feature.trains.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resposta nao-200 ao abrir o stream (ex.: 503 com `Retry-After` quando o teto de conexoes estoura). */
class SseHttpException(
    val status: Int,
    val retryAfterMs: Long?,
) : IOException("SSE HTTP $status")

/** Abre um stream SSE e entrega o texto recebido, em pedacos, ate o fim ou ate falhar. Falha de rede = IOException. */
fun interface SseSource {
    /** [onOpen] roda uma vez, apos o servidor responder 200 e antes do primeiro pedaco. */
    suspend fun collect(
        url: String,
        onOpen: suspend () -> Unit,
        onChunk: suspend (String) -> Unit,
    )
}

/** SSE sobre HttpURLConnection. Cancelar a coroutine fecha a conexao (a leitura bloqueada estoura). */
class UrlConnectionSseSource(
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : SseSource {
    override suspend fun collect(
        url: String,
        onOpen: suspend () -> Unit,
        onChunk: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Leitura em socket nao responde a interrupcao: so fechar a conexao a destrava.
        val closer =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                throw SseHttpException(status, retryAfter?.times(MS_PER_S))
            }
            onOpen()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(BUFFER)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    onChunk(String(buffer, 0, n))
                }
            }
        } finally {
            closer.cancel()
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        // Heartbeat a cada 15 s: tres perdidos = conexao morta.
        const val READ_TIMEOUT_MS = 45_000
        const val BUFFER = 4096
        const val MS_PER_S = 1000L
    }
}
