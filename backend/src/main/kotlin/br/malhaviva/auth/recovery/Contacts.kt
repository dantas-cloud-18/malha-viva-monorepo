package br.malhaviva.auth.recovery

import java.time.Instant
import java.util.UUID

private val PHONE_REGEX = Regex("^\\+[1-9][0-9]{7,14}$")
private val EMAIL_REGEX = Regex("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,63}$")
private const val MAX_EMAIL_LENGTH = 254

enum class Channel(
    val wire: String,
) {
    PHONE("phone"),
    EMAIL("email"),
    ;

    companion object {
        fun fromWire(value: String?): Channel? = entries.firstOrNull { it.wire == value }
    }
}

/** Telefone em E.164 valido ou null. */
fun normalizePhone(raw: String): String? = raw.trim().takeIf { PHONE_REGEX.matches(it) }

/** E-mail em minusculas ou null. Validacao propositalmente simples: a prova real e o OTP. */
fun normalizeEmail(raw: String): String? =
    raw.trim().lowercase().takeIf { it.length <= MAX_EMAIL_LENGTH && EMAIL_REGEX.matches(it) }

fun normalizeContact(
    channel: Channel,
    raw: String,
): String? =
    when (channel) {
        Channel.PHONE -> normalizePhone(raw)
        Channel.EMAIL -> normalizeEmail(raw)
    }

/** Contatos guardados da conta. Nunca vai para token, log ou resposta de outra conta. */
data class ContactInfo(
    val phone: String? = null,
    val phoneVerified: Boolean = false,
    val email: String? = null,
    val emailVerified: Boolean = false,
) {
    fun value(channel: Channel): String? = if (channel == Channel.PHONE) phone else email

    fun verified(channel: Channel): Boolean = if (channel == Channel.PHONE) phoneVerified else emailVerified

    fun verifiedChannels(): List<Channel> = Channel.entries.filter { verified(it) && value(it) != null }
}

interface ContactRepository {
    fun find(accountId: UUID): ContactInfo

    /** Grava o valor sem verificar (zera a verificacao anterior do canal). */
    fun store(
        accountId: UUID,
        channel: Channel,
        value: String,
    )

    /** Grava o valor ja verificado. False se outra conta ja verificou o mesmo contato. */
    fun setVerified(
        accountId: UUID,
        channel: Channel,
        value: String,
        now: Instant,
    ): Boolean
}

data class ContactChange(
    val id: UUID,
    val accountId: UUID,
    val channel: Channel,
    val newValue: String,
    val createdAt: Instant,
    val effectiveAt: Instant,
    val confirmedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val appliedAt: Instant? = null,
) {
    val open: Boolean get() = cancelledAt == null && appliedAt == null
}

interface ContactChangeRepository {
    fun create(change: ContactChange)

    fun find(id: UUID): ContactChange?

    /** Trocas ainda abertas (nem canceladas nem aplicadas) da conta. */
    fun open(accountId: UUID): List<ContactChange>

    fun markConfirmed(
        id: UUID,
        now: Instant,
    )

    fun markApplied(
        id: UUID,
        now: Instant,
    )

    /** Cancela as trocas abertas da conta (filtrando por canal quando informado); devolve quantas. */
    fun cancel(
        accountId: UUID,
        channel: Channel?,
        now: Instant,
    ): Int

    fun cancelOne(
        accountId: UUID,
        id: UUID,
        now: Instant,
    ): Boolean
}
