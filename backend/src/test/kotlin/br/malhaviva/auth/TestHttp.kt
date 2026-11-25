package br.malhaviva.auth

import br.malhaviva.installForwardedHeaders
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Primeiro IP "de fora" dos testes: o ALB acrescenta o IP do cliente ao fim de X-Forwarded-For. */
const val TEST_CLIENT_IP = "203.0.113.10"

/** App com a mesma configuracao de IP da producao ([installForwardedHeaders]) e as rotas de conta. */
fun authApp(
    c: AuthComponents,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        installForwardedHeaders()
        install(ContentNegotiation) { json() }
        routing { route("/v1") { authRoutes(c) } }
    }
    block()
}

/** POST JSON; [ip] vira o item que o ALB acrescenta ao X-Forwarded-For. */
suspend fun ApplicationTestBuilder.httpPost(
    path: String,
    body: String,
    bearer: String? = null,
    ip: String = TEST_CLIENT_IP,
): HttpResponse =
    client.post("/v1$path") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header("X-Forwarded-For", ip)
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(body)
    }

suspend fun ApplicationTestBuilder.httpGet(
    path: String,
    bearer: String,
    ip: String = TEST_CLIENT_IP,
): HttpResponse =
    client.get("/v1$path") {
        header("X-Forwarded-For", ip)
        header(HttpHeaders.Authorization, "Bearer $bearer")
    }

suspend fun ApplicationTestBuilder.httpDelete(
    path: String,
    bearer: String,
): HttpResponse =
    client.delete("/v1$path") {
        header("X-Forwarded-For", TEST_CLIENT_IP)
        header(HttpHeaders.Authorization, "Bearer $bearer")
    }

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

/** Cadastra e entra; devolve o par de tokens como JSON. */
suspend fun ApplicationTestBuilder.signupAndLogin(
    handle: String,
    ip: String = TEST_CLIENT_IP,
): JsonObject {
    val created = httpPost("/auth/accounts", """{"handle":"$handle","password":"$STRONG_PASSWORD"}""", ip = ip)
    check(created.status.value == 201) { "cadastro de teste falhou: ${created.status}" }
    return httpPost("/auth/token", """{"handle":"$handle","password":"$STRONG_PASSWORD"}""", ip = ip).json()
}
