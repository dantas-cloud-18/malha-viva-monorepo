package br.malhaviva.auth.recovery

import br.malhaviva.auth.sha256Hex
import br.malhaviva.tracking.ingest.RateLimiter
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("br.malhaviva.auth.recovery.SendGuard")
private const val MS_PER_DAY = 86_400_000L
const val DEFAULT_SMS_DAILY_CEILING = 500
const val DEFAULT_EMAIL_DAILY_CEILING = 2000
private const val DDI_CAPACITY = 20.0
private const val DDI_REFILL_PER_S = 1.0 / 60
private const val TARGET_CAPACITY = 3.0
private const val TARGET_REFILL_PER_S = 1.0 / 600
private const val ACCOUNT_CAPACITY = 6.0
private const val ACCOUNT_REFILL_PER_S = 1.0 / 600
private const val MAX_DDI_DIGITS = 3

// shortcut: tabela parcial de DDIs de 2 digitos; DDI de 1 digito so 1 e 7; o resto cai em 3 digitos.
private val TWO_DIGIT_CODES =
    setOf(
        "20",
        "27",
        "30",
        "31",
        "32",
        "33",
        "34",
        "36",
        "39",
        "40",
        "41",
        "43",
        "44",
        "45",
        "46",
        "47",
        "48",
        "49",
        "51",
        "52",
        "53",
        "54",
        "55",
        "56",
        "57",
        "58",
        "60",
        "61",
        "62",
        "63",
        "64",
        "65",
        "66",
        "81",
        "82",
        "84",
        "86",
        "90",
        "91",
        "92",
        "93",
        "94",
        "95",
        "98",
    )

/** DDI de um telefone E.164 (aproximado, ver tabela parcial acima). */
fun callingCode(e164: String): String {
    val digits = e164.removePrefix("+")
    return when {
        digits.startsWith("1") || digits.startsWith("7") -> digits.take(1)
        digits.take(2) in TWO_DIGIT_CODES -> digits.take(2)
        else -> digits.take(MAX_DDI_DIGITS)
    }
}

/**
 * Teto diario de mensagens (dia UTC). Estourado, o envio para (parada dura) e sai um alerta no log, uma vez
 * por dia, sem dado pessoal. shortcut: contador em memoria de um no; trocar por contador compartilhado.
 */
class SpendCeiling(
    private val name: String,
    private val maxPerDay: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var day = -1L
    private var count = 0
    private var alerted = false

    @Synchronized
    fun tryConsume(): Boolean {
        val today = clock() / MS_PER_DAY
        if (today != day) {
            day = today
            count = 0
            alerted = false
        }
        val ok = count < maxPerDay
        if (ok) count++ else alertOnce()
        return ok
    }

    private fun alertOnce() {
        if (!alerted) {
            alerted = true
            log.error("ALERTA teto diario de mensagens ({}) atingido, limite {}; envios bloqueados", name, maxPerDay)
        }
    }
}

/** Anti-SMS pumping: limite por DDI, por numero (ou e-mail), por conta e teto diario por canal. */
class SendLimits(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ddi: RateLimiter = RateLimiter(DDI_CAPACITY, DDI_REFILL_PER_S),
    private val target: RateLimiter = RateLimiter(TARGET_CAPACITY, TARGET_REFILL_PER_S),
    private val account: RateLimiter = RateLimiter(ACCOUNT_CAPACITY, ACCOUNT_REFILL_PER_S),
    private val sms: SpendCeiling = SpendCeiling("sms", DEFAULT_SMS_DAILY_CEILING, clock),
    private val email: SpendCeiling = SpendCeiling("email", DEFAULT_EMAIL_DAILY_CEILING, clock),
) {
    /** Chaves de limite nunca guardam o contato em claro: so o SHA-256. */
    fun allow(
        accountId: UUID,
        channel: Channel,
        to: String,
    ): Boolean {
        val now = clock()
        val ddiOk = channel != Channel.PHONE || ddi.tryAcquire("ddi:${callingCode(to)}", now)
        return ddiOk &&
            target.tryAcquire("t:${sha256Hex(to)}", now) &&
            account.tryAcquire("a:$accountId", now) &&
            (if (channel == Channel.PHONE) sms else email).tryConsume()
    }
}

/** Envia com limites e sem nunca propagar falha do provedor (o chamador responde igual de qualquer jeito). */
class MessageGateway(
    private val sender: MessageSender,
    private val limits: SendLimits,
) {
    suspend fun deliver(
        accountId: UUID,
        channel: Channel,
        to: String,
        subject: String,
        text: String,
    ): Boolean =
        if (limits.allow(accountId, channel, to)) {
            try {
                if (channel == Channel.PHONE) sender.sendSms(to, text) else sender.sendEmail(to, subject, text)
                true
            } catch (e: MessageDeliveryException) {
                log.error("Falha ao enviar mensagem pelo canal {}: {}", channel.wire, e.message)
                false
            }
        } else {
            log.warn("Envio suprimido por limite no canal {}", channel.wire)
            false
        }
}
