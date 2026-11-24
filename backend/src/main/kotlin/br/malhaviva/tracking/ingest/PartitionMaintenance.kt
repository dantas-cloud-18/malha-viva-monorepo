package br.malhaviva.tracking.ingest

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Cria as próximas partições de telemetria e apaga as que passaram da retenção (24 h). */
class PartitionMaintenance(
    private val dataSource: DataSource,
    private val retentionHours: Int = 24,
) {
    private val log = LoggerFactory.getLogger(PartitionMaintenance::class.java)
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "partition-maintenance").also { it.isDaemon = true }
        }

    fun runOnce() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT telemetry_maintain_partitions(make_interval(hours => ?))").use {
                it.setInt(1, retentionHours)
                it.execute()
            }
            conn.prepareStatement("DELETE FROM telemetry_nonces WHERE expires_at < now()").use { it.execute() }
        }
    }

    fun start() {
        scheduler.scheduleAtFixedRate({
            runCatching { runOnce() }.onFailure { log.error("Partition maintenance failed", it) }
        }, 0, 15, TimeUnit.MINUTES)
    }

    fun stop() {
        scheduler.shutdownNow()
    }
}
