package br.malhaviva.tracking.parity

import br.malhaviva.tracking.ingest.BatchIngestor
import br.malhaviva.tracking.ingest.BatchSecurity
import br.malhaviva.tracking.ingest.InMemoryTelemetryTokenStore
import br.malhaviva.tracking.ingest.IngestLimiters
import br.malhaviva.tracking.ingest.IngestRequest
import br.malhaviva.tracking.ingest.PolylineCorridor
import br.malhaviva.tracking.ingest.RateLimiter
import br.malhaviva.tracking.ingest.TelemetryToken
import br.malhaviva.tracking.live.Direction
import br.malhaviva.tracking.live.LineKey
import br.malhaviva.tracking.live.LiveHub
import br.malhaviva.tracking.live.TrainClusterer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Harness de paridade (Fase 7.2), lado novo: roda a fixture sintetica de tools/paridade pelo caminho real do
 * backend (BatchIngestor, validacao, PolylineCorridor, LiveHub, TrainClusterer) e escreve build/paridade/novo.json.
 * O lado antigo (Motor.gs) e a comparacao ficam em tools/paridade/comparar.js.
 */

@Serializable
private data class Am(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val acc: Double,
    val v: Double,
)

@Serializable
private data class Cel(
    val id: String,
    val amostras: List<Am>,
)

@Serializable
private data class Cen(
    val id: String,
    @SerialName("inicio_ms") val inicioMs: Long,
    @SerialName("fim_ms") val fimMs: Long,
    @SerialName("passo_ms") val passoMs: Long,
    val celulares: List<Cel>,
)

@Serializable
private data class Geo(
    val linha: List<List<Double>>,
)

@Serializable
private data class Fixture(
    val geometria: Geo,
    val cenarios: List<Cen>,
)

@Serializable
private data class OutTrain(
    val id: String,
    val direction: String,
    @SerialName("along_m") val alongM: Double,
    @SerialName("speed_mps") val speedMps: Double,
    val confidence: String,
)

@Serializable
private data class OutTick(
    val t: Long,
    val trains: List<OutTrain>,
)

@Serializable
private data class OutRun(
    val k: Int,
    @SerialName("lote_ms") val loteMs: Long,
    val ticks: List<OutTick>,
    val rejected: Map<String, Int>,
)

@Serializable
private data class OutScenario(
    val id: String,
    val runs: List<OutRun>,
)

@Serializable
private data class Out(
    @SerialName("corridor_length_m") val corridorLengthM: Double,
    val scenarios: List<OutScenario>,
)

private const val CORRIDOR = "RAMAL_SANTA_CRUZ"
private val KEY = "chave-de-paridade-com-32-bytes!!".toByteArray()
private const val STEP_MS = 1_000L
private const val LOTE_MS = 15_000L
private const val LOTE_RAPIDO_MS = 5_000L
private const val LOTE_LENTO_MS = 30_000L

// (K, intervalo de envio): K=3 e o padrao de producao; K=1 isola o algoritmo. 15 s e o envio de referencia (3 amostras
// por lote); 5 s (1 amostra por lote, sem sentido inferivel) e 30 s (trem anda mais que o raio de casamento de ids)
// sao sensibilidades.
private val RUNS =
    listOf(
        1 to LOTE_MS,
        2 to LOTE_MS,
        3 to LOTE_MS,
        1 to LOTE_RAPIDO_MS,
        3 to LOTE_RAPIDO_MS,
        1 to LOTE_LENTO_MS,
        3 to LOTE_LENTO_MS,
    )
private val LENIENT = Json { ignoreUnknownKeys = true }

class ParityHarnessTest {
    private val fixtureFile = File("../tools/paridade/fixtures/fixture.json")
    private val outFile = File("build/paridade/novo.json")

    private val fixture = LENIENT.decodeFromString<Fixture>(fixtureFile.readText())

    // Central primeiro (along 0 = Central), como o corredor do backend; a fixture vem de Santa Cruz (km 0).
    private val corridor =
        PolylineCorridor(
            fixture.geometria.linha
                .reversed()
                .map { it[0] to it[1] },
        )
    private val lengthM =
        fixture.geometria.linha
            .first()
            .let { corridor.alongTrack(it[0], it[1]).first }

