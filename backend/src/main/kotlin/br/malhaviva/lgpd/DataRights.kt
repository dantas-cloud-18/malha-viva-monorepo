package br.malhaviva.lgpd

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.Gender
import br.malhaviva.auth.PasswordHasher
import br.malhaviva.auth.exists
import br.malhaviva.auth.isValidHandle
import br.malhaviva.auth.normalizePassword
import br.malhaviva.auth.read
import br.malhaviva.auth.tx
import br.malhaviva.auth.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val UNIQUE_VIOLATION = "23505"

/** Registros de acesso (Marco Civil art. 15). Sem FK: sobrevivem a exclusao da conta ate o fim do prazo legal. */
class AccessLogRepository(
    private val dataSource: DataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun record(
        accountId: UUID,
        event: String,
        ip: String,
    ) {
        dataSource.read {
            it.update(
                "INSERT INTO access_logs (account_id, event, ip, occurred_at) VALUES (?, ?, ?, ?)",
                accountId,
                event,
                ip,
                Instant.ofEpochMilli(clock()),
            )
        }
    }
}

sealed interface RenameResult {
    data object Done : RenameResult

    data object Invalid : RenameResult

    data object Taken : RenameResult
}

/**
 * Direitos do titular (LGPD art. 18): exportar, corrigir e excluir. A exclusao e uma transacao so: apaga tudo que
 * identifica a pessoa e deixa um tumulo anonimo (id, datas) que a rotina de retencao remove depois. Os registros de
 * acesso ficam ate o prazo do Marco Civil (art. 16, I da LGPD). A telemetria e anonima e nunca esteve ligada a conta.
 */
class DataRightsService(
    private val dataSource: DataSource,
    private val accounts: AccountRepository,
    private val hasher: PasswordHasher,
    private val consents: ConsentService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun now(): Instant = Instant.ofEpochMilli(clock())

    fun export(accountId: UUID): JsonObject =
        dataSource.read { c ->
            val (account, contacts) = c.exportAccount(accountId)
            val totp = c.exists("SELECT 1 FROM totp_secrets WHERE account_id = ? AND enabled_at IS NOT NULL", accountId)
            buildJsonObject {
                put("generated_at", now().toString())
                put("account", JsonObject(account + ("totp_enabled" to JsonPrimitive(totp))))
                put(
                    "contacts",
                    JsonObject(
                        contacts + ("pending_changes" to JsonArray(c.exportContactChanges(accountId))),
                    ),
                )
                put("devices", JsonArray(c.exportDevices(accountId)))
                put("consents", JsonArray(consents.list(accountId).map { consentJson(it) }))
                put("consent_history", JsonArray(consents.history(accountId).map { eventJson(it) }))
                put("parental_consent", c.exportParental(accountId))
                put("access_logs", JsonArray(c.exportAccessLogs(accountId)))
            }
        }

    fun eventJson(e: ConsentEvent): JsonObject =
        buildJsonObject {
            put("purpose", e.purpose.wire)
            put("action", e.action)
            put("policy_version", e.policyVersion)
            put("occurred_at", e.occurredAt.toString())
        }

    fun consentJson(s: ConsentState): JsonObject =
        buildJsonObject {
            put("purpose", s.purpose.wire)
            put("granted", s.granted)
            s.policyVersion?.let { put("policy_version", it) }
            s.grantedAt?.let { put("granted_at", it.toString()) }
            s.revokedAt?.let { put("revoked_at", it.toString()) }
            put("current_policy_version", consents.policyVersion)
            put("needs_reconsent", s.granted && s.policyVersion != consents.policyVersion)
        }

    fun rename(
        accountId: UUID,
        handle: String,
    ): RenameResult =
        if (!isValidHandle(handle)) {
            RenameResult.Invalid
        } else {
            try {
                dataSource.read {
                    it.update(
                        "UPDATE accounts SET handle = ? WHERE account_id = ? AND status <> 'deleted'",
                        handle,
                        accountId,
                    )
                }
                RenameResult.Done
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) RenameResult.Taken else throw e
            }
        }

    /** Define ou apaga ([gender] null) o genero opcional; nao reabre conta excluida. */
    fun setGender(
        accountId: UUID,
        gender: Gender?,
    ) {
        dataSource.read {
            it.update(
                "UPDATE accounts SET gender = NULLIF(?, '') WHERE account_id = ? AND status <> 'deleted'",
                gender?.wire.orEmpty(),
                accountId,
            )
        }
    }

    fun passwordMatches(
        accountId: UUID,
        password: String?,
    ): Boolean {
        val hash = accounts.findById(accountId)?.passwordHash
        return password != null && hash != null && hasher.verify(normalizePassword(password), hash)
    }

    /** Apaga e anonimiza numa transacao. A senha ja foi conferida por quem chama. */
    fun erase(accountId: UUID) {
        dataSource.tx { c ->
            listOf(
                "refresh_tokens",
                "devices",
                "otp_codes",
                "contact_changes",
                "totp_secrets",
                "totp_backup_codes",
                "consents",
                "consent_events",
                "parental_consents",
            ).forEach { table -> c.update("DELETE FROM $table WHERE account_id = ?", accountId) }
            c.update(
                "UPDATE accounts SET handle = '@del_' || substr(md5(random()::text || account_id::text), 1, 12), " +
                    "password_hash = '!', phone_e164 = NULL, email = NULL, phone_verified_at = NULL, " +
                    "email_verified_at = NULL, role = 'user', admin_enroll_hash = NULL, gender = NULL, " +
                    "status = 'deleted', deleted_at = ? WHERE account_id = ? AND status <> 'deleted'",
                now(),
                accountId,
            )
        }
    }
}
