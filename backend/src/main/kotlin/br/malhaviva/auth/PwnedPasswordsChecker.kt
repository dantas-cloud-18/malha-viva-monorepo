package br.malhaviva.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** Consulta de senhas vazadas. Implementacoes nunca podem enviar a senha nem o hash completo. */
interface PwnedPasswordsChecker {
    suspend fun isPwned(password: String): Boolean
}

/** Nunca acha nada; so para quem desliga a checagem de proposito. */
object NoPwnedPasswordsChecker : PwnedPasswordsChecker {
    override suspend fun isPwned(password: String): Boolean = false
}

private const val PREFIX_LENGTH = 5
private const val TIMEOUT_SECONDS = 3L
private const val HIBP_RANGE_URL = "https://api.pwnedpasswords.com/range/"
private val log = LoggerFactory.getLogger("br.malhaviva.auth.Pwned")

/** SHA-1 em hexa maiusculo; so o prefixo de 5 caracteres sai da maquina (k-anonimato do HIBP). */
internal fun sha1Hex(password: String): String =
    MessageDigest
        .getInstance("SHA-1")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

/** Linhas "SUFIXO:contagem"; contagem 0 e preenchimento (Add-Padding) e nao conta. */
internal fun rangeContains(
    body: String,
    suffix: String,
): Boolean =
    body.lineSequence().any { line ->
        val parts = line.trim().split(':')
        parts.size == 2 && parts[0].equals(suffix, ignoreCase = true) && (parts[1].toLongOrNull() ?: 0L) > 0L
    }

/**
 * HIBP Pwned Passwords. Falha aberta: se o servico cair, registra aviso (sem dados) e aceita, para que
 * uma indisponibilidade externa nao impeca cadastros. zxcvbn continua valendo.
 */
class HttpPwnedPasswordsChecker(
    private val baseUrl: String = HIBP_RANGE_URL,
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(TIMEOUT_SECONDS),
            ).build(),
) : PwnedPasswordsChecker {
    override suspend fun isPwned(password: String): Boolean {
        val hash = sha1Hex(password)
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl + hash.take(PREFIX_LENGTH)))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Add-Padding", "true")
                .GET()
                .build()
        return try {
            val response = withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            if (response.statusCode() == HTTP_OK) {
                rangeContains(response.body(), hash.drop(PREFIX_LENGTH))
            } else {
                failOpen("status ${response.statusCode()}")
            }
        } catch (e: IOException) {
            failOpen(e.javaClass.simpleName)
        }
    }

    private fun failOpen(reason: String): Boolean {
        log.warn("Pwned Passwords indisponivel ({}); checagem ignorada", reason)
        return false
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
