package br.malhaviva.app

import br.malhaviva.core.data.net.HttpRequest
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.core.data.net.UrlConnectionTransport
import br.malhaviva.core.data.secure.InMemorySecretStore
import br.malhaviva.core.domain.sampling.Sample
import br.malhaviva.core.domain.sampling.SampleBatch
import br.malhaviva.feature.auth.AuthSession
import br.malhaviva.feature.auth.SessionState
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthApi
import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.LoginOutcome
import br.malhaviva.feature.auth.net.NewAccount
import br.malhaviva.feature.tracking.net.BatchSigner
import br.malhaviva.feature.tracking.net.HttpBatchUploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ponta a ponta contra um backend real. So roda com `MALHAVIVA_E2E_URL` (ex.: http://127.0.0.1:8080);
 * sem ela o teste e ignorado e o CI segue verde. O backend precisa de MALHAVIVA_DEV_CAPTCHA e
 * MALHAVIVA_DEV_INTEGRITY ligados, e do corredor RAMAL_SANTA_CRUZ carregado para o lote ser aceito.
 */
class E2eLocalBackendTest {
    private lateinit var url: String

    @Before
    fun requireBackend() {
        url = System.getenv("MALHAVIVA_E2E_URL").orEmpty()
        assumeTrue("MALHAVIVA_E2E_URL nao definida", url.isNotBlank())
    }

    @Test
    fun fullFlow() =
        runBlocking {
            val transport = UrlConnectionTransport()
            val api = AuthApi(url, transport)
            val account = AccountApi(url, transport)
            val secrets = InMemorySecretStore()
            val session = AuthSession(api, secrets, { "dev-captcha" })
            val handle = "@e2e_" + System.nanoTime().toString(36)
            val password = "E2e-" + java.util.UUID.randomUUID() // gerada por rodada; nunca fixa no codigo

            // cadastro e login simples
            assertOk(session.signup(NewAccount(handle, password, "1990-01-01"))) // adulto: sem responsavel
            assertOk(patient { session.login(handle, password, "e2e") })
            assertEquals(SessionState.LoggedIn, session.state.value)

            // TOTP: enrolamento e confirmacao (gera os 10 codigos de backup)
            val enrollment = assertOk(session.authed { account.startTotp(it) })
            val secret = base32Decode(enrollment.secret)
            val now = System.currentTimeMillis()
            val backupCodes = assertOk(session.authed { account.confirmTotp(it, totp(secret, now / STEP_MS)) })
            assertEquals(10, backupCodes.size)

            // logout derruba a sessao; login volta a pedir MFA
            session.logout()
            assertEquals(SessionState.LoggedOut, session.state.value)
            assertEquals(AuthError.Unauthorized, (session.authed { account.listDevices(it) } as ApiResult.Fail).error)

            val mfa = assertOk(patient { session.login(handle, password, "e2e") }) as LoginOutcome.MfaRequired
            // o passo atual ja foi gasto na confirmacao; o proximo cai na janela de +-1
            val next = totp(secret, now / STEP_MS + 1)
            assertEquals(
                AuthError.InvalidMfa,
                (patient { session.loginMfa(mfa.mfaToken, code = "000000") } as ApiResult.Fail).error,
            )
            assertOk(patient { session.loginMfa(mfa.mfaToken, code = next) })
            assertEquals(SessionState.LoggedIn, session.state.value)
            assertTrue(assertOk(session.authed { account.listDevices(it) }).isNotEmpty())

            // codigo de backup entra e e de uso unico
            val backup = backupCodes.first()
            val mfa2 = assertOk(patient { session.login(handle, password, "e2e") }) as LoginOutcome.MfaRequired
            assertOk(patient { session.loginMfa(mfa2.mfaToken, backupCode = backup) })
            val mfa3 = assertOk(patient { session.login(handle, password, "e2e") }) as LoginOutcome.MfaRequired
            assertEquals(
                AuthError.InvalidMfa,
                (patient { session.loginMfa(mfa3.mfaToken, backupCode = backup) } as ApiResult.Fail).error,
            )

            // rotacao do refresh: o antigo morre e o reuso derruba a familia
            val mfa4 =
                assertOk(patient { api.login(handle, password, "e2e", "dev-captcha") }) as LoginOutcome.MfaRequired
            val first = assertOk(patient { api.loginMfa(mfa4.mfaToken, backupCode = backupCodes[1]) })
            val second = assertOk(api.refresh(first.refreshToken))
            assertNotEquals(first.refreshToken, second.refreshToken)
            assertEquals(AuthError.Unauthorized, (api.refresh(first.refreshToken) as ApiResult.Fail).error)
            assertEquals(AuthError.Unauthorized, (api.refresh(second.refreshToken) as ApiResult.Fail).error)

            // sessao do app renova sozinha quando o access some (restore usa o refresh salvo)
            val restored = AuthSession(api, secrets, { "dev-captcha" })
            assertTrue(restored.restore())
            assertTrue(assertOk(restored.authed { account.listDevices(it) }).isNotEmpty())

            telemetryFlow(transport)
        }

    private suspend fun telemetryFlow(transport: HttpTransport) {
        // telemetria assinada: token anonimo com "dev-integrity" e lote HMAC
        val uploader = HttpBatchUploader(url, transport, { "dev-integrity" })
        val t0 = System.currentTimeMillis() - 30_000
        val samples =
            (0 until 5).map {
                Sample(it, t0 + it * 5_000L, -22.9, -43.195 + it * 0.0004, 10f, 8.0)
            }
        assertTrue("lote assinado deveria dar 202", uploader.upload(SampleBatch(samples)))

        // o corpo do 202 traz as contagens: as 5 amostras no corredor semeado entram; reuso de nonce da 409
        val tokenRequest = """{"platform":"android","integrity_token":"dev-integrity"}"""
        val issuedBody = postJson(transport, "/v1/telemetry/tokens", tokenRequest.toByteArray(), emptyMap()).body
        val issued = JSONObject(issuedBody)
        val body = batchJson(issued.getString("anon_epoch_id"), samples).toByteArray()
        val timestamp = System.currentTimeMillis()
        val nonce = BatchSigner.newNonce()
        val headers =
            mapOf(
                "Authorization" to "Bearer ${issued.getString("token")}",
                "X-Timestamp" to timestamp.toString(),
                "X-Nonce" to nonce,
                "X-Signature" to BatchSigner.signature(issued.getString("signing_key"), timestamp, nonce, body),
            )
        val accepted = postJson(transport, "/v1/telemetry/batches", body, headers)
        assertEquals(202, accepted.status)
        assertEquals(5, JSONObject(accepted.body).getInt("accepted"))
        assertEquals(409, postJson(transport, "/v1/telemetry/batches", body, headers).status)
    }

    private suspend fun postJson(
        transport: HttpTransport,
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
    ) = transport.execute(HttpRequest("POST", url + path, headers + ("Content-Type" to "application/json"), body))

    private fun batchJson(
        epoch: String,
        samples: List<Sample>,
    ): String {
        val array = JSONArray()
        samples.forEach {
            array.put(
                JSONObject()
                    .put("seq", it.seq)
                    .put("ts", Instant.ofEpochMilli(it.timeMs).toString())
                    .put("lat", it.lat)
                    .put("lon", it.lon)
                    .put("accuracy_m", it.accuracyM.toDouble())
                    .put("speed_mps", it.speedMs),
            )
        }
        return JSONObject()
            .put("anon_epoch_id", epoch)
            .put("corridor", HttpBatchUploader.CORRIDOR)
            .put("samples", array)
            .toString()
    }

    /** O guard de login atrasa de forma progressiva por IP (estado vive 15 min no backend): espera o Retry-After. */
    private suspend fun <T> patient(call: suspend () -> ApiResult<T>): ApiResult<T> {
        repeat(MAX_RETRIES) {
            val result = call()
            val limited = (result as? ApiResult.Fail)?.error as? AuthError.RateLimited ?: return result
            delay((limited.retryAfterSeconds ?: 1L) * 1_000L + 100L)
        }
        return call()
    }

    private fun <T> assertOk(result: ApiResult<T>): T =
        when (result) {
            is ApiResult.Ok -> result.value
            is ApiResult.Fail -> throw AssertionError("falhou: ${result.error}")
        }

    private fun totp(
        secret: ByteArray,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text.trimEnd('=').uppercase()) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val STEP_MS = 30_000L
        const val MAX_RETRIES = 8
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
