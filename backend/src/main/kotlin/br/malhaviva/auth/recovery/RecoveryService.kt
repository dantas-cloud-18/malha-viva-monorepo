package br.malhaviva.auth.recovery

import br.malhaviva.auth.AccountRecord
import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.CaptchaVerifier
import br.malhaviva.auth.PasswordHasher
import br.malhaviva.auth.PasswordPolicy
import br.malhaviva.auth.PasswordRejection
import br.malhaviva.auth.PolicyResult
import br.malhaviva.auth.SessionService
import br.malhaviva.auth.isValidHandle
import br.malhaviva.platform.DEFAULT_MAX_KEYS
import br.malhaviva.platform.ExpiringMap
import br.malhaviva.tracking.ingest.RateLimiter
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("br.malhaviva.auth.recovery.RecoveryService")
private const val FREE_REQUESTS_BEFORE_CAPTCHA = 3
private const val COUNTER_WINDOW_MS = 60 * 60_000L
private const val REQUEST_CAPACITY = 5.0
private const val REQUEST_REFILL_PER_S = 1.0 / 30
private const val COMPLETE_CAPACITY = 10.0
private const val COMPLETE_REFILL_PER_S = 1.0 / 30
private const val RETRY_AFTER_S = 60L
private const val DEFAULT_MIN_COMPLETE_MS = 150L
private const val NANOS_PER_MS = 1_000_000L
private val RECOVERY_CONTEXT = SendContext(SendPurpose.RECOVERY)
private val PROBE_ACCOUNT = UUID(0L, 0L)

/**
 * Conta pedidos por chave numa janela; a chave some quando a janela passa sem uso e a tabela tem teto
 * ([ExpiringMap]). shortcut: de um no so; trocar por Redis com TTL ao escalar.
 */
class RequestCounter(
    private val windowMs: Long = COUNTER_WINDOW_MS,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private class Hits(
        val startMs: Long,
        val count: Int,
    )

    private val hits = ExpiringMap<String, Hits>(DEFAULT_MAX_KEYS, Duration.ofMillis(windowMs), ticker)

    fun hit(
        key: String,
        nowMs: Long,
    ): Int =
        hits.map
            .compute(key) { _, old ->
                if (old == null || nowMs - old.startMs >= windowMs) Hits(nowMs, 1) else Hits(old.startMs, old.count + 1)
            }?.count ?: 1

    /** Chaves vivas (para testes e metricas). */
    fun trackedKeys(): Long = hits.size()
}

/**
 * Limites do fluxo publico: dependem so de IP e do texto do handle, nunca da existencia da conta. O limite por IP
 * ([requestIp], [completeIp]) vale sem ler o corpo; o do handle usa a chave `ip|handle`, para que um atacante nao
 * esgote o balde da vitima (a protecao por conta e o CAPTCHA do [RequestCounter], nunca um 429 duro).
 */
class RecoveryLimits(
    val requestIp: RateLimiter = RateLimiter(REQUEST_CAPACITY, REQUEST_REFILL_PER_S),
    val requestHandle: RateLimiter = RateLimiter(REQUEST_CAPACITY, REQUEST_REFILL_PER_S),
    val completeIp: RateLimiter = RateLimiter(COMPLETE_CAPACITY, COMPLETE_REFILL_PER_S),
    val completeHandle: RateLimiter = RateLimiter(COMPLETE_CAPACITY, COMPLETE_REFILL_PER_S),
    val counter: RequestCounter = RequestCounter(),
)

sealed interface RecoveryRequestResult {
    data object Accepted : RecoveryRequestResult

    data object CaptchaRequired : RecoveryRequestResult

    data class Throttled(
        val retryAfterSeconds: Long = RETRY_AFTER_S,
    ) : RecoveryRequestResult
}

sealed interface RecoveryCompleteResult {
    data object Done : RecoveryCompleteResult

    /** Handle inexistente, OTP errado, vencido, ausente ou sem canal verificado: tudo igual. */
    data object InvalidCode : RecoveryCompleteResult

    data class PasswordRejected(
        val reason: PasswordRejection,
    ) : RecoveryCompleteResult