    private fun run(
        cen: Cen,
        k: Int,
        loteMs: Long,
    ): OutRun {
        var now = cen.inicioMs
        var nonce = 0
        val hub = LiveHub(clock = { now }, clusterer = TrainClusterer(k, newId = idFactory()), snapshotTtlMs = 0)
        val store = InMemoryTelemetryTokenStore()
        val limiters = IngestLimiters(RateLimiter(1e9, 1e9), RateLimiter(1e9, 1e9))
        val ingestor = BatchIngestor(store, corridor, hub, limiters = limiters, clock = { now })
        cen.celulares.forEach { store.put(TelemetryToken("tok-${it.id}", KEY, "epoch-${it.id}", Long.MAX_VALUE)) }
        // cada celular envia no proprio ritmo (fase escalonada), como aparelhos reais nao sincronizados
        val phase =
            cen.celulares.withIndex().associate { (i, c) ->
                c.id to i * loteMs / cen.celulares.size / STEP_MS * STEP_MS
            }
        val sent = cen.celulares.associate { it.id to 0 }.toMutableMap()
        val rejected = sortedMapOf<String, Int>()
        val ticks = mutableListOf<OutTick>()
        while (now <= cen.fimMs) {
            for (cel in cen.celulares) {
                val from = sent.getValue(cel.id)
                val to = cel.amostras.indexOfLast { it.t <= now } + 1
                val due = (now - cen.inicioMs - phase.getValue(cel.id)) % loteMs == 0L
                if (!due || to <= from) continue
                val body = batchJson(cel, from, to).toByteArray()
                val n = "n-%016d".format(nonce++)
                val req = IngestRequest("tok-${cel.id}", "ip", "$now", n, BatchSecurity.sign(KEY, now, n, body), body)
                ingestor.ingest(req).rejected.forEach { (why, c) -> rejected.merge(why, c, Int::plus) }
                sent[cel.id] = to
            }
            if ((now - cen.inicioMs) % cen.passoMs == 0L) ticks += OutTick(now, snapshot(hub))
            now += STEP_MS
        }
        return OutRun(k, loteMs, ticks, rejected)
    }

    private fun idFactory(): () -> String {
        var seq = 0
        return { "N-%04d".format(++seq) }
    }

    private fun batchJson(
        cel: Cel,
        from: Int,
        to: Int,
    ): String {
        val samples =
            (from until to).joinToString(",") {
                val a = cel.amostras[it]
                """{"seq":$it,"ts":"${Instant.ofEpochMilli(a.t)}","lat":${a.lat},"lon":${a.lon},""" +
                    """"accuracy_m":${a.acc},"speed_mps":${a.v}}"""
            }
        return """{"anon_epoch_id":"epoch-${cel.id}","corridor":"$CORRIDOR","samples":[$samples]}"""
    }

    private fun snapshot(hub: LiveHub): List<OutTrain> =
        Direction.entries.flatMap { dir ->
            hub.snapshot(LineKey(CORRIDOR, dir)).map {
                OutTrain(it.id, dir.name, it.alongM, it.speedMps, it.confidence.name)
            }
        }

    @Test
    fun `roda a fixture no backend novo e grava build-paridade-novo json`() {
        val scenarios = fixture.cenarios.map { cen -> OutScenario(cen.id, RUNS.map { (k, lote) -> run(cen, k, lote) }) }
        outFile.parentFile.mkdirs()
        outFile.writeText(Json.encodeToString(Out(lengthM, scenarios)))

        fun maxTrains(
            id: String,
            k: Int,
        ) = scenarios
            .first { it.id.startsWith(id) }
            .runs
            .first { it.k == k && it.loteMs == LOTE_MS }
            .ticks
            .maxOf { it.trains.size }
        // sanidade do proprio harness: k-anonimato age e o caminho completo produz trens
        assertEquals(0, maxTrains("S3", 3), "um celular so nao e publicado com K=3")
        assertEquals(1, maxTrains("S3", 1), "com K=1 o celular isolado aparece")
        assertEquals(0, maxTrains("S4", 3), "dois celulares nao passam em K=3")
        assertEquals(1, maxTrains("S4", 2), "dois celulares passam em K=2")
        assertTrue(maxTrains("S1", 3) >= 2, "dois trens separados por 3 km")
        assertTrue(maxTrains("S0", 3) >= 1, "viagem real tem trem")
    }
}
