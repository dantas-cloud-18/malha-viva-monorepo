package br.malhaviva.tracking.ingest

import br.malhaviva.platform.DEFAULT_MAX_KEYS
import br.malhaviva.platform.ExpiringMap
import br.malhaviva.tracking.live.Direction
import br.malhaviva.tracking.live.LineKey
import br.malhaviva.tracking.live.LiveHub
import br.malhaviva.tracking.live.PassengerPosition
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.cos
import kotlin.math.sin

const val MAX_BODY_BYTES = 128 * 1024

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val CONFLICT = 409
private const val TOO_MANY = 429
private const val CORRIDOR_ID = "RAMAL_SANTA_CRUZ"
private const val MAX_SAMPLES = 120
private const val MIN_PROGRESS_M = 50.0
private const val BEARING_MIN_SPEED_MPS = 1.0
private const val BEARING_PROBE_M = 50.0
private const val BEARING_MIN_ALONG_M = 10.0
private const val METERS_PER_DEGREE = 111_320.0
private const val MAX_BEARING_DEG = 360.0
private const val MAX_LAT = 90.0
private const val MAX_LON = 180.0
private val NONCE_LENGTH = 16..64

// lote com mais de 10 min e STALE: depois disso o estado da epoca nao protege mais nada
private val EPOCH_STATE_TTL: Duration = Duration.ofMinutes(15)

private const val TOKEN_CAPACITY = 10.0
private const val TOKEN_REFILL_PER_S = 0.5
private const val IP_CAPACITY = 120.0
private const val IP_REFILL_PER_S = 2.0

/** Falha previsivel do lote; a rota converte em Problem com [status]. */
class IngestRejected(
    val status: Int,
    val title: String,
    cause: Throwable? = null,
) : RuntimeException(title, cause)

class IngestRequest(
    val bearer: String?,
    val clientIp: String,
    val timestamp: String?,
    val nonce: String?,
    val signature: String?,
    val body: ByteArray,
)

class IngestLimiters(
    val perToken: RateLimiter = RateLimiter(TOKEN_CAPACITY, TOKEN_REFILL_PER_S),
    val perIp: RateLimiter = RateLimiter(IP_CAPACITY, IP_REFILL_PER_S),
)

/** Estado minimo por epoca anonima: ultimo seq aceito e ultimo sentido inferido. Sem posicoes. */
private data class EpochState(
    val lastSeq: Long?,
    val direction: Direction?,
)

