package br.malhaviva.lgpd

import br.malhaviva.auth.AgeBracket
import br.malhaviva.auth.recovery.Channel
import br.malhaviva.auth.recovery.normalizeContact
import br.malhaviva.auth.recovery.normalizeEmail
import br.malhaviva.auth.recovery.normalizePhone
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val CHILD_UNTIL = 12L
private const val ADULT_FROM = 18L
private const val MAX_AGE_YEARS = 120L

/** Contato do responsavel como chega no cadastro (canal: `phone` ou `email`). */
@Serializable
class GuardianDto(
    val channel: String,
    val value: String,
)

data class Guardian(
    val channel: Channel,
    val value: String,
)

sealed interface SignupAge {
    /** [guardian] so existe para menor de 12 anos. */
    data class Ok(
        val bracket: AgeBracket,
        val guardian: Guardian? = null,
    ) : SignupAge

    /** [code] vai no `detail` do 400. */
    data class Rejected(
        val code: String,
    ) : SignupAge
}

/** Faixa pela idade completa em [today]; null para data futura ou irreal (> 120 anos). */
fun ageBracketOf(
    birth: LocalDate,
    today: LocalDate,
): AgeBracket? {
    val years = ChronoUnit.YEARS.between(birth, today)
    return when {
        birth.isAfter(today) || years > MAX_AGE_YEARS -> null
        years < CHILD_UNTIL -> AgeBracket.UNDER_12
        years < ADULT_FROM -> AgeBracket.TEEN
        else -> AgeBracket.ADULT
    }
}

/**
 * Portao de idade autodeclarado do cadastro. A data de nascimento so serve para a faixa e e descartada.
 * Menor de 12 exige contato do responsavel, diferente dos contatos da propria conta (senao o OTP parental
 * seria lido pela crianca sem ninguem alem dela).
 */
fun evaluateSignupAge(
    birthDate: String?,
    guardian: GuardianDto?,
    ownContacts: List<String?>,
    today: LocalDate,
): SignupAge {
    val bracket = birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { ageBracketOf(it, today) }
    return when {
        birthDate == null -> SignupAge.Rejected("birth_date_required")
        bracket == null -> SignupAge.Rejected("invalid_birth_date")
        bracket != AgeBracket.UNDER_12 -> SignupAge.Ok(bracket)
        else ->
            guardianFor(guardian, ownContacts)?.let { SignupAge.Ok(bracket, it) }
                ?: SignupAge.Rejected("guardian_contact_required")
    }
}

private fun guardianFor(
    dto: GuardianDto?,
    ownContacts: List<String?>,
): Guardian? {
    val channel = Channel.fromWire(dto?.channel)
    val value = if (dto != null && channel != null) normalizeContact(channel, dto.value) else null
    val own = ownContacts.filterNotNull().map { normalizePhone(it) ?: normalizeEmail(it) ?: it }
    return if (channel != null && value != null && value !in own) Guardian(channel, value) else null
}
