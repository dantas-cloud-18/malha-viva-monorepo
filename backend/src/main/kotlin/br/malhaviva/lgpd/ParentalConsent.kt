package br.malhaviva.lgpd

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.AccountStatus
import br.malhaviva.auth.queryList
import br.malhaviva.auth.read
import br.malhaviva.auth.recovery.Channel
import br.malhaviva.auth.recovery.MessageGateway
import br.malhaviva.auth.recovery.OtpPurpose
import br.malhaviva.auth.recovery.OtpService
import br.malhaviva.auth.recovery.SendContext
import br.malhaviva.auth.recovery.SendPurpose
import br.malhaviva.auth.tx
import br.malhaviva.auth.update
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class ParentalRecord(
    val guardian: Guardian,
    val requestedAt: Instant,
    val verifiedAt: Instant?,
)

interface ParentalConsentRepository {
    /** Grava (ou troca) o contato do responsavel e zera a verificacao. */
    fun upsert(
        accountId: UUID,
        guardian: Guardian,
        policyVersion: String,
        now: Instant,
    )

    fun find(accountId: UUID): ParentalRecord?

    /** Marca verificado e ativa a conta, juntos; false se a conta nao estava pendente. */
    fun activate(
        accountId: UUID,
        now: Instant,
    ): Boolean
}

class JdbcParentalConsentRepository(
    private val dataSource: DataSource,
) : ParentalConsentRepository {
    override fun upsert(
        accountId: UUID,
        guardian: Guardian,
        policyVersion: String,
        now: Instant,
    ) {
        dataSource.read {
            it.update(
                "INSERT INTO parental_consents " +
                    "(account_id, guardian_channel, guardian_value, policy_version, requested_at) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (account_id) " +
                    "DO UPDATE SET guardian_channel = EXCLUDED.guardian_channel, " +
                    "guardian_value = EXCLUDED.guardian_value, policy_version = EXCLUDED.policy_version, " +
                    "requested_at = EXCLUDED.requested_at, verified_at = NULL",
                accountId,
                guardian.channel.name,
                guardian.value,
                policyVersion,
                now,
            )
        }
    }

    override fun find(accountId: UUID): ParentalRecord? =
        dataSource
            .read {
                it.queryList(
                    "SELECT guardian_channel, guardian_value, requested_at, verified_at FROM parental_consents " +
                        "WHERE account_id = ?",
                    accountId,
                ) { rs ->
                    ParentalRecord(
                        Guardian(Channel.valueOf(rs.getString("guardian_channel")), rs.getString("guardian_value")),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("verified_at")?.toInstant(),
                    )
                }
            }.firstOrNull()

    override fun activate(
        accountId: UUID,
        now: Instant,
    ): Boolean =
        dataSource.tx { c ->
            val activated =
                c.update(
                    "UPDATE accounts SET status = 'active' " +
                        "WHERE account_id = ? AND status = 'pending_parental_consent'",
                    accountId,
                ) == 1
            if (activated) {
                c.update("UPDATE parental_consents SET verified_at = ? WHERE account_id = ?", now, accountId)
            }
            activated
        }
}

/**
 * Consentimento parental (LGPD art. 14, 1o) para menor de 12 anos: a conta nasce pendente, um OTP vai ao contato do
 * responsavel e, confirmado, a conta ativa. Limite do metodo: prova controle do contato, nao parentesco; verificacao
 * reforcada (documento, cobranca simbolica) e evolucao a decidir no RIPD.
 */
class ParentalConsentService(
    private val accounts: AccountRepository,
    private val repo: ParentalConsentRepository,
    private val otp: OtpService,
    private val gateway: MessageGateway,
    private val policyVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun now(): Instant = Instant.ofEpochMilli(clock())

    suspend fun start(
        accountId: UUID,
        guardian: Guardian,
        ip: String,
    ) {
        repo.upsert(accountId, guardian, policyVersion, now())
        send(accountId, guardian, ip)
    }

    /** Reenvia so se a conta existe e segue pendente; quem chama responde 202 igual nos dois casos. */
    suspend fun resend(
        handle: String,
        ip: String,
    ) {
        val account = accounts.findByHandle(handle)?.takeIf { it.status == AccountStatus.PENDING_PARENTAL_CONSENT }
        val record = account?.let { repo.find(it.id) }
        if (account != null && record != null) send(account.id, record.guardian, ip)
    }

    private suspend fun send(
        accountId: UUID,
        guardian: Guardian,
        ip: String,
    ) {
        val code = otp.issue(accountId, OtpPurpose.PARENTAL_CONSENT, guardian.channel, guardian.value)
        val text = "Malha Viva: codigo para autorizar a conta de uma crianca sob sua responsabilidade: $code"
        gateway.deliver(
            accountId,
            guardian.channel,
            guardian.value,
            "Codigo Malha Viva",
            text,
            SendContext(SendPurpose.SIGNUP, ip),
        )
    }

    /** True so com OTP correto numa conta pendente; erro, conta inexistente ou ja ativa dao o mesmo false. */
    fun confirm(
        handle: String,
        code: String,
    ): Boolean {
        val account = accounts.findByHandle(handle)?.takeIf { it.status == AccountStatus.PENDING_PARENTAL_CONSENT }
        val record = account?.let { repo.find(it.id) }
        if (account == null || record == null) {
            otp.probe(Channel.PHONE, code)
            return false
        }
        val otpRecord =
            otp.check(account.id, OtpPurpose.PARENTAL_CONSENT, record.guardian.channel, record.guardian.value, code)
        return otpRecord != null && otp.consume(otpRecord) && repo.activate(account.id, now())
    }
}