/**
 * Pipeline do lote: limites, token, assinatura, JSON, validacao das amostras e envio ao LiveHub.
 * Nunca registra em log nem devolve posicoes.
 *
 * Suppress: injecao de dependencias (o ticker so existe para testar a expiracao) e um unico pipeline de lote.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class BatchIngestor(
    private val tokens: TelemetryTokenStore,
    private val geometry: CorridorGeometry,
    private val hub: LiveHub,
    private val nonces: NonceStore = InMemoryNonceStore(),
    private val limiters: IngestLimiters = IngestLimiters(),
    private val clock: () -> Long = System::currentTimeMillis,
    ticker: Ticker = Ticker.systemTicker(),
) {
    // expira por inatividade e tem teto. shortcut: um no so; usar Valkey em producao.
    private val epochStore = ExpiringMap<String, EpochState>(DEFAULT_MAX_KEYS, EPOCH_STATE_TTL, ticker)
    private val epochs get() = epochStore.map

    /** Epocas com estado vivo (para testes e metricas). */
    fun trackedEpochs(): Long = epochStore.size()

    /** Limite por IP, cobrado pela rota ANTES de ler o corpo (a chave nao depende dele). */
    fun admitIp(ip: String) {
        reject(limiters.perIp.tryAcquire("ip:$ip", clock()), TOO_MANY, "Too many requests")
    }

    fun ingest(req: IngestRequest): BatchResult {
        val now = clock()
        val token = lookupToken(req.bearer, now)
        reject(limiters.perToken.tryAcquire("tk:${token.tokenId}", now), TOO_MANY, "Too many requests")
        reject(req.body.size <= MAX_BODY_BYTES, BAD_REQUEST, "Body too large")
        checkSignature(req, token, now)
        val samples = parseBatch(req.body, token).samples.map { it.toIngestSample() }
        return process(token.anonEpochId, samples, now)
    }

    private fun lookupToken(
        bearer: String?,
        now: Long,
    ): TelemetryToken {
        val token = bearer?.let(tokens::find)
        if (token == null || token.expiresAtMs <= now) throw IngestRejected(UNAUTHORIZED, "Invalid token")
        return token
    }

    private fun checkSignature(
        req: IngestRequest,
        token: TelemetryToken,
        now: Long,
    ) {
        val ts = req.timestamp?.toLongOrNull()
        val nonce = req.nonce?.takeIf { it.length in NONCE_LENGTH }
        val signature = req.signature
        if (ts == null || nonce == null || signature == null) {
            throw IngestRejected(BAD_REQUEST, "Missing or malformed signature headers")
        }
        BatchSecurity.verify(token.signingKey, ts, nonce, req.body, signature, now, nonces).failure()?.let { throw it }
    }

    private fun VerifyResult.failure(): IngestRejected? =
        when (this) {
            VerifyResult.Ok -> null
            VerifyResult.BadTimestamp -> IngestRejected(BAD_REQUEST, "Timestamp out of tolerance")
            VerifyResult.BadSignature -> IngestRejected(UNAUTHORIZED, "Invalid signature")
            VerifyResult.Replay -> IngestRejected(CONFLICT, "Nonce already used")
        }

    private fun parseBatch(
        body: ByteArray,
        token: TelemetryToken,
    ): BatchDto {
        val batch =
            try {
                Json.decodeFromString<BatchDto>(body.decodeToString())
            } catch (e: IllegalArgumentException) {
                throw IngestRejected(BAD_REQUEST, "Malformed batch", e)
            }
        reject(batch.corridor == CORRIDOR_ID, BAD_REQUEST, "Unknown corridor")
        reject(batch.anonEpochId == token.anonEpochId, BAD_REQUEST, "anon_epoch_id does not match token")
        reject(batch.samples.size in 1..MAX_SAMPLES, BAD_REQUEST, "Sample count out of range")
        return batch
    }

    private fun process(
        epochId: String,
        samples: List<IngestSample>,
        now: Long,
    ): BatchResult {
        lateinit var result: ValidationResult
        val state =
            epochs.compute(epochId) { _, prev ->
                result = validateSamples(samples, now, prev?.lastSeq) { geometry.offCorridor(it.lat, it.lon) }
                EpochState(
                    result.accepted.lastOrNull()?.seq ?: prev?.lastSeq,
                    heading(result.accepted) ?: prev?.direction,
                )
            }
        publish(epochId, result.accepted.lastOrNull(), state?.direction)
        return BatchResult(result.accepted.size, result.rejected)
    }

    /**
     * Sentido pelo avanco ao longo do tracado entre a primeira e a ultima amostra aceita
     * (along crescente = Central para Santa Cruz). Menos de 50 m de avanco (por exemplo lote de 1 amostra):
     * usa o rumo (`bearing_deg`) da ultima amostra; sem rumo utilizavel fica indeterminado e vale o sentido da epoca.
     */
    private fun heading(accepted: List<IngestSample>): Direction? {
        val delta = along(accepted.lastOrNull()) - along(accepted.firstOrNull())
        return when {
            delta >= MIN_PROGRESS_M -> Direction.CENTRAL_TO_SANTA_CRUZ
            delta <= -MIN_PROGRESS_M -> Direction.SANTA_CRUZ_TO_CENTRAL
            else -> accepted.lastOrNull()?.let(::headingFromBearing)
        }
    }

    /**
     * Projeta um ponto BEARING_PROBE_M a frente no rumo medido e olha para que lado do tracado ele andou. O rumo
     * do GPS so vale em movimento; rumo quase perpendicular ao tracado (avanco < BEARING_MIN_ALONG_M) e ignorado.
     */
    private fun headingFromBearing(s: IngestSample): Direction? {
        val moved =
            s.bearingDeg
                ?.takeIf { (s.speedMps ?: 0.0) >= BEARING_MIN_SPEED_MPS }
                ?.let { Math.toRadians(it) }
                ?.let { rad ->
                    val lat2 = s.lat + BEARING_PROBE_M * cos(rad) / METERS_PER_DEGREE
                    val lon2 = s.lon + BEARING_PROBE_M * sin(rad) / (METERS_PER_DEGREE * cos(Math.toRadians(s.lat)))
                    geometry.alongTrack(lat2, lon2)?.first?.minus(along(s))
                }
        return when {
            moved == null -> null
            moved >= BEARING_MIN_ALONG_M -> Direction.CENTRAL_TO_SANTA_CRUZ
            moved <= -BEARING_MIN_ALONG_M -> Direction.SANTA_CRUZ_TO_CENTRAL
            else -> null
        }
    }

    private fun along(s: IngestSample?): Double = s?.let { geometry.alongTrack(it.lat, it.lon)?.first } ?: 0.0

    /** So a ultima posicao aceita entra no hub; o id efemero e a propria epoca anonima. */
    private fun publish(
        epochId: String,
        last: IngestSample?,
        direction: Direction?,
    ) {
        if (last != null && direction != null) {
            val position = PassengerPosition(along(last), last.speedMps ?: 0.0, last.tsMs)
            hub.update(LineKey(CORRIDOR_ID, direction), epochId, position)
        }
    }
}

private fun reject(
    ok: Boolean,
    status: Int,
    title: String,
) {
    if (!ok) throw IngestRejected(status, title)
}

private fun SampleDto.toIngestSample(): IngestSample {
    val valid =
        seq >= 0 &&
            lat in -MAX_LAT..MAX_LAT &&
            lon in -MAX_LON..MAX_LON &&
            accuracyM >= 0 &&
            (speedMps ?: 0.0) >= 0 &&
            (bearingDeg ?: 0.0) in 0.0..MAX_BEARING_DEG &&
            bearingDeg != MAX_BEARING_DEG
    reject(valid, BAD_REQUEST, "Sample out of range")
    val tsMs =
        try {
            OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw IngestRejected(BAD_REQUEST, "Malformed timestamp", e)
        }
    return IngestSample(seq, tsMs, lat, lon, accuracyM, speedMps, mockLocation, bearingDeg)
}
