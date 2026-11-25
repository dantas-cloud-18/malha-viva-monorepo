package br.malhaviva.tracking.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Ultima posicao de um passageiro anonimo; nao carrega nenhum identificador. */
data class PassengerPosition(
    val alongM: Double,
    val speedMps: Double,
    val tsMs: Long,
)

/** Situacao do trem; o texto em portugues e montado pelo cliente. */
@Serializable
enum class Situation {
    @SerialName("moving")
    MOVING,

    @SerialName("at_station")
    AT_STATION,

    /** Alerta: parado fora de estacao por pelo menos [ClusterPolicy.STOP_ALERT_MS]. */
    @SerialName("stopped_between_stations")
    STOPPED_BETWEEN_STATIONS,
}

@Serializable
data class Eta(
    @SerialName("station_id") val stationId: String,
    @SerialName("eta_s") val etaS: Int,
)

@Serializable
data class TrainState(
    val id: String,
    @SerialName("along_m") val alongM: Double,
    @SerialName("speed_mps") val speedMps: Double,
    val confidence: ConfidenceBand,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    val situation: Situation,
    @SerialName("station_id") val stationId: String?,
    val etas: List<Eta>,
    /** Desde quando esta parado (so no servidor, serve ao alerta); nunca sai no JSON. */
    @Transient val stoppedSinceMs: Long? = null,
)

/** Faixa grossa de confianca; o numero exato de contribuintes nunca sai do servidor. */
@Serializable
enum class ConfidenceBand {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
    ;

    companion object {
        /** 1..2 -> baixa, 3..6 -> media, 7 ou mais -> alta. */
        const val MEDIUM_FROM = 3
        const val HIGH_FROM = 7

        fun of(contributors: Int): ConfidenceBand =
            when {
                contributors >= HIGH_FROM -> HIGH
                contributors >= MEDIUM_FROM -> MEDIUM
                else -> LOW
            }
    }
}

object ClusterPolicy {
    // as posicoes ja chegam projetadas para "agora": o espalhamento que sobra e ruido de GPS, nao fase de envio
    const val GROUP_GAP_M = 250.0
    const val MATCH_RADIUS_M = 300.0

    // O app segura os ultimos 300 m da viagem (PrivacyPolicy.TRIM_M): a amostra mais nova tem de 25 a 40 s a 12 m/s.
    // A posicao e projetada para agora pela velocidade, dentro desta mesma janela.
    // Trem lento ou parado demora a sair dos 300 m retidos: 180 s evita sumir na parada (teste de campo 25/11).
    const val MAX_AGE_MS = 180_000L

    /** Projecao para "agora" limitada a esta idade, para amostra velha nao empurrar o trem para longe. */
    const val MAX_PROJECT_MS = 60_000L
    const val MIN_BAND_M = 100.0
    const val MAD_FACTOR = 3.0

    /** Parado fora de estacao por tanto tempo vira alerta; ETA so ate este horizonte (99 min, igual ao app). */
    const val STOP_ALERT_MS = 60_000L
    const val ETA_MAX_S = 99 * 60
    const val MIN_ETA_SPEED_MPS = 1.0

    /** Pedestre: viagem isolada abaixo disto (7,2 km/h) fora de estacao nao e trem. Proposto, calibrar. */
    const val PEDESTRIAN_MAX_SPEED_MPS = 2.0

    /**
     * Limiar de publicacao (RIPD R1/R2): ids efemeros distintos por trem. Decisao de produto: 1, para o trem
     * aparecer com um unico celular; sobe-se pela variavel de ambiente. Nunca abaixo do piso 1.
     */
    const val DEFAULT_MIN_CONTRIBUTORS = 1
    const val MIN_CONTRIBUTORS_FLOOR = 1
    const val MIN_CONTRIBUTORS_ENV = "MALHAVIVA_TRAIN_MIN_CONTRIBUTORS"

    /** Invalido ou abaixo do piso (1) cai no piso; ausente usa o padrao. */
    fun minContributorsFromEnv(env: (String) -> String?): Int =
        (env(MIN_CONTRIBUTORS_ENV)?.toIntOrNull() ?: DEFAULT_MIN_CONTRIBUTORS).coerceAtLeast(MIN_CONTRIBUTORS_FLOOR)
}

private const val MS_PER_S = 1000.0

