package br.malhaviva.lgpd

import br.malhaviva.PostgresIntegrationTest
import br.malhaviva.tracking.ingest.PartitionMaintenance
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RetentionJobTest : PostgresIntegrationTest() {
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val config = RetentionConfig()

    private fun ago(d: Duration) = now.minus(d)

    private fun account(
        handle: String,
        status: String = "active",
        createdAt: Instant = now,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        scalars(
            "INSERT INTO accounts (account_id, handle, password_hash, status, created_at, deleted_at) " +
                "VALUES (?, ?, 'h', ?, ?, ?)",
            id,
            handle,
            status,
            java.sql.Timestamp.from(createdAt),
            deletedAt?.let { java.sql.Timestamp.from(it) },
        )
        return id
    }

    private fun count(sql: String) = scalars(sql).single() as Long

    @Test
    fun `removes only what passed its retention window`() {
        val keep = account("@mantida_1")
        val staleMinor = account("@menor_old", "pending_parental_consent", ago(Duration.ofDays(8)))
        val freshMinor = account("@menor_new", "pending_parental_consent", ago(Duration.ofDays(1)))
        val oldTomb = account("@del_aaaaaaaaaaaa", "deleted", ago(Duration.ofDays(90)), ago(Duration.ofDays(31)))
        val newTomb = account("@del_bbbbbbbbbbbb", "deleted", ago(Duration.ofDays(90)), ago(Duration.ofDays(2)))

        fun otp(
            expires: Instant,
            owner: UUID = keep,
        ) = scalars(
            "INSERT INTO otp_codes (account_id, purpose, channel, code_hmac, expires_at) " +
                "VALUES (?, 'RECOVERY', 'PHONE', 'h', ?)",
            owner,
            java.sql.Timestamp.from(expires),
        )
        otp(ago(Duration.ofMinutes(1)))
        otp(now.plusSeconds(60))

        fun log(at: Instant) =
            scalars(
                "INSERT INTO access_logs (account_id, event, ip, occurred_at) VALUES (?, 'login', '203.0.113.1', ?)",
                keep,
                java.sql.Timestamp.from(at),
            )
        log(ago(Duration.ofDays(184)))
        log(ago(Duration.ofDays(100)))

        val report = RetentionJob(ds, config) { now }.runOnce()

        assertEquals(1, report["pending_parental_accounts"])
        assertEquals(1, report["deleted_account_tombstones"])
        assertEquals(1, report["expired_otps"])
        assertEquals(1, report["access_logs"])
        assertEquals(1L, count("SELECT count(*) FROM otp_codes"))
        assertEquals(1L, count("SELECT count(*) FROM access_logs"))
        val survivors = scalars("SELECT account_id FROM accounts").toSet()
        assertEquals(setOf<Any?>(keep, freshMinor, newTomb), survivors)
        assertEquals(false, survivors.contains(staleMinor) || survivors.contains(oldTomb))
    }

    @Test
    fun `closed contact changes, revoked devices and expired refresh tokens go after the grace period`() {
        val acc = account("@limpa_001")
        val old = java.sql.Timestamp.from(ago(Duration.ofDays(40)))
        val recent = java.sql.Timestamp.from(ago(Duration.ofDays(2)))
        scalars(
            "INSERT INTO contact_changes " +
                "(change_id, account_id, channel, new_value, created_at, effective_at, applied_at) " +
                "VALUES (gen_random_uuid(), ?, 'EMAIL', 'a@b.test', ?, ?, ?)",
            acc,
            old,
            old,
            old,
        )
        scalars(
            "INSERT INTO contact_changes (change_id, account_id, channel, new_value, created_at, effective_at) " +
                "VALUES (gen_random_uuid(), ?, 'EMAIL', 'open@b.test', ?, ?)",
            acc,
            old,
            old,
        )
        val revoked = UUID.randomUUID()
        val active = UUID.randomUUID()
        scalars(
            "INSERT INTO devices (device_id, account_id, label, revoked_at) VALUES (?, ?, 'a', ?)",
            revoked,
            acc,
            old,
        )
        scalars(
            "INSERT INTO devices (device_id, account_id, label, revoked_at) VALUES (?, ?, 'b', ?)",
            active,
            acc,
            recent,
        )

        RetentionJob(ds, config) { now }.runOnce()

        assertEquals(1L, count("SELECT count(*) FROM contact_changes"), "troca aberta fica")
        assertEquals(listOf<Any?>(active), scalars("SELECT device_id FROM devices"))
    }

    @Test
    fun `config is read from the environment and never goes below the legal floors`() {
        val env =
            mapOf(
                "MALHAVIVA_RETENTION_ACCESS_LOG_DAYS" to "30",
                "MALHAVIVA_POSITION_TTL_HOURS" to "0",
                "MALHAVIVA_RETENTION_DELETED_ACCOUNT_DAYS" to "45",
                "MALHAVIVA_RETENTION_PENDING_PARENTAL_DAYS" to "lixo",
            )
        val c = RetentionConfig.fromEnv { env[it] }
        assertEquals(MIN_ACCESS_LOG_DAYS, c.accessLogDays, "Marco Civil: no minimo 6 meses")
        assertEquals(1, c.positionTtlHours)
        assertEquals(45, c.deletedAccountDays)
        assertEquals(7, c.pendingParentalDays)
        assertEquals(RetentionConfig(), RetentionConfig.fromEnv { null })
    }

    @Test
    fun `position TTL drops partitions older than the configured window`() {
        val old = Instant.now().minus(Duration.ofHours(30))
        val name =
            "telemetry_points_" +
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMddHH")
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(old)
        val from = old.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val to = from.plusSeconds(3600)
        scalars("CREATE TABLE IF NOT EXISTS $name PARTITION OF telemetry_points FOR VALUES FROM ('$from') TO ('$to')")
        assertEquals(1L, count("SELECT count(*) FROM pg_class WHERE relname = '$name'"))

        PartitionMaintenance(ds, retentionHours = 48).runOnce()
        assertEquals(1L, count("SELECT count(*) FROM pg_class WHERE relname = '$name'"), "dentro de 48 h: fica")
        PartitionMaintenance(ds, retentionHours = 24).runOnce()
        assertEquals(0L, count("SELECT count(*) FROM pg_class WHERE relname = '$name'"), "alem de 24 h: cai")
    }
}
