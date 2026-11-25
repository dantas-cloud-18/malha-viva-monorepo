package br.malhaviva.lgpd

import br.malhaviva.auth.queryList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

private fun ResultSet.ts(column: String): JsonElement =
    getTimestamp(column)?.let { JsonPrimitive(it.toInstant().toString()) } ?: JsonNull

private fun ResultSet.str(column: String): JsonElement = getString(column)?.let { JsonPrimitive(it) } ?: JsonNull

/** Cadastro e contatos da conta (nunca hash de senha, papel ou segredo de admin). */
internal fun Connection.exportAccount(accountId: UUID): Pair<JsonObject, JsonObject> =
    queryList(
        "SELECT account_id, handle, created_at, age_bracket, status, phone_e164, phone_verified_at, " +
            "email, email_verified_at FROM accounts WHERE account_id = ?",
        accountId,
    ) { rs ->
        buildJsonObject {
            put("account_id", rs.getString("account_id"))
            put("handle", rs.getString("handle"))
            put("created_at", rs.ts("created_at"))
            put("age_bracket", rs.getString("age_bracket"))
            put("status", rs.getString("status"))
        } to
            buildJsonObject {
                put("phone", rs.str("phone_e164"))
                put("phone_verified_at", rs.ts("phone_verified_at"))
                put("email", rs.str("email"))
                put("email_verified_at", rs.ts("email_verified_at"))
            }
    }.first()

internal fun Connection.exportContactChanges(accountId: UUID): List<JsonObject> =
    queryList(
        "SELECT channel, new_value, effective_at FROM contact_changes " +
            "WHERE account_id = ? AND cancelled_at IS NULL AND applied_at IS NULL",
        accountId,
    ) { rs ->
        buildJsonObject {
            put("channel", rs.getString("channel").lowercase())
            put("new_value", rs.getString("new_value"))
            put("effective_at", rs.ts("effective_at"))
        }
    }

internal fun Connection.exportDevices(accountId: UUID): List<JsonObject> =
    queryList(
        "SELECT device_id, label, created_at, last_used_at, revoked_at FROM devices WHERE account_id = ? " +
            "ORDER BY created_at",
        accountId,
    ) { rs ->
        buildJsonObject {
            put("id", rs.getString("device_id"))
            put("label", rs.getString("label"))
            put("created_at", rs.ts("created_at"))
            put("last_used_at", rs.ts("last_used_at"))
            put("revoked_at", rs.ts("revoked_at"))
        }
    }

internal fun Connection.exportParental(accountId: UUID): JsonElement =
    queryList(
        "SELECT guardian_channel, guardian_value, requested_at, verified_at FROM parental_consents " +
            "WHERE account_id = ?",
        accountId,
    ) { rs ->
        buildJsonObject {
            put("guardian_channel", rs.getString("guardian_channel").lowercase())
            put("guardian_contact", rs.getString("guardian_value"))
            put("requested_at", rs.ts("requested_at"))
            put("verified_at", rs.ts("verified_at"))
        }
    }.firstOrNull() ?: JsonNull

internal fun Connection.exportAccessLogs(accountId: UUID): List<JsonObject> =
    queryList(
        "SELECT event, ip, occurred_at FROM access_logs WHERE account_id = ? ORDER BY log_id",
        accountId,
    ) { rs ->
        buildJsonObject {
            put("event", rs.getString("event"))
            put("ip", rs.getString("ip"))
            put("occurred_at", rs.ts("occurred_at"))
        }
    }
