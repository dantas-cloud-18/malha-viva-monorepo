package br.malhaviva.feature.auth.net

import br.malhaviva.core.data.net.HttpTransport
import org.json.JSONArray
import org.json.JSONObject

/** Rotas de `/me` (exigem access token). Use por meio de `AuthSession.authed`, que renova o token em 401. */
class AccountApi(
    baseUrl: String,
    transport: HttpTransport,
) {
    private val client = JsonClient(transport, baseUrl)

    suspend fun listDevices(accessToken: String): ApiResult<List<Device>> =
        client.call("GET", "/me/devices", accessToken, ::devices)

    suspend fun revokeDevice(
        accessToken: String,
        deviceId: String,
    ): ApiResult<Unit> = client.call("DELETE", "/me/devices/$deviceId", accessToken, { })

    suspend fun getContacts(accessToken: String): ApiResult<ContactsState> =
        client.call("GET", "/me/contacts", accessToken, obj(::contactsState))

    /** `currentPassword` e obrigatoria em toda operacao: um access token roubado nao basta. */
    suspend fun setContact(
        accessToken: String,
        channel: String,
        value: String,
        currentPassword: String,
    ): ApiResult<ContactChangeStarted> =
        client.call("POST", "/me/contacts", accessToken, obj(::contactChange)) {
            put("channel", channel)
            put("value", value)
            put("current_password", currentPassword)
        }

    suspend fun confirmContact(
        accessToken: String,
        channel: String,
        code: String,
        changeId: String? = null,
        currentPassword: String? = null,
    ): ApiResult<Unit> =
        client.call("POST", "/me/contacts/confirm", accessToken, { }) {
            put("channel", channel)
            put("code", code)
            changeId?.let { put("change_id", it) }
            currentPassword?.let { put("current_password", it) }
        }

    suspend fun cancelContactChange(
        accessToken: String,
        changeId: String,
    ): ApiResult<Unit> = client.call("DELETE", "/me/contact-changes/$changeId", accessToken, { })

    suspend fun startTotp(
        accessToken: String,
        enrollmentCode: String? = null,
    ): ApiResult<TotpEnrollment> =
        client.call("POST", "/me/mfa/totp", accessToken, obj(::totpEnrollment)) {
            enrollmentCode?.let { put("enrollment_code", it) }
        }

    /** Devolve os 10 codigos de backup, em claro so desta vez. */
    suspend fun confirmTotp(
        accessToken: String,
        code: String,
    ): ApiResult<List<String>> =
        client.call("POST", "/me/mfa/totp/confirm", accessToken, obj(::backupCodes)) { put("code", code) }

    /** Senha mais exatamente um entre `code` (TOTP) e `backupCode`; devolve um lote novo de 10 codigos. */
    suspend fun regenerateBackupCodes(
        accessToken: String,
        password: String,
        code: String? = null,
        backupCode: String? = null,
    ): ApiResult<List<String>> =
        client.call("POST", "/me/mfa/totp/backup-codes", accessToken, obj(::backupCodes)) {
            put("password", password)
            code?.let { put("code", it) }
            backupCode?.let { put("backup_code", it) }
        }

    suspend fun disableTotp(
        accessToken: String,
        password: String,
        code: String,
    ): ApiResult<Unit> =
        client.call("POST", "/me/mfa/totp/disable", accessToken, { }) {
            put("password", password)
            put("code", code)
        }
}

private fun devices(body: String): List<Device> =
    JSONArray(body).let { array ->
        (0 until array.length()).map {
            val json = array.getJSONObject(it)
            Device(
                json.getString("id"),
                json.getString("label"),
                json.getString("created_at"),
                json.getString("last_used_at"),
            )
        }
    }

private fun contactsState(json: JSONObject): ContactsState {
    val verified = json.getJSONObject("contacts_verified")
    val changes = json.getJSONArray("pending_changes")
    return ContactsState(
        phoneVerified = verified.getBoolean("phone"),
        emailVerified = verified.getBoolean("email"),
        pendingChanges =
            (0 until changes.length()).map {
                val change = changes.getJSONObject(it)
                PendingChange(
                    change.getString("id"),
                    change.getString("channel"),
                    change.getString("effective_at"),
                    change.getBoolean("confirmed"),
                )
            },
    )
}

private fun contactChange(json: JSONObject) =
    ContactChangeStarted(
        json.getString("status"),
        json.optString("change_id").ifEmpty { null },
        json.optString("effective_at").ifEmpty { null },
    )

private fun totpEnrollment(json: JSONObject) = TotpEnrollment(json.getString("secret"), json.getString("otpauth_uri"))

private fun backupCodes(json: JSONObject): List<String> =
    json.getJSONArray("backup_codes").let { array -> (0 until array.length()).map { array.getString(it) } }
