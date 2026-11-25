package br.malhaviva.core.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HttpResponse(
    val status: Int,
    val body: String,
    /** Nomes em minusculas. */
    val headers: Map<String, String> = emptyMap(),
)

/** Camada HTTP minima, trocavel por um falso nos testes. Falha de rede lanca IOException. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val failed = status >= HttpURLConnection.HTTP_BAD_REQUEST
                val stream = if (failed) connection.errorStream else connection.inputStream
                val headers =
                    connection.headerFields
                        .filterKeys {
                            it != null
                        }.mapKeys { it.key.lowercase() }
                        .mapValues { it.value.last() }
                HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), headers)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
