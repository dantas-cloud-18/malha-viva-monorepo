package br.malhaviva.lgpd

import br.malhaviva.auth.read
import br.malhaviva.auth.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.lgpd.Retention")

/** Piso do Marco Civil art. 15: registros de acesso por 6 meses. */
const val MIN_ACCESS_LOG_DAYS = 183L

/** Prazos de retencao (LGPD art. 15 e 16); cada um configuravel por ambiente. */
data class RetentionConfig(
    /** Posicoes: cada ponto vive no maximo isto + 1 h (particoes por hora). */
    val positionTtlHours: Int = 24,
    val accessLogDays: Long = MIN_ACCESS_LOG_DAYS,
    /** Tumulo anonimo de conta excluida. */
    val deletedAccountDays: Long = 30,
    /** Conta de menor sem consentimento parental confirmado: minimizacao, apaga cedo. */
    val pendingParentalDays: Long = 7,
    /** Trocas de contato encerradas, dispositivos revogados e refresh vencidos. */
    val closedRecordDays: Long = 30,
    val intervalMinutes: Long = 60,
) {
    companion object {
        /** `MALHAVIVA_RETENTION_*`; invalido cai no padrao e o log de acesso nunca fica abaixo do piso legal. */
        fun fromEnv(env: (String) -> String?): RetentionConfig {
            val d = RetentionConfig()

            fun long(
                key: String,
                default: Long,
                min: Long,
            ) = (env(key)?.toLongOrNull() ?: default).coerceAtLeast(min)
            return RetentionConfig(
                positionTtlHours = long("MALHAVIVA_POSITION_TTL_HOURS", d.positionTtlHours.toLong(), 1).toInt(),
                accessLogDays = long("MALHAVIVA_RETENTION_ACCESS_LOG_DAYS", d.accessLogDays, MIN_ACCESS_LOG_DAYS),
                deletedAccountDays = long("MALHAVIVA_RETENTION_DELETED_ACCOUNT_DAYS", d.deletedAccountDays, 1),
                pendingParentalDays = long("MALHAVIVA_RETENTION_PENDING_PARENTAL_DAYS", d.pendingParentalDays, 1),
                closedRecordDays = long("MALHAVIVA_RETENTION_CLOSED_RECORD_DAYS", d.closedRecordDays, 1),
                intervalMinutes = long("MALHAVIVA_RETENTION_INTERVAL_MINUTES", d.intervalMinutes, 1),
            )
        }
    }
}

/**
 * Varredura periodica de retencao. Posicoes saem pelo [br.malhaviva.tracking.ingest.PartitionMaintenance] (mesmo TTL
 * em [RetentionConfig.positionTtlHours]); aqui: OTP vencido, trocas de contato encerradas, dispositivos revogados e
 * refresh vencidos, registros de acesso, contas pendentes de consentimento parental e tumulos de contas excluidas.
 * Cada passo e independente: falha em um nao impede os outros. O log traz so contagens (nunca dado pessoal).
 */
class RetentionJob(
    private val dataSource: DataSource,
    private val config: RetentionConfig = RetentionConfig(),
    private val clock: () -> Instant = Instant::now,
) {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "retention-job").also { it.isDaemon = true }
        }

    /** Apaga o que passou do prazo; devolve quantas linhas saíram por passo. */
    fun runOnce(now: Instant = clock()): Map<String, Int> {
        val closed = now.minus(Duration.ofDays(config.closedRecordDays))
        val steps =
            linkedMapOf(
                "pending_parental_accounts" to
                    (
                        "DELETE FROM accounts WHERE status = 'pending_parental_consent' AND created_at < ?" to
                            now.minus(Duration.ofDays(config.pendingParentalDays))
                    ),
                "deleted_account_tombstones" to
                    (
                        "DELETE FROM accounts WHERE status = 'deleted' AND deleted_at < ?" to
                            now.minus(Duration.ofDays(config.deletedAccountDays))
                    ),
                "expired_otps" to ("DELETE FROM otp_codes WHERE expires_at < ?" to now),
                "closed_contact_changes" to
                    ("DELETE FROM contact_changes WHERE COALESCE(applied_at, cancelled_at) < ?" to closed),
                "revoked_devices" to ("DELETE FROM devices WHERE revoked_at < ?" to closed),
                "expired_refresh_tokens" to ("DELETE FROM refresh_tokens WHERE expires_at < ?" to closed),
                "access_logs" to
                    (
                        "DELETE FROM access_logs WHERE occurred_at < ?" to
                            now.minus(Duration.ofDays(config.accessLogDays))
                    ),
            )
        return steps.mapValues { (name, step) ->
            runCatching { dataSource.read { it.update(step.first, step.second) } }
                .onFailure { log.error("Retencao falhou no passo {}: {}", name, it.javaClass.simpleName) }
                .getOrDefault(0)
        }
    }

    fun start() {
        scheduler.scheduleAtFixedRate({
            val report = runOnce().filterValues { it > 0 }
            if (report.isNotEmpty()) log.info("Retencao apagou: {}", report)
        }, 1, config.intervalMinutes, TimeUnit.MINUTES)
    }

    fun stop() {
        scheduler.shutdownNow()
    }
}
