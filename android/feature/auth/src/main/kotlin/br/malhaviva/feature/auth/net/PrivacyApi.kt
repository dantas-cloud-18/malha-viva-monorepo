package br.malhaviva.feature.auth.net

import br.malhaviva.core.data.net.HttpTransport
import org.json.JSONArray
import org.json.JSONObject

/** Rotas de privacidade (LGPD) de `/me`: consentimentos, exportacao, correcao e exclusao. Exigem access token. */
class PrivacyApi(
    baseUrl: String,
    transport: HttpTransport,
) {
    private val client = JsonClient(transport, baseUrl)

    suspend fun getConsents(accessToken: String): ApiResult<ConsentList> =
        client.call("GET", "/me/consents", accessToken, obj(::consentList))

    suspend fun grantConsent(
        accessToken: String,
        purpose: String,
        policyVersion: String,
    ): ApiResult<Unit> =
        client.call("PUT", "/me/consents/$purpose", accessToken, { }) { put("policy_version", policyVersion) }

    suspend fun revokeConsent(
        accessToken: String,
        purpose: String,
    ): ApiResult<Unit> = client.call("DELETE", "/me/consents/$purpose", accessToken, { })

    suspend fun consentHistory(accessToken: String): ApiResult<List<ConsentEvent>> =
        client.call("GET", "/me/consents/history", accessToken, ::consentEvents)

    /** JSON do export, como veio do servidor (so fica em memoria ate ser compartilhado). */
    suspend fun exportData(accessToken: String): ApiResult<String> =
        client.call("GET", "/me/export", accessToken, { JSONObject(it).toString(2) })

    /** Corrige o @nome; devolve a conta como ficou gravada. */
    suspend fun correctHandle(
        accessToken: String,
        handle: String,
    ): ApiResult<AccountInfo> =
        client.call(
            "PATCH",
            "/me",
            accessToken,
            obj {
                AccountInfo(it.getString("handle"), it.optString("age_bracket").ifEmpty { null })
            },
        ) { put("handle", handle) }

    suspend fun deleteAccount(
        accessToken: String,
        currentPassword: String,
    ): ApiResult<Unit> = client.call("DELETE", "/me", accessToken, { }) { put("current_password", currentPassword) }
}

private fun consentList(json: JSONObject): ConsentList {
    val array = json.getJSONArray("consents")
    return ConsentList(
        json.getString("policy_version"),
        (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Consent(item.getString("purpose"), item.getBoolean("granted"), item.optBoolean("needs_reconsent"))
        },
    )
}

private fun consentEvents(body: String): List<ConsentEvent> =
    JSONArray(body).let { array ->
        (0 until array.length()).map {
            val item = array.getJSONObject(it)
            ConsentEvent(
                item.getString("purpose"),
                item.getString("action"),
                item.getString("policy_version"),
                item.getString("occurred_at"),
            )
        }
    }
