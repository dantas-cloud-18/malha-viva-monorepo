package br.malhaviva.feature.auth

import java.time.LocalDate
import java.time.Period

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 128
private val HANDLE_PATTERN = Regex("^@[a-zA-Z0-9_]{3,20}$")
private val PHONE_PATTERN = Regex("^\\+[1-9][0-9]{7,14}$")
internal const val TOTP_LENGTH = 6
const val DEFAULT_PHONE = "+55"

/** Politica NIST 800-63B: so o tamanho conta, sem regras de composicao. */
fun isPasswordValid(password: String): Boolean = password.length in PASSWORD_MIN..PASSWORD_MAX

fun normalizeHandle(raw: String): String = raw.trim().let { if (it.isEmpty() || it.startsWith("@")) it else "@$it" }

fun isHandleValid(raw: String): Boolean = HANDLE_PATTERN.matches(normalizeHandle(raw))

/** Tira espacos, hifens e parenteses da digitacao; o formato final e E.164. */
fun normalizePhone(raw: String): String = raw.filterNot { it.isWhitespace() || it in "-()." }

/** Campo ainda com o DDI padrao (ou vazio) conta como "sem telefone". */
fun isPhoneBlank(raw: String): Boolean = normalizePhone(raw).let { it.isEmpty() || it == "+" || it == DEFAULT_PHONE }

fun isPhoneValid(raw: String): Boolean = PHONE_PATTERN.matches(normalizePhone(raw))

fun isCodeValid(code: String): Boolean = code.length == TOTP_LENGTH && code.all { it in '0'..'9' }

/**
 * Versao da politica de privacidade que o app mostra e aceita. Tem de ser igual a vigente no servidor
 * (`MALHAVIVA_POLICY_VERSION`, padrao do backend `2026-11-25`); se divergir o servidor responde
 * 409 `policy_version_mismatch` e a tela pede para atualizar o app. Unico lugar do app com este valor.
 */
const val POLICY_VERSION = "2026-11-25"

/** Menor de 12 anos: o cadastro exige o contato do responsavel (LGPD art. 14; ECA Digital). */
const val GUARDIAN_AGE = 12
private const val MAX_AGE = 120

// Separador opcional (/, -, . ou espaco): teclados numericos nem sempre tem "/".
private val BIRTH_PATTERN = Regex("^([0-9]{2})[/. -]?([0-9]{2})[/. -]?([0-9]{4})$")

/** Data digitada como dd/mm/aaaa (ou dd-mm-aaaa, ddmmaaaa). Null se invalida, futura ou com mais de 120 anos. */
fun parseBirthDate(
    raw: String,
    today: LocalDate,
): LocalDate? {
    val parts = BIRTH_PATTERN.matchEntire(raw.trim())?.destructured
    val date =
        parts?.let { (day, month, year) ->
            runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
        }
    return date?.takeIf { !it.isAfter(today) && ageOn(it, today) <= MAX_AGE }
}

fun ageOn(
    birth: LocalDate,
    today: LocalDate,
): Int = Period.between(birth, today).years

fun isEmailValid(raw: String): Boolean = '@' in raw && raw.trim().length <= EMAIL_MAX

private const val EMAIL_MAX = 254
