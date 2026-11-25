package br.malhaviva.auth.recovery

import br.malhaviva.auth.instant
import br.malhaviva.auth.instantOrNull
import br.malhaviva.auth.queryList
import br.malhaviva.auth.read
import br.malhaviva.auth.tx
import br.malhaviva.auth.update
import br.malhaviva.auth.uuid
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val UNIQUE_VIOLATION = "23505"
private const val OTP_COLUMNS =
    "otp_id, account_id, purpose, channel, code_hmac, expires_at, attempts, consumed_at, created_at"
private const val CHANGE_COLUMNS =
    "change_id, account_id, channel, new_value, created_at, effective_at, confirmed_at, cancelled_at, applied_at"
private const val OPEN_CHANGE = "cancelled_at IS NULL AND applied_at IS NULL"

/** Contatos nas colunas de `accounts` (V3). Nao testado contra Postgres real nesta fase (sem Docker). */
class JdbcContactRepository(
    private val dataSource: DataSource,
) : ContactRepository {
    override fun find(accountId: UUID): ContactInfo =
        dataSource
            .read {
                it.queryList(
                    "SELECT phone_e164, phone_verified_at, email, email_verified_at FROM accounts WHERE account_id = ?",
                    accountId,
                ) { rs ->
                    ContactInfo(
                        rs.getString("phone_e164"),
                        rs.getTimestamp("phone_verified_at") != null,
                        rs.getString("email"),
                        rs.getTimestamp("email_verified_at") != null,
                    )
                }
            }.firstOrNull() ?: ContactInfo()

    override fun store(
        accountId: UUID,
        channel: Channel,
        value: String,
    ) {
        val sql =
            when (channel) {
                Channel.PHONE -> "UPDATE accounts SET phone_e164 = ?, phone_verified_at = NULL WHERE account_id = ?"
                Channel.EMAIL -> "UPDATE accounts SET email = ?, email_verified_at = NULL WHERE account_id = ?"
            }
        dataSource.read { it.update(sql, value, accountId) }
    }

    override fun setVerified(
        accountId: UUID,
        channel: Channel,
        value: String,
        now: Instant,
    ): Boolean {
        val sql =
            when (channel) {
                Channel.PHONE -> "UPDATE accounts SET phone_e164 = ?, phone_verified_at = ? WHERE account_id = ?"
                Channel.EMAIL -> "UPDATE accounts SET email = ?, email_verified_at = ? WHERE account_id = ?"
            }
        return try {
            dataSource.read { it.update(sql, value, now, accountId) } == 1
        } catch (e: SQLException) {
            // contato ja verificado por outra conta (indice parcial unico do V5)
            if (e.sqlState == UNIQUE_VIOLATION) false else throw e
        }
    }
}

private fun ResultSet.toOtp() =
    OtpRecord(
        uuid("otp_id"),
        uuid("account_id"),
        OtpPurpose.valueOf(getString("purpose")),
        Channel.valueOf(getString("channel")),
        getString("code_hmac"),
        instant("expires_at"),
        getInt("attempts"),
        instantOrNull("consumed_at"),
        instant("created_at"),
    )

class JdbcOtpRepository(
    private val dataSource: DataSource,
) : OtpRepository {
    override fun replace(record: OtpRecord) {
        dataSource.tx { c ->
            c.update(
                "UPDATE otp_codes SET consumed_at = ? " +
                    "WHERE account_id = ? AND purpose = ? AND channel = ? AND consumed_at IS NULL",
                record.createdAt,
                record.accountId,
                record.purpose.name,
                record.channel.name,
            )
            c.update(
                "INSERT INTO otp_codes ($OTP_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                record.id,
                record.accountId,
                record.purpose.name,
                record.channel.name,
                record.codeHmac,
                record.expiresAt,
                record.attempts,
                record.createdAt,
            )
        }
    }

    override fun latest(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
    ): OtpRecord? =
        dataSource
            .read {
                it.queryList(
                    "SELECT $OTP_COLUMNS FROM otp_codes WHERE account_id = ? AND purpose = ? AND channel = ? " +
                        "ORDER BY created_at DESC LIMIT 1",
                    accountId,
                    purpose.name,
                    channel.name,
                ) { rs -> rs.toOtp() }
            }.firstOrNull()

    override fun addAttempt(id: UUID): Int =
        dataSource
            .read {
                it.queryList(
                    "UPDATE otp_codes SET attempts = attempts + 1 WHERE otp_id = ? RETURNING attempts",
                    id,
                ) { rs ->
                    rs.getInt(1)
                }
            }.firstOrNull() ?: Int.MAX_VALUE

    override fun consume(
        id: UUID,
        now: Instant,
    ): Boolean =
        dataSource.read {
            it.update("UPDATE otp_codes SET consumed_at = ? WHERE otp_id = ? AND consumed_at IS NULL", now, id)
        } == 1
}