private fun median(sorted: List<Double>): Double {
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

private fun Direction.sign(): Int = if (this == Direction.CENTRAL_TO_SANTA_CRUZ) 1 else -1

/** Agrupa posicoes ordenadas: um novo trem comeca quando o salto passa de GROUP_GAP_M. */
private fun groupByGap(sorted: List<PassengerPosition>): List<List<PassengerPosition>> {
    val groups = mutableListOf<MutableList<PassengerPosition>>()
    for (p in sorted) {
        val last = groups.lastOrNull()
        if (last != null && p.alongM - last.last().alongM <= ClusterPolicy.GROUP_GAP_M) {
            last += p
        } else {
            groups += mutableListOf(p)
        }
    }
    return groups
}

/** Mediana dos inliers (banda em torno da mediana, igual ao consenso do app). */
private fun inliersOf(group: List<PassengerPosition>): List<PassengerPosition> {
    val sorted = group.sortedBy { it.alongM }
    val med = median(sorted.map { it.alongM })
    val mad = median(sorted.map { abs(it.alongM - med) }.sorted())
    val band = max(ClusterPolicy.MIN_BAND_M, ClusterPolicy.MAD_FACTOR * mad)
    return sorted.filter { abs(it.alongM - med) <= band }
}

class TrainClusterer(
    private val minContributors: Int = ClusterPolicy.DEFAULT_MIN_CONTRIBUTORS,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val stations: () -> List<StationRef> = { emptyList() },
) {
    /**
     * Estima os trens de um corredor+sentido. Cada posicao e projetada para [nowMs] (velocidade x idade, no
     * [direction]) antes de agrupar. [previous] serve para manter ids estaveis (cada grupo herda o id do trem anterior
     * mais proximo, ate MATCH_RADIUS_M) e o tempo parado. Grupos com menos de [minContributors] posicoes (cada uma de
     * um id efemero distinto) nao sao trens publicaveis e saem aqui.
     */
    fun cluster(
        positions: Collection<PassengerPosition>,
        nowMs: Long,
        previous: List<TrainState> = emptyList(),
        direction: Direction = Direction.CENTRAL_TO_SANTA_CRUZ,
    ): List<TrainState> {
        val fresh =
            positions
                .filter { nowMs - it.tsMs <= ClusterPolicy.MAX_AGE_MS }
                .map { projected(it, nowMs, direction) }
                .sortedBy { it.alongM }
        val refs = stations()
        val drafts =
            groupByGap(fresh)
                .filter { it.size >= minContributors && !isPedestrian(it, refs, previous) }
                .map { draft(it) }
        val ids = assignIds(drafts.map { it.alongM }, previous)
        return drafts.mapIndexed { i, d ->
            describe(d.copy(id = ids[i]), refs, direction, nowMs, previous.firstOrNull { it.id == ids[i] })
        }
    }

    /**
     * Viagem isolada e lenta fora do raio de estacao: gente a pe, nao um trem. Excecao: perto de um trem ja
     * publicado e o trem que parou entre estacoes (teste de campo 25/11), nao um pedestre.
     */
    private fun isPedestrian(
        group: List<PassengerPosition>,
        refs: List<StationRef>,
        previous: List<TrainState>,
    ): Boolean {
        val p = group.singleOrNull() ?: return false
        val atStation = refs.any { abs(it.alongM - p.alongM) <= CrowdingPolicy.STATION_RADIUS_M }
        val knownTrain = previous.any { abs(it.alongM - p.alongM) <= ClusterPolicy.MATCH_RADIUS_M }
        return p.speedMps < ClusterPolicy.PEDESTRIAN_MAX_SPEED_MPS && !atStation && !knownTrain
    }

    private fun projected(
        p: PassengerPosition,
        nowMs: Long,
        direction: Direction,
    ): PassengerPosition {
        val ahead = p.speedMps * min(nowMs - p.tsMs, ClusterPolicy.MAX_PROJECT_MS) / MS_PER_S
        return p.copy(alongM = (p.alongM + direction.sign() * ahead).coerceAtLeast(0.0))
    }

    private fun draft(group: List<PassengerPosition>): TrainState {
        val inliers = inliersOf(group)
        return TrainState(
            id = "",
            alongM = median(inliers.map { it.alongM }),
            speedMps = median(inliers.map { it.speedMps }.sorted()),
            confidence = ConfidenceBand.of(group.size),
            updatedAtMs = group.maxOf { it.tsMs },
            situation = Situation.MOVING,
            stationId = null,
            etas = emptyList(),
        )
    }

    /** Situacao, estacao de referencia e ETA por estacao a frente (marcha constante: estimativa). */
    private fun describe(
        t: TrainState,
        refs: List<StationRef>,
        direction: Direction,
        nowMs: Long,
        prev: TrainState?,
    ): TrainState {
        val sign = direction.sign()
        val stopped = t.speedMps <= CrowdingPolicy.DWELL_MAX_SPEED_MPS
        val nearest = refs.minByOrNull { abs(it.alongM - t.alongM) }
        val at = nearest?.takeIf { abs(it.alongM - t.alongM) <= CrowdingPolicy.STATION_RADIUS_M }
        val since = if (stopped) prev?.stoppedSinceMs ?: nowMs else null
        val situation =
            when {
                stopped && at != null -> Situation.AT_STATION
                since != null && nowMs - since >= ClusterPolicy.STOP_ALERT_MS -> Situation.STOPPED_BETWEEN_STATIONS
                else -> Situation.MOVING
            }
        val ahead = refs.filter { sign * (it.alongM - t.alongM) > 0 }.sortedBy { sign * it.alongM }
        val etas =
            if (t.speedMps < ClusterPolicy.MIN_ETA_SPEED_MPS) {
                emptyList()
            } else {
                ahead
                    .map { Eta(it.id, ceil(sign * (it.alongM - t.alongM) / t.speedMps).toInt()) }
                    .takeWhile { it.etaS <= ClusterPolicy.ETA_MAX_S }
            }
        val reference = if (situation == Situation.AT_STATION) at?.id else ahead.firstOrNull()?.id
        return t.copy(situation = situation, stationId = reference, etas = etas, stoppedSinceMs = since)
    }

    /** Casamento guloso por distancia crescente; sobras recebem id novo. */
    private fun assignIds(
        alongs: List<Double>,
        previous: List<TrainState>,
    ): List<String> {
        val ids = arrayOfNulls<String>(alongs.size)
        val usedPrev = mutableSetOf<String>()
        val pairs =
            alongs.indices
                .flatMap { i -> previous.map { Triple(i, it, abs(it.alongM - alongs[i])) } }
                .filter { it.third <= ClusterPolicy.MATCH_RADIUS_M }
                .sortedBy { it.third }
        for ((i, prev, _) in pairs) {
            if (ids[i] == null && usedPrev.add(prev.id)) ids[i] = prev.id
        }
        return ids.map { it ?: newId() }
    }
}
