package br.malhaviva

import br.malhaviva.auth.AuthComponents
import br.malhaviva.auth.AuthConfig
import br.malhaviva.auth.authRoutes
import br.malhaviva.lgpd.RetentionConfig
import br.malhaviva.lgpd.RetentionJob
import br.malhaviva.network.NetworkRepository
import br.malhaviva.network.networkRoutes
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
import br.malhaviva.tracking.live.ClusterPolicy
import br.malhaviva.tracking.live.CrowdingPolicy
import br.malhaviva.tracking.live.LiveHub
import br.malhaviva.tracking.live.StationCrowdingEstimator
import br.malhaviva.tracking.live.StationRef
import br.malhaviva.tracking.live.TrainClusterer
import br.malhaviva.tracking.live.crowdingRoutes
import br.malhaviva.tracking.live.liveRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private const val METERS_PER_KM = 1000.0

private val log = LoggerFactory.getLogger("br.malhaviva.Application")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port) {
        val dataSource = Database.connect(DatabaseConfig.fromEnv())
        // falha aqui (pepper/segredo ausentes) derruba a subida antes de aceitar trafego
        module(dataSource, auth = AuthConfig.fromEnv(dataSource))
    }.start(wait = true)
}

// parametros com padrao existem so para injecao em testes; agrupar quebraria a assinatura usada por eles
@Suppress("LongParameterList")
fun Application.module(
    dataSource: DataSource,
    liveHub: LiveHub =
        LiveHub(clusterer = TrainClusterer(ClusterPolicy.minContributorsFromEnv(System::getenv))),
    tokens: InMemoryTelemetryTokenStore = InMemoryTelemetryTokenStore(),
    geometry: CorridorGeometry = JdbcCorridorGeometry(dataSource),
    integrity: IntegrityVerifier = integrityVerifierFromEnv(),
    auth: AuthComponents? = null,
) {
    Database.migrate(dataSource)

    val retention = RetentionConfig.fromEnv(System::getenv)
    val maintenance = PartitionMaintenance(dataSource, retention.positionTtlHours)
    maintenance.start()
    val retentionJob = RetentionJob(dataSource, retention)
    retentionJob.start()
    monitor.subscribe(ApplicationStopped) {
        maintenance.stop()
        retentionJob.stop()
    }

    installForwardedHeaders()
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
            val network = NetworkRepository(dataSource)
            networkRoutes(network)
            liveRoutes(liveHub)
            crowdingRoutes(
                liveHub,
                { c -> network.find(c)?.stations?.map { StationRef(it.stationId, it.km * METERS_PER_KM) } },
                StationCrowdingEstimator(CrowdingPolicy.minContributorsFromEnv(System::getenv)),
            )
            ingestRoutes(BatchIngestor(tokens, geometry, liveHub))
            tokenRoutes(TokenIssuer(tokens), integrity)
            auth?.let { authRoutes(it) }
        }
    }
}

/**
 * Atras do ALB: o ALB ACRESCENTA o IP de quem conectou ao fim de X-Forwarded-For, entao o ULTIMO item e o cliente
 * real e o que veio antes pode ser forjado pelo cliente. So e seguro porque o grupo de seguranca da aplicacao aceita
 * trafego apenas do ALB (infra/terraform/network.tf). Com CloudFront ou outro proxy na frente, rever `skipLastProxies`.
 */
internal fun Application.installForwardedHeaders() {
    install(XForwardedHeaders) { useLastProxy() }
}

@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
)

private const val DEFAULT_PORT = 8080
