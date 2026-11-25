package br.malhaviva.feature.auth

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpResponse
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.core.data.secure.InMemorySecretStore
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.AuthApi
import org.json.JSONObject
import java.io.IOException

/** Transporte falso: respostas em fila por "METODO /caminho"; sem resposta na fila lanca erro. */
class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var offline = false
    val failing = mutableSetOf<String>()
    private val queues = mutableMapOf<String, ArrayDeque<HttpResponse>>()

    fun on(
        route: String,
        vararg responses: HttpResponse,
    ) {
        queues.getOrPut(route) { ArrayDeque() }.addAll(responses)
    }

    fun sent(route: String): List<HttpRequest> = requests.filter { key(it) == route }

    fun bodyOf(request: HttpRequest): JSONObject = JSONObject(String(request.body ?: ByteArray(0), Charsets.UTF_8))

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (offline || key(request) in failing) throw IOException("sem rede")
        return queues[key(request)]?.removeFirstOrNull() ?: error("sem resposta para ${key(request)}")
    }

    private fun key(request: HttpRequest) = "${request.method} ${request.url.substringAfter("/v1")}"
}

fun json(
    status: Int,
    body: String = "",
    headers: Map<String, String> = emptyMap(),
) = HttpResponse(status, body, headers)

fun problem(
    status: Int,
    detail: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpResponse {
    val extra = if (detail != null) ""","detail":"$detail"""" else ""
    return json(status, """{"type":"about:blank","title":"x","status":$status$extra}""", headers)
}

fun deviceJson(id: String) = """{"id":"$id","label":"A","created_at":"c","last_used_at":"u"}"""

const val ACCOUNT_JSON = """{"handle":"@ana","contacts_verified":{"phone":false,"email":false}}"""

const val ENROLL_JSON =
    """{"mfa_enrollment_required":true,"access_token":"e","expires_in":900,"scope":"mfa_enroll_only"}"""

const val MFA_JSON = """{"mfa_required":true,"mfa_token":"m-1","expires_in":300}"""

fun tokenBody(
    access: String,
    refresh: String,
) = """{"access_token":"$access","refresh_token":"$refresh","expires_in":900}"""

class TestEnv(
    captcha: String? = "captcha-ok",
) {
    val transport = FakeTransport()
    val secrets = InMemorySecretStore()
    val api = AuthApi("http://host:8080/", transport)
    val account = AccountApi("http://host:8080/", transport)
    val session = AuthSession(api, secrets, { captcha })
}