    data class Throttled(
        val retryAfterSeconds: Long = RETRY_AFTER_S,
    ) : RecoveryCompleteResult
}

class RecoveryCodes(
    val phone: String? = null,
    val email: String? = null,
) {
    fun forChannel(channel: Channel): String? = if (channel == Channel.PHONE) phone else email
}

/**
 * Recuperacao: pedido, OTP em cada canal verificado (os DOIS se houver dois: anti-SIM swap) e senha nova.
 *
 * Nao revela se a conta existe: o pedido responde sempre igual e todo o trabalho que depende da conta roda em
 * segundo plano ([background]); a conclusao falha igual para handle inexistente e OTP errado. O canal que nao
 * recebe OTP recebe um aviso. Ao concluir: senha nova, TODAS as sessoes revogadas, trocas de contato
 * pendentes canceladas e aviso a todos os canais. O TOTP NAO e removido: depois do reset o login segue exigindo o
 * codigo (obrigatorio para admin), entao a recuperacao nao contorna o 2FA.
 *
 * Suppress: injecao de dependencias (agrupar so deslocaria os parametros do construtor) e um unico fluxo de
 * recuperacao (dividir partiria os limites e o estado compartilhados).
 */
@Suppress("LongParameterList", "TooManyFunctions")
class RecoveryService(
    private val accounts: AccountRepository,
    private val contacts: ContactRepository,
    private val contactService: ContactService,
    private val changes: ContactChangeRepository,
    private val otp: OtpService,
    private val gateway: MessageGateway,
    private val sessions: SessionService,
    private val policy: PasswordPolicy,
    private val hasher: PasswordHasher,
    private val captcha: CaptchaVerifier,
    private val limits: RecoveryLimits = RecoveryLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Duracao minima do caminho de falha de [complete]: iguala handle inexistente e conta com OTP errado. */
    private val minCompleteMs: Long = DEFAULT_MIN_COMPLETE_MS,
    private val background: (suspend () -> Unit) -> Unit,
) {
    /** Limite por IP do pedido; as rotas consultam ANTES de ler o corpo. */
    fun admitRequestIp(ip: String): Boolean = limits.requestIp.tryAcquire("ip:$ip", clock())

    /** Limite por IP da conclusao; as rotas consultam ANTES de ler o corpo. */
    fun admitCompleteIp(ip: String): Boolean = limits.completeIp.tryAcquire("ip:$ip", clock())

    /** O limite por IP ja foi cobrado por [admitRequestIp]; aqui entram o par IP+handle e o CAPTCHA. */
    suspend fun request(
        ip: String,
        handle: String,
        captchaToken: String?,
    ): RecoveryRequestResult {
        val key = handle.lowercase()
        val now = clock()
        return when {
            // formato invalido nunca e conta: mesma resposta, sem criar estado por chave
            !isValidHandle(handle) -> RecoveryRequestResult.Accepted
            !limits.requestHandle.tryAcquire("p:$ip|$key", now) -> RecoveryRequestResult.Throttled()
            captchaNeeded(ip, key, now) && !captcha.verify(captchaToken, ip) -> RecoveryRequestResult.CaptchaRequired
            else -> {
                background { process(key) }
                RecoveryRequestResult.Accepted
            }
        }
    }

    private fun captchaNeeded(
        ip: String,
        key: String,
        now: Long,
    ): Boolean =
        maxOf(limits.counter.hit("ip:$ip", now), limits.counter.hit("h:$key", now)) > FREE_REQUESTS_BEFORE_CAPTCHA

    private suspend fun process(handle: String) {
        val account = accounts.findByHandle(handle) ?: return
        contactService.applyDue(account.id)
        val info = contacts.find(account.id)
        val verified = info.verifiedChannels()
        if (verified.isEmpty()) log.info("Recuperacao pedida para conta sem canal verificado")
        verified.forEach { ch -> info.value(ch)?.let { sendCode(account, ch, it) } }
        Channel.entries.filter { it !in verified }.forEach { ch ->
            info.value(ch)?.let { notice(account, ch, it, "Pedido de recuperacao de conta. Se nao foi voce, ignore.") }
        }
    }

    private suspend fun sendCode(
        account: AccountRecord,
        channel: Channel,
        to: String,
    ) {
        val code = otp.issue(account.id, OtpPurpose.RECOVERY, channel, "")
        val text = "codigo de recuperacao $code. Valido por 10 min. Se nao foi voce, ignore e troque sua senha."
        // so chega aqui para canal verificado: usa a cota reservada de recuperacao
        gateway.deliver(account.id, channel, to, "Recuperacao Malha Viva", "Malha Viva: $text", RECOVERY_CONTEXT)
    }

    private suspend fun notice(
        account: AccountRecord,
        channel: Channel,
        to: String,
        text: String,
    ) {
        gateway.deliver(account.id, channel, to, "Aviso Malha Viva", "Malha Viva: $text")
    }

    /** O limite por IP ja foi cobrado por [admitCompleteIp]; aqui entra o par IP+handle (formato valido). */
    suspend fun complete(
        ip: String,
        handle: String,
        codes: RecoveryCodes,
        newPassword: String,
    ): RecoveryCompleteResult {
        val key = handle.lowercase()
        val throttled = isValidHandle(handle) && !limits.completeHandle.tryAcquire("p:$ip|$key", clock())
        return if (throttled) {
            RecoveryCompleteResult.Throttled()
        } else {
            when (val checked = policy.check(newPassword, listOf(key.removePrefix("@")))) {
                is PolicyResult.Rejected -> RecoveryCompleteResult.PasswordRejected(checked.reason)
                is PolicyResult.Accepted -> finish(key, codes, checked.normalized)
            }
        }
    }

    /**
     * Mesmo formato de trabalho para conta existente e inexistente: uma leitura de contatos e uma consulta de OTP
     * por canal (a inexistente usa [OtpService.probe]); o caminho de falha ainda espera [minCompleteMs].
     * Risco residual: a conta existente grava tentativas de OTP (escrita) que a inexistente nao faz; a espera
     * minima cobre a diferenca de tempo, nao de trabalho.
     */
    private suspend fun finish(
        handle: String,
        codes: RecoveryCodes,
        normalizedPassword: String,
    ): RecoveryCompleteResult {
        val startedNs = System.nanoTime()
        val account = accounts.findByHandle(handle)
        val ok = if (account == null) probeAll(codes) else allCodesValid(account, codes)
        return if (ok && account != null) {
            applyReset(account, normalizedPassword)
            RecoveryCompleteResult.Done
        } else {
            val left = minCompleteMs - (System.nanoTime() - startedNs) / NANOS_PER_MS
            if (left > 0) delay(left)
            RecoveryCompleteResult.InvalidCode
        }
    }

    /** Conta inexistente: leitura de contatos e consulta de OTP em todos os canais, contra uma conta descartavel. */
    private fun probeAll(codes: RecoveryCodes): Boolean {
        contacts.find(PROBE_ACCOUNT)
        Channel.entries.forEach { otp.probe(it, codes.forChannel(it)) }
        return false
    }

    /** Exige o OTP de cada canal verificado; so consome quando todos conferem. */
    private fun allCodesValid(
        account: AccountRecord,
        codes: RecoveryCodes,
    ): Boolean {
        val required = contacts.find(account.id).verifiedChannels()
        val records =
            required.map { ch -> otp.check(account.id, OtpPurpose.RECOVERY, ch, "", codes.forChannel(ch)) }
        Channel.entries.filter { it !in required }.forEach { otp.probe(it, codes.forChannel(it)) }
        return required.isNotEmpty() && records.all { it != null } && records.filterNotNull().all(otp::consume)
    }

    private suspend fun applyReset(
        account: AccountRecord,
        normalizedPassword: String,
    ) {
        accounts.updatePasswordHash(account.id, hasher.hash(normalizedPassword))
        sessions.revokeAll(account.id)
        changes.cancel(account.id, null, Instant.ofEpochMilli(clock()))
        val info = contacts.find(account.id)
        Channel.entries.forEach { ch ->
            info
                .value(
                    ch,
                )?.let { notice(account, ch, it, "Sua senha foi alterada e todas as sessoes foram encerradas.") }
        }
        log.info("Recuperacao de conta concluida; sessoes revogadas")
    }
}
