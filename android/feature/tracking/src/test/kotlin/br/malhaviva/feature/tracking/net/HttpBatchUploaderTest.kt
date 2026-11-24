package br.malhaviva.feature.tracking.net

import br.malhaviva.core.domain.sampling.Sample
import br.malhaviva.core.domain.sampling.SampleBatch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

private class FakeTransport(
    private val tokenExpiresAt: () -> String,
    var batchStatus: Int = 202,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var tokenCalls = 0
    var failNetwork = false

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (failNetwork) throw IOException("sem rede")
        return if (request.url.endsWith("/v1/telemetry/tokens")) {
            tokenCalls++
            val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
            val body =
                """{"token":"tok-$tokenCalls","signing_key":"$key","anon_epoch_id":"epoch-$tokenCalls",""" +
                    """"expires_at":"${tokenExpiresAt()}"}"""
            HttpResponse(201, body)
        } else {
            HttpResponse(batchStatus, "{}")
        }
    }
}

class HttpBatchUploaderTest {
    private var now = 1_800_000_000_000L
    private val batch =
        SampleBatch(listOf(Sample(0, now, -22.9, -43.2, 5.5f, 12.0), Sample(1, now + 1_000, -22.91, -43.21, 5f, 0.0)))

    private fun iso(ms: Long) =
        java.time.Instant
            .ofEpochMilli(ms)
            .toString()

    private fun uploader(
        transport: HttpTransport,
        attestation: String? = "dev-integrity",
    ): HttpBatchUploader {
        var n = 0
        return HttpBatchUploader(
            baseUrl = "http://host:8080/",
            transport = transport,
            integrity = { attestation },
            clock = { now },
            newNonce = { "nonce-%016d".format(n++) },
        )
    }

    @Test
    fun `sends token request then signed batch and returns true on 202`() =
        runTest {
            val http = FakeTransport({ iso(now + 6 * 3_600_000L) })
            assertTrue(uploader(http).upload(batch))
            val (tokenReq, batchReq) = http.requests
            assertEquals("http://host:8080/v1/telemetry/tokens", tokenReq.url)
            val tokenBody = JSONObject(String(tokenReq.body!!))
            assertEquals("android", tokenBody.getString("platform"))
            assertEquals("dev-integrity", tokenBody.getString("integrity_token"))
            assertEquals("http://host:8080/v1/telemetry/batches", batchReq.url)
            assertEquals("Bearer tok-1", batchReq.headers["Authorization"])
            assertEquals(now.toString(), batchReq.headers["X-Timestamp"])
            assertTrue(batchReq.headers.getValue("X-Nonce").length in 16..64)
            val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
            assertEquals(
                BatchSigner.signature(key, now, batchReq.headers.getValue("X-Nonce"), batchReq.body!!),
                batchReq.headers["X-Signature"],
            )
            val json = JSONObject(String(batchReq.body!!))
            assertEquals("epoch-1", json.getString("anon_epoch_id"))
            assertEquals("RAMAL_SANTA_CRUZ", json.getString("corridor"))
            val first = json.getJSONArray("samples").getJSONObject(0)
            assertEquals(
                setOf("seq", "ts", "lat", "lon", "accuracy_m", "speed_mps", "mock_location"),
                first.keys().asSequence().toSet(),
            )
            assertFalse(first.getBoolean("mock_location"))
            assertEquals(iso(now), first.getString("ts"))
        }

    @Test
    fun `token is reused while valid and refreshed under 5 minutes left`() =
        runTest {
            val http = FakeTransport({ iso(now + 10 * 60_000L) })
            val up = uploader(http)
            assertTrue(up.upload(batch))
            now += 4 * 60_000L // faltam 6 min: reaproveita
            assertTrue(up.upload(batch))
            assertEquals(1, http.tokenCalls)
            now += 2 * 60_000L // faltam 4 min: renova
            assertTrue(up.upload(batch))
            assertEquals(2, http.tokenCalls)
            val nonces = http.requests.filter { it.url.endsWith("batches") }.map { it.headers["X-Nonce"] }
            assertEquals(nonces.size, nonces.toSet().size)
        }

    @Test
    fun `non 202 and network failure return false and 401 drops the token`() =
        runTest {
            val http = FakeTransport({ iso(now + 6 * 3_600_000L) }, batchStatus = 429)
            val up = uploader(http)
            assertFalse(up.upload(batch))
            http.batchStatus = 401
            assertFalse(up.upload(batch))
            http.batchStatus = 202
            assertTrue(up.upload(batch))
            assertEquals(2, http.tokenCalls)
            http.failNetwork = true
            assertFalse(up.upload(batch))
        }

    @Test
    fun `without attestation token nothing is sent`() =
        runTest {
            val http = FakeTransport({ iso(now + 6 * 3_600_000L) })
            assertFalse(uploader(http, attestation = null).upload(batch))
            assertTrue(http.requests.isEmpty())
        }
}
