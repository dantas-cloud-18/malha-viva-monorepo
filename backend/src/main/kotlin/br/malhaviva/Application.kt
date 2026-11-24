package br.malhaviva

import br.malhaviva.platform.Database
import br.malhaviva.platform.DatabaseConfig
import br.malhaviva.platform.health.healthRoutes
import br.malhaviva.tracking.ingest.PartitionMaintenance
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.Application")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        module(Database.connect(DatabaseConfig.fromEnv()))
    }.start(wait = true)
}

fun Application.module(dataSource: DataSource) {
    Database.migrate(dataSource)

    val maintenance = PartitionMaintenance(dataSource)
    maintenance.start()
    monitor.subscribe(ApplicationStopped) { maintenance.stop() }

    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {}", call.request.local.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, Problem("about:blank", "Internal error", 500))
        }
    }

    routing {
        route("/v1") {
            healthRoutes(dataSource)
        }
    }
}

@Serializable
data class Problem(val type: String, val title: String, val status: Int)
