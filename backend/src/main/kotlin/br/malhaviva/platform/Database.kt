package br.malhaviva.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun fromEnv(): DatabaseConfig =
            DatabaseConfig(
                url = requireEnv("DB_URL"),
                user = requireEnv("DB_USER"),
                password = requireEnv("DB_PASSWORD"),
            )

        private fun requireEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Missing environment variable $name")
    }
}

object Database {
    fun connect(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = 10
            },
        )

    fun migrate(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    }
}
