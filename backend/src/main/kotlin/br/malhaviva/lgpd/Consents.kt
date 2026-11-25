package br.malhaviva.lgpd

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.AgeBracket
import br.malhaviva.auth.instant
import br.malhaviva.auth.instantOrNull
import br.malhaviva.auth.queryList
import br.malhaviva.auth.read
import br.malhaviva.auth.tx
import br.malhaviva.auth.update
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class ConsentPurpose(
    val wire: String,
) {
    LOCATION("location"),
    MARKETING("marketing"),
    ;

    companion object {
        fun fromWire(value: String?): ConsentPurpose? = entries.firstOrNull { it.wire == value }
    }
}

data class ConsentState(
    val purpose: ConsentPurpose,
    val granted: Boolean,
    val policyVersion: String? = null,
    val grantedAt: Instant? = null,
    val revokedAt: Instant? = null,
)

data class ConsentEvent(
    val purpose: ConsentPurpose,
    val action: String,
    val policyVersion: String,
    val occurredAt: Instant,
)

/** Registro de consentimento: so identidade da conta e finalidade; jamais posicao, telemetria ou epoca anonima. */
interface ConsentRepository {
    fun states(accountId: UUID): List<ConsentState>

    /** Do mais recente ao mais antigo. */
    fun history(accountId: UUID): List<ConsentEvent>

    /** True se algo mudou (novo evento); repetir a mesma versao ja concedida e no-op. */
    fun grant(
        accountId: UUID,
        purpose: ConsentPurpose,
        policyVersion: String,
        now: Instant,
    ): Boolean

    /** True se havia consentimento ativo e foi revogado; sem ativo e no-op. */
    fun revoke(
        accountId: UUID,
        purpose: ConsentPurpose,
        now: Instant,
    ): Boolean
}

private const val UPSERT_CONSENT =
    "INSERT INTO consents (account_id, purpose, policy_version, granted_at) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (account_id, purpose) DO UPDATE " +
        "SET policy_version = EXCLUDED.policy_version, granted_at = EXCLUDED.granted_at, revoked_at = NULL " +
        "WHERE consents.revoked_at IS NOT NULL OR consents.policy_version <> EXCLUDED.policy_version"
private const val INSERT_EVENT =
    "INSERT INTO consent_events (account_id, purpose, action, policy_version, occurred_at) VALUES (?, ?, ?, ?, ?)"

class JdbcConsentRepository(
    private val dataSource: DataSource,
) : ConsentRepository {
    override fun states(accountId: UUID): List<ConsentState> =
        dataSource.read {
            it.queryList(
                "SELECT purpose, policy_version, granted_at, revoked_at FROM consents WHERE account_id = ?",
                accountId,
            ) { rs ->
                ConsentState(
                    ConsentPurpose.fromWire(rs.getString("purpose"))!!,
                    rs.getTimestamp("revoked_at") == null,
                    rs.getString("policy_version"),
                    rs.instant("granted_at"),
                    rs.instantOrNull("revoked_at"),
                )
            }
        }

    override fun history(accountId: UUID): List<ConsentEvent> =
        dataSource.read {
            it.queryList(
                "SELECT purpose, action, policy_version, occurred_at FROM consent_events " +
                    "WHERE account_id = ? ORDER BY event_id DESC",
                accountId,
            ) { rs ->
                ConsentEvent(
                    ConsentPurpose.fromWire(rs.getString("purpose"))!!,
                    rs.getString("action"),
                    rs.getString("policy_version"),
                    rs.instant("occurred_at"),
                )
            }
        }

    override fun grant(
        accountId: UUID,
        purpose: ConsentPurpose,
        policyVersion: String,
        now: Instant,
    ): Boolean =
        dataSource.tx { c ->
            val changed = c.update(UPSERT_CONSENT, accountId, purpose.wire, policyVersion, now) == 1
            if (changed) c.update(INSERT_EVENT, accountId, purpose.wire, "granted", policyVersion, now)
            changed
        }

    override fun revoke(
        accountId: UUID,
        purpose: ConsentPurpose,
        now: Instant,
    ): Boolean =
        dataSource.tx { c ->
            val versions =
                c.queryList(
                    "UPDATE consents SET revoked_at = ? WHERE account_id = ? AND purpose = ? AND revoked_at IS NULL " +
                        "RETURNING policy_version",
                    now,
                    accountId,
                    purpose.wire,
                ) { rs -> rs.getString("policy_version") }
            versions.firstOrNull()?.let { c.update(INSERT_EVENT, accountId, purpose.wire, "revoked", it, now) }
            versions.isNotEmpty()
        }
}

sealed interface GrantResult {
    data class Granted(
        val state: ConsentState,
    ) : GrantResult

    data object PolicyMismatch : GrantResult

    data object MarketingForbiddenForMinors : GrantResult
}

/**
 * Regras do consentimento. Nada comeca concedido (opt-in explicito). `marketing` e proibido a menores de 18
 * (LGPD art. 14, ECA Digital). Revogar `location` so registra o evento: conta, sessoes e a ingestao anonima
 * seguem como estavam, porque nada aqui conhece a telemetria.
 */
class ConsentService(
    private val repo: ConsentRepository,
    private val accounts: AccountRepository,
    val policyVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun now(): Instant = Instant.ofEpochMilli(clock())

    fun list(accountId: UUID): List<ConsentState> {
        val byPurpose = repo.states(accountId).associateBy { it.purpose }
        return ConsentPurpose.entries.map { byPurpose[it] ?: ConsentState(it, granted = false) }
    }

    fun history(accountId: UUID): List<ConsentEvent> = repo.history(accountId)

    fun grant(
        accountId: UUID,
        purpose: ConsentPurpose,
        version: String,
    ): GrantResult {
        val minor = accounts.findById(accountId)?.ageBracket != AgeBracket.ADULT
        return when {
            version != policyVersion -> GrantResult.PolicyMismatch
            purpose == ConsentPurpose.MARKETING && minor -> GrantResult.MarketingForbiddenForMinors
            else -> {
                repo.grant(accountId, purpose, version, now())
                GrantResult.Granted(list(accountId).first { it.purpose == purpose })
            }
        }
    }

    fun revoke(
        accountId: UUID,
        purpose: ConsentPurpose,
    ) {
        repo.revoke(accountId, purpose, now())
    }
}
