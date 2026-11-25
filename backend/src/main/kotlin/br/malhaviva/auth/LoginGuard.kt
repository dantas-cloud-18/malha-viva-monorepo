package br.malhaviva.auth

import br.malhaviva.platform.DEFAULT_MAX_KEYS
import br.malhaviva.platform.ExpiringMap
import com.github.benmanes.caffeine.cache.Ticker
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Gancho de CAPTCHA, exigido apos [CAPTCHA_AFTER_FAILURES] falhas por handle ou IP.
 *
 * Producao exige um provedor real (hCaptcha, Turnstile, reCAPTCHA): [AuthConfig] nao sobe sem um.
 */
interface CaptchaVerifier {
    suspend fun verify(
        token: String?,
        ip: String,
    ): Boolean
}

/**
 * ATENCAO: NAO E PARA PRODUCAO. Aceita qualquer token nao vazio, entao so prova que o cliente sabe
 * enviar o campo. So entra com `MALHAVIVA_DEV_CAPTCHA=true` (desenvolvimento e testes).
 */
object NoopCaptchaVerifier : CaptchaVerifier {
    override suspend fun verify(
        token: String?,
        ip: String,
    ): Boolean = !token.isNullOrBlank()
}

const val CAPTCHA_AFTER_FAILURES = 5
private const val DELAY_AFTER_FAILURES = 3
private const val BASE_DELAY_MS = 1_000L
private const val MAX_DELAY_MS = 15 * 60_000L
private const val FAILURE_MEMORY_MS = 15 * 60_000L
private const val STUFFING_WINDOW_MS = 10 * 60_000L
private const val STUFFING_DISTINCT_HANDLES = 20
private const val MS_PER_SECOND = 1000L
private val log = LoggerFactory.getLogger("br.malhaviva.auth.LoginGuard")

sealed interface GuardDecision {
    data object Allow : GuardDecision

    data object CaptchaRequired : GuardDecision

    data class Blocked(
        val retryAfterSeconds: Long,
    ) : GuardDecision
}

/**
 * Atraso progressivo, CAPTCHA e sinal de credential stuffing. Estado em memoria de um unico no.
 *
 * Atraso vale por par IP+handle e por IP (um atacante nao tranca a conta alheia; CAPTCHA por handle
 * deixa o dono legitimo entrar). Logs nunca trazem IP nem handle, so contagens.
 * Os mapas expiram por inatividade e tem teto de tamanho ([ExpiringMap]).
 * shortcut: nao sao compartilhados entre instancias; trocar por Redis com TTL.
 */
class LoginGuard(
    ticker: Ticker = Ticker.systemTicker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Failures(
        val count: Int,
        val lastMs: Long,
    )

    private class Window(
        val startMs: Long,
        val handles: MutableSet<String>,
        var blocked: Boolean = false,
    )

    private val failureStore =
        ExpiringMap<String, Failures>(DEFAULT_MAX_KEYS, Duration.ofMillis(FAILURE_MEMORY_MS), ticker)
    private val windowStore =
        ExpiringMap<String, Window>(DEFAULT_MAX_KEYS, Duration.ofMillis(STUFFING_WINDOW_MS), ticker)
    private val failures get() = failureStore.map
    private val windows get() = windowStore.map

    /** Entradas vivas nos dois mapas (para testes e metricas). */
    fun trackedKeys(): Long = failureStore.size() + windowStore.size()

    fun check(
        ip: String,
        handle: String,
    ): GuardDecision {
        val now = clock()
        val retryMs =
            maxOf(
                stuffingRemainingMs(ip, handle, now),
                delayRemainingMs("p:$ip|$handle", now),
                delayRemainingMs("i:$ip", now),
            )
        return when {
            retryMs > 0 -> GuardDecision.Blocked((retryMs + MS_PER_SECOND - 1) / MS_PER_SECOND)
            count("h:$handle", now) >= CAPTCHA_AFTER_FAILURES || count("i:$ip", now) >= CAPTCHA_AFTER_FAILURES ->
                GuardDecision.CaptchaRequired
            else -> GuardDecision.Allow
        }
    }

    fun recordFailure(
        ip: String,
        handle: String,
    ) {
        val now = clock()
        listOf("h:$handle", "i:$ip", "p:$ip|$handle").forEach { key ->
            failures.merge(key, Failures(1, now)) { old, _ ->
                Failures(if (now - old.lastMs > FAILURE_MEMORY_MS) 1 else old.count + 1, now)
            }
        }
    }

    /** Sucesso limpa handle e par; o contador do IP segue, pois um acerto nao absolve o IP. */
    fun recordSuccess(
        ip: String,
        handle: String,
    ) {
        failures.remove("h:$handle")
        failures.remove("p:$ip|$handle")
    }

    private fun count(
        key: String,
        now: Long,
    ): Int = failures[key]?.takeIf { now - it.lastMs <= FAILURE_MEMORY_MS }?.count ?: 0

    private fun delayRemainingMs(
        key: String,
        now: Long,
    ): Long {
        val f = failures[key]
        val n = count(key, now)
        return if (f == null || n < DELAY_AFTER_FAILURES) {
            0L
        } else {
            val delay = min(MAX_DELAY_MS, BASE_DELAY_MS shl min(n - DELAY_AFTER_FAILURES, MAX_SHIFT))
            maxOf(0L, f.lastMs + delay - now)
        }
    }

    /** Muitos handles distintos de um IP numa janela: bloqueia o IP ate a janela acabar. */
    private fun stuffingRemainingMs(
        ip: String,
        handle: String,
        now: Long,
    ): Long {
        windows.compute(ip) { _, old ->
            (old?.takeIf { now - it.startMs < STUFFING_WINDOW_MS } ?: Window(now, ConcurrentHashMap.newKeySet()))
                .also { if (!it.blocked) it.handles.add(handle) }
        }
        val w = windows.getValue(ip)
        if (!w.blocked && w.handles.size > STUFFING_DISTINCT_HANDLES) {
            w.blocked = true
            log.warn("Credential stuffing suspeito: {} handles distintos de um mesmo IP; IP bloqueado", w.handles.size)
        }
        return if (w.blocked) w.startMs + STUFFING_WINDOW_MS - now else 0L
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}
