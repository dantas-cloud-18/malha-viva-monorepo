package br.malhaviva.auth.recovery

import br.malhaviva.auth.AccountRecord
import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.CaptchaVerifier
import br.malhaviva.auth.PasswordHasher
import br.malhaviva.auth.PasswordPolicy
import br.malhaviva.auth.PasswordRejection
import br.malhaviva.auth.PolicyResult
import br.malhaviva.auth.SessionService
import br.malhaviva.tracking.ingest.RateLimiter
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("br.malhaviva.auth.recovery.RecoveryService")
private const val FREE_REQUESTS_BEFORE_CAPTCHA = 3
private const val COUNTER_WINDOW_MS = 60 * 60_000L
private const val REQUEST_CAPACITY = 5.0
private const val REQUEST_REFILL_PER_S = 1.0 / 30
private const val COMPLETE_CAPACITY = 10.0
private const val COMPLETE_REFILL_PER_S = 1.0 / 30
private const val RETRY_AFTER_S = 60L

/** Conta pedidos por chave numa janela. shortcut: mapa em memoria que nunca e limpo; trocar por Redis com TTL. */
class RequestCounter(
    private val windowMs: Long = COUNTER_WINDOW_MS,
) {
    private class Hits(
        val startMs: Long,
        val count: Int,
    )

    private val hits = ConcurrentHashMap<String, Hits>()

    fun hit(
        key: String,
        nowMs: Long,
    ): Int =
        hits
            .compute(key) { _, old ->
                if (old == null || nowMs - old.startMs >= windowMs) Hits(nowMs, 1) else Hits(old.startMs, old.count + 1)
            }?.count ?: 1
}

/** Limites do fluxo publico: dependem so de IP e do texto do handle, nunca da existencia da conta. */
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
 * Suppress: injecao de dependencias; agrupar so deslocaria os parametros do construtor.
 */
@Suppress("LongParameterList")
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
    private val background: (suspend () -> Unit) -> Unit,
) {
    suspend fun request(
        ip: String,
        handle: String,
        captchaToken: String?,
    ): RecoveryRequestResult {
        val key = handle.lowercase()
        val now = clock()
        val throttled =
            !limits.requestIp.tryAcquire("ip:$ip", now) || !limits.requestHandle.tryAcquire("h:$key", now)
        return when {
            throttled -> RecoveryRequestResult.Throttled()
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
        gateway.deliver(account.id, channel, to, "Recuperacao Malha Viva", "Malha Viva: $text")
    }

    private suspend fun notice(
        account: AccountRecord,
        channel: Channel,
        to: String,
        text: String,
    ) {
        gateway.deliver(account.id, channel, to, "Aviso Malha Viva", "Malha Viva: $text")
    }

    suspend fun complete(
        ip: String,
        handle: String,
        codes: RecoveryCodes,
        newPassword: String,
    ): RecoveryCompleteResult {
        val key = handle.lowercase()
        val now = clock()
        val throttled =
            !limits.completeIp.tryAcquire("ip:$ip", now) || !limits.completeHandle.tryAcquire("h:$key", now)
        return if (throttled) {
            RecoveryCompleteResult.Throttled()
        } else {
            when (val checked = policy.check(newPassword, listOf(key.removePrefix("@")))) {
                is PolicyResult.Rejected -> RecoveryCompleteResult.PasswordRejected(checked.reason)
                is PolicyResult.Accepted -> finish(key, codes, checked.normalized)
            }
        }
    }

    private suspend fun finish(
        handle: String,
        codes: RecoveryCodes,
        normalizedPassword: String,
    ): RecoveryCompleteResult {
        val account = accounts.findByHandle(handle)
        val ok =
            if (account == null) {
                otp.burnTime(codes.phone)
                false
            } else {
                allCodesValid(account, codes)
            }
        return if (ok && account != null) {
            applyReset(account, normalizedPassword)
            RecoveryCompleteResult.Done
        } else {
            RecoveryCompleteResult.InvalidCode
        }
    }

    /** Exige o OTP de cada canal verificado; so consome quando todos conferem. */
    private fun allCodesValid(
        account: AccountRecord,
        codes: RecoveryCodes,
    ): Boolean {
        val required = contacts.find(account.id).verifiedChannels()
        val records =
            required.map { ch -> otp.check(account.id, OtpPurpose.RECOVERY, ch, "", codes.forChannel(ch)) }
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