private fun ResultSet.toChange() =
    ContactChange(
        uuid("change_id"),
        uuid("account_id"),
        Channel.valueOf(getString("channel")),
        getString("new_value"),
        instant("created_at"),
        instant("effective_at"),
        instantOrNull("confirmed_at"),
        instantOrNull("cancelled_at"),
        instantOrNull("applied_at"),
    )

class JdbcContactChangeRepository(
    private val dataSource: DataSource,
) : ContactChangeRepository {
    override fun create(change: ContactChange) {
        dataSource.read {
            it.update(
                "INSERT INTO contact_changes (change_id, account_id, channel, new_value, created_at, effective_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                change.id,
                change.accountId,
                change.channel.name,
                change.newValue,
                change.createdAt,
                change.effectiveAt,
            )
        }
    }

    override fun find(id: UUID): ContactChange? =
        dataSource
            .read {
                it.queryList(
                    "SELECT $CHANGE_COLUMNS FROM contact_changes WHERE change_id = ?",
                    id,
                ) { rs -> rs.toChange() }
            }.firstOrNull()

    override fun open(accountId: UUID): List<ContactChange> =
        dataSource.read {
            it.queryList(
                "SELECT $CHANGE_COLUMNS FROM contact_changes WHERE account_id = ? AND $OPEN_CHANGE ORDER BY created_at",
                accountId,
            ) { rs -> rs.toChange() }
        }

    override fun markConfirmed(
        id: UUID,
        now: Instant,
    ) {
        dataSource.read { it.update("UPDATE contact_changes SET confirmed_at = ? WHERE change_id = ?", now, id) }
    }

    override fun markApplied(
        id: UUID,
        now: Instant,
    ) {
        dataSource.read { it.update("UPDATE contact_changes SET applied_at = ? WHERE change_id = ?", now, id) }
    }

    override fun cancel(
        accountId: UUID,
        channel: Channel?,
        now: Instant,
    ): Int =
        dataSource.read {
            if (channel == null) {
                it.update(
                    "UPDATE contact_changes SET cancelled_at = ? WHERE account_id = ? AND $OPEN_CHANGE",
                    now,
                    accountId,
                )
            } else {
                it.update(
                    "UPDATE contact_changes SET cancelled_at = ? WHERE account_id = ? AND channel = ? AND $OPEN_CHANGE",
                    now,
                    accountId,
                    channel.name,
                )
            }
        }

    override fun cancelOne(
        accountId: UUID,
        id: UUID,
        now: Instant,
    ): Boolean =
        dataSource.read {
            it.update(
                "UPDATE contact_changes SET cancelled_at = ? WHERE change_id = ? AND account_id = ? AND $OPEN_CHANGE",
                now,
                id,
                accountId,
            )
        } == 1
}

class JdbcTotpRepository(
    private val dataSource: DataSource,
) : TotpRepository {
    override fun find(accountId: UUID): TotpRecord? =
        dataSource
            .read {
                it.queryList(
                    "SELECT secret_enc, enabled_at, last_step FROM totp_secrets WHERE account_id = ?",
                    accountId,
                ) { rs ->
                    TotpRecord(
                        rs.getBytes("secret_enc"),
                        rs.getTimestamp("enabled_at") != null,
                        rs.getLong("last_step"),
                    )
                }
            }.firstOrNull()

    override fun save(
        accountId: UUID,
        secretEnc: ByteArray,
    ) {
        dataSource.read {
            it.update(
                "INSERT INTO totp_secrets (account_id, secret_enc) VALUES (?, ?) ON CONFLICT (account_id) DO UPDATE " +
                    "SET secret_enc = EXCLUDED.secret_enc, last_step = 0 WHERE totp_secrets.enabled_at IS NULL",
                accountId,
                secretEnc,
            )
        }
    }

    override fun enable(
        accountId: UUID,
        step: Long,
    ) {
        dataSource.read {
            it.update("UPDATE totp_secrets SET enabled_at = now(), last_step = ? WHERE account_id = ?", step, accountId)
        }
    }

    override fun advance(
        accountId: UUID,
        expectedLast: Long,
        step: Long,
    ): Boolean =
        dataSource.read {
            it.update(
                "UPDATE totp_secrets SET last_step = ? " +
                    "WHERE account_id = ? AND last_step = ? AND enabled_at IS NOT NULL",
                step,
                accountId,
                expectedLast,
            )
        } == 1

    override fun delete(accountId: UUID) {
        dataSource.read { it.update("DELETE FROM totp_secrets WHERE account_id = ?", accountId) }
    }
}
