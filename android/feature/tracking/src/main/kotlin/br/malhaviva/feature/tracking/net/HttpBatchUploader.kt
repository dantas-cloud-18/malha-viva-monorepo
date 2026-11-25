package br.malhaviva.feature.tracking.net

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.core.domain.sampling.SampleBatch
import br.malhaviva.feature.tracking.BatchUploader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Fornece o token de atestado do aparelho. A versao de release devolve null ate a integracao real do
 * Play Integrity: sem token, nenhum lote sai do aparelho.
 */
fun interface IntegrityTokenProvider {
    suspend fun token(): String?
}

/**
 * Envio assinado dos lotes. Token anonimo so em memoria, renovado quando faltam menos de 5 minutos.
 * Nunca registra coordenadas nem tokens em log. Lotes sao enviados em ordem (um por vez).
 */
class HttpBatchUploader(
    baseUrl: String,
    private val transport: HttpTransport,
    private val integrity: IntegrityTokenProvider,
    private val corridor: String = CORRIDOR,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newNonce: () -> String = BatchSigner::newNonce,
) : BatchUploader {
    private val base = baseUrl.trimEnd('/')
    private val lock = Mutex()
    private var session: Session? = null

    private class Session(
        val token: String,
        val signingKey: String,
        val anonEpochId: String,
        val expiresAtMs: Long,
    )

    override suspend fun upload(batch: SampleBatch): Boolean =
        lock.withLock {
            orNull {
                val current = validSession() ?: return@orNull false
                val body = batch.toJson(current.anonEpochId, corridor).toByteArray(Charsets.UTF_8)
                val timestamp = clock()
                val nonce = newNonce()
                val headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer ${current.token}",
                        "X-Timestamp" to timestamp.toString(),
                        "X-Nonce" to nonce,
                        "X-Signature" to BatchSigner.signature(current.signingKey, timestamp, nonce, body),
                    )
                val status = transport.execute(HttpRequest("POST", "$base/v1/telemetry/batches", headers, body)).status
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) session = null
                status == HttpURLConnection.HTTP_ACCEPTED
            } ?: false
        }

    private suspend fun validSession(): Session? {
        val cached = session
        if (cached != null && cached.expiresAtMs - clock() >= REFRESH_MARGIN_MS) return cached
        return integrity.token()?.let { fetchSession(it) }?.also { session = it }
    }

    private suspend fun fetchSession(attestation: String): Session? {
        val request =
            HttpRequest(
                method = "POST",
                url = "$base/v1/telemetry/tokens",
                headers = mapOf("Content-Type" to "application/json"),
                body =
                    JSONObject()
                        .put("platform", "android")
                        .put("integrity_token", attestation)
                        .toString()
                        .toByteArray(Charsets.UTF_8),
            )
        val response = transport.execute(request)
        if (response.status != HttpURLConnection.HTTP_CREATED) return null
        val json = JSONObject(response.body)
        return Session(
            token = json.getString("token"),
            signingKey = json.getString("signing_key"),
            anonEpochId = json.getString("anon_epoch_id"),
            expiresAtMs = Instant.parse(json.getString("expires_at")).toEpochMilli(),
        )
    }

    private inline fun <T> orNull(block: () -> T): T? =
        try {
            block()
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: DateTimeParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val CORRIDOR = "RAMAL_SANTA_CRUZ"
        const val REFRESH_MARGIN_MS = 5 * 60_000L
    }
}

private const val FULL_TURN_DEG = 360f

internal fun SampleBatch.toJson(
    anonEpochId: String,
    corridor: String,
): String {
    val array = JSONArray()
    samples.forEach {
        val sample =
            JSONObject()
                .put("seq", it.seq)
                .put("ts", Instant.ofEpochMilli(it.timeMs).toString())
                .put("lat", it.lat)
                .put("lon", it.lon)
                .put("accuracy_m", it.accuracyM.toDouble())
                .put("speed_mps", it.speedMs)
                .put("mock_location", false)
        // contrato: 0 <= bearing_deg < 360; valor fora disso nao e enviado
        it.bearingDeg
            ?.takeIf { b ->
                b >= 0f && b < FULL_TURN_DEG
            }?.let { b -> sample.put("bearing_deg", b.toDouble()) }
        array.put(sample)
    }
    return JSONObject()
        .put("anon_epoch_id", anonEpochId)
        .put("corridor", corridor)
        .put("samples", array)
        .toString()
}
