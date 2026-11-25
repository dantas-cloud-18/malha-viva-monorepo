package br.malhaviva.tracking.ingest

import br.malhaviva.PostgresIntegrationTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CORRIDOR = "RAMAL_SANTA_CRUZ"
private const val RETRY_MS = 30_000L

// Central -> Madureira -> Santa Cruz (lat, lon); o fim do trecho 1 e o inicio do 2
private val TRECHO_1 = listOf(-22.9035 to -43.1909, -22.8800 to -43.3000, -22.8700 to -43.3400)
private val TRECHO_2 = listOf(-22.8700 to -43.3400, -22.9000 to -43.5000, -22.9100 to -43.6800)

/** Geometria do corredor, particoes de telemetria e migracoes, contra PostGIS 17 de verdade. */
class JdbcTrackingIntegrationTest : PostgresIntegrationTest() {
    private fun wkt(points: List<Pair<Double, Double>>) =
        "LINESTRING(" + points.joinToString(", ") { "${it.second} ${it.first}" } + ")"

    private fun seedCorridor() {
        scalars("INSERT INTO corridors (corridor_id, name) VALUES (?, 'Ramal Santa Cruz')", CORRIDOR)
        listOf(
            Triple("CENTRAL", TRECHO_1.first(), 0),
            Triple("MADUREIRA", TRECHO_1.last(), 10),
            Triple("SANTA_CRUZ", TRECHO_2.last(), 20),
        ).forEachIndexed { i, (id, p, km) ->
            scalars(
                "INSERT INTO stations (station_id, corridor_id, name, seq_order, km, platform) " +
                    "VALUES (?, ?, ?, ?, ?, ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326), 0.0005))",
                id,
                CORRIDOR,
                id,
                i + 1,
                km,
                p.second,
                p.first,
            )
        }
        listOf(
            Triple("S1", "CENTRAL" to "MADUREIRA", TRECHO_1) to (0 to 10),
            Triple("S2", "MADUREIRA" to "SANTA_CRUZ", TRECHO_2) to (10 to 20),
        ).forEach { (seg, km) ->
            scalars(
                "INSERT INTO track_segments " +
                    "(segment_id, corridor_id, from_station_id, to_station_id, km_start, km_end, line) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))",
                seg.first,
                CORRIDOR,
                seg.second.first,
                seg.second.second,
                km.first,
                km.second,
                wkt(seg.third),
            )
        }
    }

    @Test
    fun `corridor geometry loads the seeded polyline in km order and measures along and lateral distance`() {
        var now = 1_000_000L
        val geometry = JdbcCorridorGeometry(ds, CORRIDOR) { now }
        assertNull(geometry.alongTrack(-22.88, -43.30), "tabela vazia: sem tracado")
        assertTrue(geometry.offCorridor(-22.88, -43.30), "sem tracado conta como fora")

        seedCorridor()
        assertNull(geometry.alongTrack(-22.88, -43.30), "nova tentativa so apos 30 s")
        now += RETRY_MS + 1

        val expected = PolylineCorridor(TRECHO_1 + TRECHO_2)
        val start = assertNotNull(geometry.alongTrack(TRECHO_1.first().first, TRECHO_1.first().second))
        assertTrue(start.first < 1.0 && start.second < 1.0, "ponto de partida em along 0")
        val middle = assertNotNull(geometry.alongTrack(-22.8800, -43.3000))
        val want = expected.alongTrack(-22.8800, -43.3000)
        assertTrue(abs(middle.first - want.first) < 1.0, "along do vertice central: ${middle.first} x ${want.first}")
        assertTrue(middle.second < 1.0)
        val end = assertNotNull(geometry.alongTrack(TRECHO_2.last().first, TRECHO_2.last().second))
        assertTrue(abs(end.first - expected.alongTrack(-22.91, -43.68).first) < 1.0, "o fim vem depois do trecho 2")
        assertTrue(end.first > middle.first, "km crescente do Central para Santa Cruz")

        assertFalse(geometry.offCorridor(-22.8800, -43.3000))
        assertTrue(geometry.offCorridor(-22.8500, -43.3000), "3 km ao norte do tracado")
        assertNull(JdbcCorridorGeometry(ds, "OUTRO") { now }.alongTrack(-22.88, -43.30))
    }

    @Test
    fun `partition maintenance creates the next hours, is idempotent and clears expired nonces`() {
        val maintenance = PartitionMaintenance(ds)
        maintenance.runOnce()
        maintenance.runOnce()
        val partitions =
            scalars(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid " +
                    "WHERE i.inhparent = 'telemetry_points'::regclass",
            ).map { it as String }
        assertTrue(partitions.size >= 4, "hora atual e as 3 seguintes: $partitions")

        scalars(
            "INSERT INTO telemetry_points (anon_epoch_id, seq, recorded_at, corridor_id, geom, accuracy_m) " +
                "VALUES ('ep_futuro', 1, now() + interval '2 hours', ?, " +
                "ST_SetSRID(ST_MakePoint(-43.68, -22.91), 4326), 5)",
            CORRIDOR,
        )
        assertEquals(1L, scalars("SELECT count(*) FROM telemetry_points").single())

        scalars("INSERT INTO telemetry_nonces (nonce, expires_at) VALUES ('velho', now() - interval '1 minute')")
        scalars("INSERT INTO telemetry_nonces (nonce, expires_at) VALUES ('vivo', now() + interval '1 minute')")
        maintenance.runOnce()
        assertEquals(listOf<Any?>("vivo"), scalars("SELECT nonce FROM telemetry_nonces"))
    }

    @Test
    fun `all migrations V1 to V12 applied cleanly and the backup code table exists`() {
        val versions = scalars("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
        assertEquals((1..12).map { it.toString() }, versions.filterNotNull().map { it.toString() })
        assertEquals(0L, scalars("SELECT count(*) FROM flyway_schema_history WHERE NOT success").single())
        val columns =
            scalars("SELECT column_name FROM information_schema.columns WHERE table_name = 'totp_backup_codes'")
        assertTrue(columns.map { it as String }.containsAll(listOf("account_id", "code_hmac", "used_at")))
        assertEquals(
            "postgis",
            scalars("SELECT extname FROM pg_extension WHERE extname = 'postgis'").single(),
        )
        assertTrue((scalars("SELECT postgis_version()").single() as String).isNotEmpty())
    }
}
