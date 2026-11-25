package br.malhaviva

import br.malhaviva.platform.Database
import br.malhaviva.platform.DatabaseConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.test.BeforeTest

/** Um unico PostGIS 17 por JVM de teste, com V1 a V11 aplicadas uma vez (o Testcontainers o derruba no fim). */
private object SharedPostgres {
    private val image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres")
    private val container = PostgreSQLContainer(image).also { it.start() }
    val dataSource: HikariDataSource =
        Database
            .connect(DatabaseConfig(container.jdbcUrl, container.username, container.password))
            .also { Database.migrate(it) }
}

/** Base dos testes de repositorios JDBC contra Postgres de verdade; cada teste comeca com as tabelas vazias. */
abstract class PostgresIntegrationTest {
    protected val ds: DataSource get() = SharedPostgres.dataSource

    @BeforeTest
    fun cleanTables() {
        ds.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "TRUNCATE accounts, access_logs, corridors, telemetry_points, telemetry_nonces, " +
                        "train_events, holidays CASCADE",
                )
            }
        }
    }

    /** Roda [n] tarefas ao mesmo tempo, largando juntas, e devolve os resultados. */
    protected fun <T> race(
        n: Int,
        task: (Int) -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val futures =
                (0 until n).map { i ->
                    pool.submit(
                        Callable {
                            gate.await()
                            task(i)
                        },
                    )
                }
            gate.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Executa SQL direto (preparo e conferencia dos testes); devolve a primeira coluna de cada linha. */
    protected fun scalars(
        sql: String,
        vararg params: Any?,
    ): List<Any?> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
                if (st.execute()) {
                    st.resultSet.use { rs -> buildList { while (rs.next()) add(rs.getObject(1)) } }
                } else {
                    emptyList()
                }
            }
        }
}
