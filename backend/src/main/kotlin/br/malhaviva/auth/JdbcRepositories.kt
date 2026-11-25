package br.malhaviva.auth

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val INSERT_ACCOUNT =
    "INSERT INTO accounts (handle, password_hash) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING account_id"
private const val ACCOUNT_COLUMNS = "account_id, handle, password_hash, role"

private fun ResultSet.toAccount() =
    AccountRecord(uuid("account_id"), getString("handle"), getString("password_hash"), getString("role"))

class JdbcAccountRepository(
    private val dataSource: DataSource,
) : AccountRepository {
    override fun create(
        handle: String,
        passwordHash: String,
    ): UUID? =
        dataSource
            .read { it.queryList(INSERT_ACCOUNT, handle, passwordHash) { rs -> rs.uuid("account_id") } }
            .firstOrNull()

    override fun findByHandle(handle: String): AccountRecord? =
        dataSource
            .read {
                it.queryList("SELECT $ACCOUNT_COLUMNS FROM accounts WHERE lower(handle) = lower(?)", handle) { rs ->
                    rs.toAccount()
                }
            }.firstOrNull()

    override fun findById(id: UUID): AccountRecord? =
        dataSource
            .read {
                it.queryList("SELECT $ACCOUNT_COLUMNS FROM accounts WHERE account_id = ?", id) { rs -> rs.toAccount() }
            }.firstOrNull()

    override fun updatePasswordHash(
        accountId: UUID,
        passwordHash: String,
    ) {
        dataSource.read {
            it.update("UPDATE accounts SET password_hash = ? WHERE account_id = ?", passwordHash, accountId)
        }
    }

    override fun consumeAdminEnrollCode(
        accountId: UUID,
        codeHash: String,
    ): Boolean =
        dataSource.read {
            it.update(
                "UPDATE accounts SET admin_enroll_hash = NULL " +
                    "WHERE account_id = ? AND role = 'admin' AND admin_enroll_hash = ?",
                accountId,
                codeHash,
            )
        } == 1
}

private fun Connection.insertToken(t: RefreshRecord) =
    update(
        "INSERT INTO refresh_tokens (token_hash, account_id, family_id, issued_at, expires_at, mfa) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
        t.tokenHash,
        t.accountId,
        t.familyId,
        t.issuedAt,
        t.expiresAt,
        t.mfa,
    )

private fun Connection.revokeFamilyRows(
    familyId: UUID,
    now: Instant,
) {
    update("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, familyId)
    update("UPDATE devices SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL", now, familyId)
}

private fun ResultSet.toRefresh() =
    RefreshRecord(
        getString("token_hash"),
        uuid("account_id"),
        uuid("family_id"),
        instant("issued_at"),
        instant("expires_at"),
        instantOrNull("replaced_at"),
        instantOrNull("revoked_at"),
        getBoolean("mfa"),
    )

private fun ResultSet.toDevice(accountId: UUID) =
    DeviceRecord(uuid("device_id"), accountId, getString("label"), instant("created_at"), instant("last_used_at"))

class JdbcSessionRepository(
    private val dataSource: DataSource,
) : SessionRepository {
    override fun createDevice(
        device: DeviceRecord,
        firstToken: RefreshRecord,
    ) {
        dataSource.tx { c ->
            c.update(
                "INSERT INTO devices (device_id, account_id, label, created_at, last_used_at) VALUES (?, ?, ?, ?, ?)",
                device.id,
                device.accountId,
                device.label,
                device.createdAt,
                device.lastUsedAt,
            )
            c.insertToken(firstToken)
        }
    }

    override fun findToken(tokenHash: String): RefreshRecord? =
        dataSource
            .read {
                it.queryList(
                    "SELECT token_hash, account_id, family_id, issued_at, expires_at, replaced_at, revoked_at, mfa " +
                        "FROM refresh_tokens WHERE token_hash = ?",
                    tokenHash,
                ) { rs -> rs.toRefresh() }
            }.firstOrNull()

    override fun rotate(
        oldHash: String,
        next: RefreshRecord,
        now: Instant,
    ): Boolean =
        dataSource.tx { c ->
            val swapped =
                c.update(
                    "UPDATE refresh_tokens SET replaced_at = ? " +
                        "WHERE token_hash = ? AND replaced_at IS NULL AND revoked_at IS NULL",
                    now,
                    oldHash,
                ) == 1
            if (swapped) {
                c.insertToken(next)
                c.update("UPDATE devices SET last_used_at = ? WHERE device_id = ?", now, next.familyId)
            }
            swapped
        }

    override fun revokeFamily(
        familyId: UUID,
        now: Instant,
    ) {
        dataSource.tx { it.revokeFamilyRows(familyId, now) }
    }

    override fun revokeAll(
        accountId: UUID,
        now: Instant,
    ) {
        dataSource.tx { c ->
            c.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                now,
                accountId,
            )
            c.update("UPDATE devices SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL", now, accountId)
        }
    }

    override fun isFamilyActive(familyId: UUID): Boolean =
        dataSource.read {
            it.exists("SELECT 1 FROM devices WHERE device_id = ? AND revoked_at IS NULL", familyId)
        }

    override fun listDevices(accountId: UUID): List<DeviceRecord> =
        dataSource.read {
            it.queryList(
                "SELECT device_id, label, created_at, last_used_at FROM devices " +
                    "WHERE account_id = ? AND revoked_at IS NULL ORDER BY last_used_at DESC",
                accountId,
            ) { rs -> rs.toDevice(accountId) }
        }

    override fun revokeDevice(
        accountId: UUID,
        deviceId: UUID,
        now: Instant,
    ): Boolean =
        dataSource.tx { c ->
            val owned =
                c.exists(
                    "SELECT 1 FROM devices WHERE device_id = ? AND account_id = ? AND revoked_at IS NULL",
                    deviceId,
                    accountId,
                )
            if (owned) c.revokeFamilyRows(deviceId, now)
            owned
        }
}
