package br.malhaviva.feature.trains.net

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpResponse
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.feature.trains.Direction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTrainsClientTest {
    private fun trainJson(
        id: String,
        along: Double = 1000.0,
    ) = """{"id":"$id","along_m":$along,"speed_mps":10.0,"confidence":"high","updated_at_ms":5}"""

    private fun sse(
        id: String,
        along: Double = 1000.0,
    ) = "event: train\ndata: ${trainJson(id, along)}\n\n"

    /** Cada tentativa de conexao executa um passo do roteiro; passar do fim do roteiro falha o teste. */
    private class Script(
        private val steps: List<suspend (onOpen: suspend () -> Unit, onChunk: suspend (String) -> Unit) -> Unit>,
    ) : SseSource {
        val urls = mutableListOf<String>()
        val openTimes = mutableListOf<Long>()
        var clock: () -> Long = { 0L }

        override suspend fun collect(
            url: String,
            onOpen: suspend () -> Unit,
            onChunk: suspend (String) -> Unit,
        ) {
            urls += url
            openTimes += clock()
            steps[urls.size - 1](onOpen, onChunk)
        }
    }

    private class FakeTransport(
        private val snapshot: String?,
        private val network: String? = null,
    ) : HttpTransport {
        val requests = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request.url
            val body = if ("/trains" in request.url) snapshot else network
            if (body == null) throw IOException("sem rede")
            return HttpResponse(HttpURLConnection.HTTP_OK, body)
        }
    }

    private fun TestScope.client(
        script: Script,
        transport: HttpTransport = FakeTransport(snapshot = null),
        stableAfterMs: Long = 30_000,
    ): LiveTrainsClient {
        script.clock = { currentTime }
        return LiveTrainsClient(
            baseUrl = { "https://api.test/" },
            transport = transport,
            source = script,
            backoff = Backoff(random = { 1.0 }),
            clock = { currentTime },
            stableAfterMs = stableAfterMs,
        )
    }

    @Test
    fun `parses train events, ignores heartbeats and other event types, tolerates split chunks`() =
        runTest {
            val raw =
                ": heartbeat\n\nevent: other\ndata: {}\n\n" + sse("a") + "event: train\ndata: not json\n\n" + sse("b")
            val script =
                Script(
                    listOf({ open, chunk ->
                        open()
                        // pedacos de 7 caracteres: corta linhas e JSON ao meio
                        raw.chunked(7).forEach { chunk(it) }
                        throw IOException("fim")
                    }),
                )
            val feed = client(script).feed(Direction.CentralToSantaCruz).take(5).toList()
            assertEquals(TrainsFeed.Connecting, feed[0])
            assertEquals(TrainsFeed.Opened, feed[1])
            assertEquals(listOf("a", "b"), feed.filterIsInstance<TrainsFeed.Train>().map { it.train.id })
            assertEquals(TrainsFeed.Lost(1), feed[4])
            assertEquals(
                "https://api.test/v1/corridors/RAMAL_SANTA_CRUZ/trains/stream?direction=CENTRAL_TO_SANTA_CRUZ",
                script.urls.single(),
            )
        }

    @Test
    fun `reconnects with exponential backoff after failures`() =
        runTest {
            val fail: suspend (
                suspend () -> Unit,
                suspend (String) -> Unit,
            ) -> Unit = { _, _ -> throw IOException("x") }
            val script = Script(listOf(fail, fail, fail, fail))
            // Connecting + Lost por tentativa; 4 tentativas = 8 itens
            client(script).feed(Direction.SantaCruzToCentral).take(8).toList()
            // sem jitter (random = 1.0): esperas de 1 s, 2 s e 4 s entre as 4 tentativas
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), script.openTimes)
            assertTrue(script.urls.all { it.endsWith("direction=SANTA_CRUZ_TO_CENTRAL") })
        }

    @Test
    fun `server closing cleanly right after opening still counts as a failure and backs off`() =
        runTest {
            val quickClose: suspend (suspend () -> Unit, suspend (String) -> Unit) -> Unit = { open, _ -> open() }
            val script = Script(listOf(quickClose, quickClose, quickClose))
            val feed = client(script).feed(Direction.CentralToSantaCruz).take(9).toList()
            assertEquals(listOf(1, 2, 3), feed.filterIsInstance<TrainsFeed.Lost>().map { it.attempt })
            assertEquals(listOf(0L, 1_000L, 3_000L), script.openTimes)
        }

    @Test
    fun `a connection that stayed open long enough resets the backoff`() =
        runTest {
            val fail: suspend (
                suspend () -> Unit,
                suspend (String) -> Unit,
            ) -> Unit = { _, _ -> throw IOException("x") }
            val stable: suspend (suspend () -> Unit, suspend (String) -> Unit) -> Unit = { open, _ ->
                open()
                kotlinx.coroutines.delay(60_000)
                throw IOException("caiu depois de 60 s")
            }
            val script = Script(listOf(fail, fail, stable, fail))
            val feed = client(script).feed(Direction.CentralToSantaCruz).take(9).toList()
            // falha 1, falha 2, estavel (volta a 1), falha 2
            assertEquals(listOf(1, 2, 1, 2), feed.filterIsInstance<TrainsFeed.Lost>().map { it.attempt })
        }

    @Test
    fun `503 with Retry-After raises the wait above the backoff`() =
        runTest {
            val busy: suspend (
                suspend () -> Unit,
                suspend (String) -> Unit,
            ) -> Unit = { _, _ -> throw SseHttpException(503, 20_000) }
            val script = Script(listOf(busy, busy))
            client(script).feed(Direction.CentralToSantaCruz).take(4).toList()
            assertEquals(listOf(0L, 20_000L), script.openTimes)
        }

    @Test
    fun `while the stream is down each failure polls the snapshot as fallback`() =
        runTest {
            val snapshot = """{"corridor":"RAMAL_SANTA_CRUZ","direction":"CENTRAL_TO_SANTA_CRUZ","trains":[${trainJson(
                "p1",
            )},${trainJson("p2")}]}"""
            val transport = FakeTransport(snapshot)
            val fail: suspend (
                suspend () -> Unit,
                suspend (String) -> Unit,
            ) -> Unit = { _, _ -> throw IOException("x") }
            val script = Script(listOf(fail))
            val feed = client(script, transport).feed(Direction.CentralToSantaCruz).take(3).toList()
            val polled = feed.filterIsInstance<TrainsFeed.Snapshot>().single()
            assertEquals(listOf("p1", "p2"), polled.trains.map { it.id })
            assertEquals(
                listOf("https://api.test/v1/corridors/RAMAL_SANTA_CRUZ/trains?direction=CENTRAL_TO_SANTA_CRUZ"),
                transport.requests,
            )
        }

    @Test
    fun `failed polling is silent and the loop keeps going`() =
        runTest {
            val fail: suspend (
                suspend () -> Unit,
                suspend (String) -> Unit,
            ) -> Unit = { _, _ -> throw IOException("x") }
            val script = Script(listOf(fail, fail))
            val feed =
                client(
                    script,
                    FakeTransport(snapshot = null),
                ).feed(Direction.CentralToSantaCruz).take(4).toList()
            assertTrue(feed.none { it is TrainsFeed.Snapshot })
            assertEquals(2, script.urls.size)
        }

    @Test
    fun `network is parsed from the corridor endpoint and null on failure`() =
        runTest {
            val json =
                """{"corridor":"RAMAL_SANTA_CRUZ","stations":[
                {"station_id":"A","name":"Central","seq_order":1,"km":0,
                 "platform":{"type":"Polygon","coordinates":[[[-43.19,-22.90],[-43.19,-22.91],[-43.20,-22.91],[-43.20,-22.90],[-43.19,-22.90]]]}},
                {"station_id":"B","name":"Madureira","seq_order":2,"km":1,
                 "platform":{"type":"Polygon","coordinates":[[[-43.33,-22.87],[-43.33,-22.88],[-43.34,-22.88],[-43.34,-22.87],[-43.33,-22.87]]]}}],
                "segments":[{"segment_id":"s","from_station_id":"A","to_station_id":"B",
                 "line":{"type":"LineString","coordinates":[[-43.195,-22.905],[-43.335,-22.875]]}}]}"""
            val ok = client(Script(emptyList()), FakeTransport(snapshot = null, network = json)).network()
            assertNotNull(ok)
            assertEquals(listOf("Central", "Madureira"), ok!!.stations.map { it.name })
            assertTrue(ok.stations[0].alongM < ok.stations[1].alongM)
            assertNull(client(Script(emptyList()), FakeTransport(snapshot = null, network = null)).network())
        }

    @Test
    fun `check-in posts an anonymous body and cancel deletes by id`() =
        runTest {
            val sent = mutableListOf<HttpRequest>()
            val transport =
                object : HttpTransport {
                    override suspend fun execute(request: HttpRequest): HttpResponse {
                        sent += request
                        return HttpResponse(HttpURLConnection.HTTP_NO_CONTENT, "")
                    }
                }
            val c = client(Script(emptyList()), transport)
            assertTrue(c.checkIn(Direction.CentralToSantaCruz, "bangu", "uuid-1"))
            assertTrue(c.cancelCheckIn(Direction.CentralToSantaCruz, "bangu", "uuid-1"))
            assertEquals("POST", sent[0].method)
            assertEquals("https://api.test/v1/corridors/RAMAL_SANTA_CRUZ/stations/bangu/checkins", sent[0].url)
            assertEquals(
                """{"direction":"CENTRAL_TO_SANTA_CRUZ","checkin_id":"uuid-1"}""",
                sent[0].body!!.decodeToString(),
            )
            assertEquals(setOf("Content-Type"), sent[0].headers.keys)
            assertEquals("DELETE", sent[1].method)
            assertTrue(sent[1].url.endsWith("/checkins/uuid-1?direction=CENTRAL_TO_SANTA_CRUZ"))
        }
}
