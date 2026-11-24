package br.malhaviva

import br.malhaviva.platform.Database
import br.malhaviva.platform.DatabaseConfig
import br.malhaviva.platform.health.healthRoutes
import br.malhaviva.tracking.ingest.BatchIngestor
import br.malhaviva.tracking.ingest.CorridorGeometry
import br.malhaviva.tracking.ingest.InMemoryTelemetryTokenStore
import br.malhaviva.tracking.ingest.IntegrityVerifier
import br.malhaviva.tracking.ingest.JdbcCorridorGeometry
import br.malhaviva.tracking.ingest.PartitionMaintenance
import br.malhaviva.tracking.ingest.TokenIssuer
import br.malhaviva.tracking.ingest.ingestRoutes
import br.malhaviva.tracking.ingest.integrityVerifierFromEnv
import br.malhaviva.tracking.ingest.tokenRoutes
import br.malhaviva.tracking.live.LiveHub
import br.malhaviva.tracking.live.liveRoutes
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
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.Application")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port) {
        module(Database.connect(DatabaseConfig.fromEnv()))
    }.start(wait = true)
}

fun Application.module(
    dataSource: DataSource,
    liveHub: LiveHub = LiveHub(),
    tokens: InMemoryTelemetryTokenStore = InMemoryTelemetryTokenStore(),
    geometry: CorridorGeometry = JdbcCorridorGeometry(dataSource),
    integrity: IntegrityVerifier = integrityVerifierFromEnv(),
) {
    Database.migrate(dataSource)

    val maintenance = PartitionMaintenance(dataSource)
    maintenance.start()
    monitor.subscribe(ApplicationStopped) { maintenance.stop() }

    install(ContentNegotiation) { json() }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {}", call.request.local.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, Problem("about:blank", "Internal error", 500))
        }
    }

    routing {
        route("/v1") {
            healthRoutes(dataSource)
            liveRoutes(liveHub)
            ingestRoutes(BatchIngestor(tokens, geometry, liveHub))
            tokenRoutes(TokenIssuer(tokens), integrity)
        }
    }
}

@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
)

private const val DEFAULT_PORT = 8080
