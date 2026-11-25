package br.malhaviva.feature.auth.net

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpResponse
import br.malhaviva.core.data.net.HttpTransport
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

private val SUCCESS = 200..299
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_NOT_FOUND = 404
private const val STATUS_CONFLICT = 409
private const val STATUS_TOO_LARGE = 413
private const val STATUS_TOO_MANY = 429

/**
 * Cliente JSON do backend. Falha de rede ou corpo ilegivel viram [AuthError.Network]; erros HTTP
 * viram [AuthError] tipado (o codigo do servidor vem no campo `detail` do problem+json).
 */
internal class JsonClient(
    private val transport: HttpTransport,
    baseUrl: String,
) {
    private val base = baseUrl.trimEnd('/') + "/v1"

    suspend fun <T> call(
        method: String,
        path: String,
        accessToken: String?,
        parse: (String) -> T,
        body: (JSONObject.() -> Unit)? = null,
    ): ApiResult<T> {
        val headers = mutableMapOf<String, String>()
        accessToken?.let { headers["Authorization"] = "Bearer $it" }
        val payload =
            body?.let {
                headers["Content-Type"] = "application/json"
                JSONObject().apply(it).toString().toByteArray(Charsets.UTF_8)
            }
        return try {
            val response = transport.execute(HttpRequest(method, base + path, headers, payload))
            if (response.status in SUCCESS) ApiResult.Ok(parse(response.body)) else ApiResult.Fail(response.toError())
        } catch (_: IOException) {
            ApiResult.Fail(AuthError.Network)
        } catch (_: JSONException) {
            ApiResult.Fail(AuthError.Network)
        }
    }
}

internal fun <T> obj(parse: (JSONObject) -> T): (String) -> T = { parse(JSONObject(it)) }

internal fun HttpResponse.toError(): AuthError {
    val code = runCatching { JSONObject(body).optString("detail").ifEmpty { null } }.getOrNull()
    return when (status) {
        STATUS_TOO_MANY -> AuthError.RateLimited(headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
        STATUS_TOO_LARGE -> AuthError.PayloadTooLarge
        STATUS_UNAUTHORIZED -> if (code == "invalid_mfa") AuthError.InvalidMfa else AuthError.Unauthorized
        STATUS_FORBIDDEN ->
            when (code) {
                "invalid_password" -> AuthError.InvalidPassword
                "captcha_required" -> AuthError.CaptchaRequired
                "admin_mfa_required" -> AuthError.AdminMfaRequired
                else -> AuthError.Rejected(status, code)
            }
        STATUS_NOT_FOUND -> AuthError.NotFound
        STATUS_CONFLICT -> AuthError.Conflict
        else -> AuthError.Rejected(status, code)
    }
}
