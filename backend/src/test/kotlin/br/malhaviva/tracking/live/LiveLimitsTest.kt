package br.malhaviva.tracking.live

import br.malhaviva.installForwardedHeaders
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.sse.SSE as ClientSSE

private const val STREAM = "/v1/corridors/RAMAL_SANTA_CRUZ/trains/stream?direction=SANTA_CRUZ_TO_CENTRAL"
private const val BAD_STREAM = "/v1/corridors/RAMAL_SANTA_CRUZ/trains/stream?direction=NADA"

class LiveLimitsTest {
    private var clock = 1_800_000_000_000L
    private val key = LineKey("RAMAL_SANTA_CRUZ", Direction.SANTA_CRUZ_TO_CENTRAL)

    private fun ApplicationTestBuilder.setup(
        hub: LiveHub,
        limiter: ConnectionLimiter,
    ) = application {
        installForwardedHeaders()
        install(ContentNegotiation) { json() }
        install(SSE)
        routing { route("/v1") { liveRoutes(hub, limiter) } }
    }

    @Test
    fun `connection limiter enforces the global and the per ip cap and releases`() {
        val limiter = ConnectionLimiter(maxTotal = 3, maxPerIp = 2)
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"), "teto por IP")
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("c"), "teto global")
        limiter.release("a")
        assertTrue(limiter.tryAcquire("c"))
        repeat(3) { limiter.release("a") }
        listOf("b", "c").forEach(limiter::release)
        assertEquals(0, limiter.active())
    }

    @Test
    fun `sse answers 503 with Retry-After above the cap and holds no slot for it`() =
        testApplication {
            val limiter = ConnectionLimiter(maxTotal = 0, maxPerIp = 0)
            setup(LiveHub(clock = { clock }), limiter)
            val res = client.get(STREAM)
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertNotNull(res.headers[HttpHeaders.RetryAfter])
            assertEquals(0, limiter.active())
        }

    @Test
    fun `a finished stream gives its slot back`() =
        testApplication {
            val limiter = ConnectionLimiter(maxTotal = 1, maxPerIp = 1)
            setup(LiveHub(clock = { clock }), limiter)
            // direcao invalida: o stream abre e fecha logo; se a vaga vazasse, o segundo pedido seria 503
            repeat(3) { assertEquals(HttpStatusCode.OK, client.get(BAD_STREAM).status) }
            assertEquals(0, limiter.active())
        }

    @Test
    fun `an open stream blocks the same client above its cap but not another client`() =
        testApplication {
            val limiter = ConnectionLimiter(maxTotal = 10, maxPerIp = 1)
            setup(LiveHub(clock = { clock }), limiter)
            val sseClient = createClient { install(ClientSSE) }
            sseClient.sse(STREAM, { header("X-Forwarded-For", "198.51.100.1") }) {
                assertEquals(1, limiter.active())
                val same = client.get(STREAM) { header("X-Forwarded-For", "198.51.100.1") }
                assertEquals(HttpStatusCode.ServiceUnavailable, same.status, "mesmo IP acima do teto")
                val other = client.get(BAD_STREAM) { header("X-Forwarded-For", "198.51.100.2") }
                assertEquals(HttpStatusCode.OK, other.status, "outro IP segue livre")
            }
            // a liberacao ao fechar o stream aberto depende do cancelamento do engine real (Netty); o motor de
            // teste nao propaga o fechamento do cliente, entao a liberacao e provada pelo teste de stream encerrado
        }

    @Test
    fun `snapshot is served from a one second cache and refreshed by updates`() {
        val hub = LiveHub(clock = { clock })
        val t0 = clock
        // 29,8 s de idade: vale agora, mas passa dos 30 s em 500 ms
        listOf(1000.0, 1050.0, 1100.0).forEachIndexed { i, along ->
            hub.update(key, "e$i", PassengerPosition(along, 12.0, t0 - 29_800))
        }
        assertEquals(1, hub.snapshot(key).size)
        clock = t0 + 500
        assertEquals(1, hub.snapshot(key).size, "dentro de 1 s o agrupamento nao e refeito")
        clock = t0 + 1_100
        assertEquals(0, hub.snapshot(key).size, "passado 1 s recalcula e as posicoes velhas saem")
        hub.update(key, "novo", PassengerPosition(2000.0, 10.0, clock))
        assertEquals(1, hub.snapshot(key).size, "atualizacao nova aparece na hora")
    }
}
