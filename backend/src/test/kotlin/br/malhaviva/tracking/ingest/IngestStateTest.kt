package br.malhaviva.tracking.ingest

import br.malhaviva.installForwardedHeaders
import br.malhaviva.platform.FakeTicker
import br.malhaviva.tracking.live.LiveHub
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngestStateTest {
    private val now = 1_800_000_000_000L
    private val key = "chave-de-teste-com-32-bytes-ok!!".toByteArray()
    private val ticker = FakeTicker()
    private val store = InMemoryTelemetryTokenStore(ticker)
    private val corridor = PolylineCorridor(listOf(-22.9 to -43.2, -22.9 to -43.1))
    private var nonceCount = 0

    private fun batchFor(n: Int): String {
        val samples =
            (1..3).joinToString(",") {
                val ts = Instant.ofEpochMilli(now - 20_000 + it * 5_000L)
                """{"seq":$it,"ts":"$ts","lat":-22.9,"lon":${-43.19 + it * 0.0005},"accuracy_m":5.0,"speed_mps":10.0}"""
            }
        return """{"anon_epoch_id":"epoch-$n","corridor":"RAMAL_SANTA_CRUZ","samples":[$samples]}"""
    }

    private fun ApplicationTestBuilder.setup(ingestor: BatchIngestor) {
        (1..3).forEach { store.put(TelemetryToken("tok-$it", key, "epoch-$it", now + 600_000)) }
        application {
            installForwardedHeaders()
            install(ContentNegotiation) { json() }
            routing { route("/v1") { ingestRoutes(ingestor) } }
        }
    }

    private suspend fun ApplicationTestBuilder.post(
        n: Int,
        ip: String,
    ): HttpResponse {
        val body = batchFor(n).toByteArray()
        val nonce = "nonce-%016d".format(nonceCount++)
        return client.post("/v1/telemetry/batches") {
            header("X-Forwarded-For", ip)
            header(HttpHeaders.Authorization, "Bearer tok-$n")
            header("X-Timestamp", now.toString())
            header("X-Nonce", nonce)
            header("X-Signature", BatchSecurity.sign(key, now, nonce, body))
            setBody(body)
        }
    }

    @Test
    fun `per IP ingest limit follows the real client address, not the shared proxy`() =
        testApplication {
            val limiters = IngestLimiters(perIp = RateLimiter(1.0, 0.0))
            setup(BatchIngestor(store, corridor, LiveHub(clock = { now }), limiters = limiters, clock = { now }))
            assertEquals(HttpStatusCode.Accepted, post(1, "6.6.6.6, 198.51.100.1").status)
            // mesmo cliente real, outro item forjado na frente: mesmo balde
            assertEquals(HttpStatusCode.TooManyRequests, post(2, "7.7.7.7, 198.51.100.1").status)
            // cliente real diferente atras do mesmo ALB: balde proprio
            assertEquals(HttpStatusCode.Accepted, post(3, "198.51.100.2").status)
        }

    @Test
    fun `per epoch state and expired telemetry tokens are evicted`() =
        testApplication {
            val ingestor = BatchIngestor(store, corridor, LiveHub(clock = { now }), clock = { now }, ticker = ticker)
            setup(ingestor)
            assertEquals(HttpStatusCode.Accepted, post(1, "198.51.100.1").status)
            assertEquals(1L, ingestor.trackedEpochs())
            assertEquals(3L, store.size())

            ticker.advance(Duration.ofMinutes(16))
            assertEquals(0L, ingestor.trackedEpochs(), "estado da epoca some sem uso")

            ticker.advance(Duration.ofMillis(TOKEN_TTL_MS))
            assertEquals(0L, store.size())
            assertNull(store.find("tok-1"))
        }
}
