package br.malhaviva

import br.malhaviva.platform.Database
import br.malhaviva.platform.DatabaseConfig
import br.malhaviva.tracking.ingest.PartitionMaintenance
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseIntegrationTest {
    private val image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres")
    private val container = PostgreSQLContainer(image)
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        container.start()
        dataSource = Database.connect(DatabaseConfig(container.jdbcUrl, container.username, container.password))
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
        container.stop()
    }

    @Test
    fun `health responds ok after migrations`() =
        testApplication {
            application { module(dataSource) }
            val response = client.get("/v1/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"ok\""))
        }

    @Test
    fun `telemetry partitions older than 24 hours are dropped`() {
        Database.migrate(dataSource)
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    DO ${'$'}${'$'}
                    DECLARE h timestamptz := date_trunc('hour', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' - interval '30 hours';
                    BEGIN
                      EXECUTE format('CREATE TABLE %I PARTITION OF telemetry_points FOR VALUES FROM (%L) TO (%L)',
                        'telemetry_points_' || to_char(h AT TIME ZONE 'UTC', 'YYYYMMDDHH24'), h, h + interval '1 hour');
                    END ${'$'}${'$'};
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO telemetry_points (anon_epoch_id, seq, recorded_at, corridor_id, geom, accuracy_m)
                    VALUES ('ep_old', 1, now() - interval '30 hours', 'RAMAL_SANTA_CRUZ', ST_SetSRID(ST_MakePoint(-43.68, -22.91), 4326), 5),
                           ('ep_new', 1, now(), 'RAMAL_SANTA_CRUZ', ST_SetSRID(ST_MakePoint(-43.68, -22.91), 4326), 5)
                    """.trimIndent(),
                )
            }
        }

        PartitionMaintenance(dataSource).runOnce()

        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT anon_epoch_id FROM telemetry_points ORDER BY 1").use { rs ->
                    val ids = generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    assertEquals(listOf("ep_new"), ids)
                }
            }
        }
    }

    @Test
    fun `telemetry tables carry no identity columns`() {
        Database.migrate(dataSource)
        val forbidden = Regex("user|account|handle|phone|email|ip_|device_model|session")
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT table_name, column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name IN ('telemetry_points', 'train_events')
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val column = rs.getString(2)
                        assertTrue(!forbidden.containsMatchIn(column), "${rs.getString(1)}.$column looks like identity")
                    }
                }
            }
        }
    }
}
