package br.malhaviva.auth.recovery

import br.malhaviva.auth.queryList
import br.malhaviva.auth.read
import br.malhaviva.auth.tx
import br.malhaviva.auth.update
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcBackupCodeRepository(
    private val dataSource: DataSource,
) : BackupCodeRepository {
    override fun replaceAll(
        accountId: UUID,
        hashes: List<String>,
        now: Instant,
    ) {
        dataSource.tx { c ->
            // serializa regeneracoes concorrentes da mesma conta: sem isso dois lotes poderiam coexistir
            c.queryList("SELECT 1 FROM accounts WHERE account_id = ? FOR UPDATE", accountId) { }
            c.update("DELETE FROM totp_backup_codes WHERE account_id = ?", accountId)
            hashes.forEach {
                c.update(
                    "INSERT INTO totp_backup_codes (account_id, code_hmac, created_at) VALUES (?, ?, ?)",
                    accountId,
                    it,
                    now,
                )
            }
        }
    }

    override fun unusedHashes(accountId: UUID): List<String> =
        dataSource.read {
            it.queryList(
                "SELECT code_hmac FROM totp_backup_codes WHERE account_id = ? AND used_at IS NULL",
                accountId,
            ) { rs -> rs.getString(1) }
        }

    override fun consume(
        accountId: UUID,
        hash: String,
        now: Instant,
    ): Boolean =
        dataSource.read {
            it.update(
                "UPDATE totp_backup_codes SET used_at = ? WHERE account_id = ? AND code_hmac = ? AND used_at IS NULL",
                now,
                accountId,
                hash,
            )
        } == 1

    override fun deleteAll(accountId: UUID) {
        dataSource.read { it.update("DELETE FROM totp_backup_codes WHERE account_id = ?", accountId) }
    }
}
