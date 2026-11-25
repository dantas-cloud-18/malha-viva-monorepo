package br.malhaviva.feature.auth

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
fun isPhoneBlank(raw: String): Boolean = normalizePhone(raw).let { it.isEmpty() || it == DEFAULT_PHONE }

fun isPhoneValid(raw: String): Boolean = PHONE_PATTERN.matches(normalizePhone(raw))

fun isCodeValid(code: String): Boolean = code.length == TOTP_LENGTH && code.all { it in '0'..'9' }
