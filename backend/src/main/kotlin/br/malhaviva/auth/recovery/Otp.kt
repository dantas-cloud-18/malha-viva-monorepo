package br.malhaviva.auth.recovery

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val OTP_KEY_ENV = "MALHAVIVA_OTP_KEY"
const val OTP_DIGITS = 6
const val OTP_MAX_ATTEMPTS = 5
private const val OTP_SPACE = 1_000_000
private const val MIN_KEY_BYTES = 32
private val OTP_TTL: Duration = Duration.ofMinutes(10)
private val CODE_REGEX = Regex("^[0-9]{$OTP_DIGITS}$")

/** Le a chave do HMAC dos OTPs (base64, >= 32 bytes). Ausente ou curta derruba a inicializacao. */
fun otpKeyFromEnv(env: (String) -> String? = System::getenv): ByteArray {
    val raw = env(OTP_KEY_ENV)?.takeIf { it.isNotBlank() } ?: error("Missing environment variable $OTP_KEY_ENV")
    val bytes = runCatching { Base64.getDecoder().decode(raw.trim()) }.getOrNull()
    check(bytes != null && bytes.size >= MIN_KEY_BYTES) { "$OTP_KEY_ENV must be base64 of at least 32 bytes" }
    return bytes
}

enum class OtpPurpose { VERIFY_CONTACT, RECOVERY, CONTACT_CHANGE, PARENTAL_CONSENT }

data class OtpRecord(
    val id: UUID,
    val accountId: UUID,
    val purpose: OtpPurpose,
    val channel: Channel,
    val codeHmac: String,
    val expiresAt: Instant,
    val attempts: Int = 0,
    val consumedAt: Instant? = null,
    val createdAt: Instant,
)

interface OtpRepository {
    /** Invalida os OTPs ativos de (conta, finalidade, canal) e grava o novo. */
    fun replace(record: OtpRecord)

    /** O mais recente de (conta, finalidade, canal), usado ou nao. */
    fun latest(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
    ): OtpRecord?

    /** Soma uma tentativa e devolve o total ja contado. */
    fun addAttempt(id: UUID): Int

    /** Marca como usado se ainda nao estava; false se outro pedido ganhou a corrida. */
    fun consume(
        id: UUID,
        now: Instant,
    ): Boolean
}

/**
 * OTP de 6 digitos (SecureRandom), valido por 10 min, uso unico, 5 tentativas. Guarda so o HMAC-SHA256
 * ligado a conta, finalidade, canal e referencia (valor do contato ou id da troca), comparado em tempo
 * constante. shortcut: o limite de tentativas e por OTP; o limite por conta fica nos limitadores do servico.
 */
class OtpService(
    private val repo: OtpRepository,
    key: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key = SecretKeySpec(key.copyOf(), "HmacSHA256")

    private fun now(): Instant = Instant.ofEpochMilli(clock())

    private fun mac(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
        ref: String,
        code: String,
    ): ByteArray =
        Mac
            .getInstance("HmacSHA256")
            .apply { init(key) }
            .doFinal("$purpose|$accountId|$channel|$ref|$code".toByteArray())

    /** Gera e grava um OTP novo e devolve o codigo em claro, so para ir ao [MessageSender]. */
    fun issue(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
        ref: String,
    ): String {
        val code = random.nextInt(OTP_SPACE).toString().padStart(OTP_DIGITS, '0')
        val hex = mac(accountId, purpose, channel, ref, code).joinToString("") { "%02x".format(it) }
        repo.replace(
            OtpRecord(UUID.randomUUID(), accountId, purpose, channel, hex, now().plus(OTP_TTL), createdAt = now()),
        )
        return code
    }

    /**
     * Confere o codigo contando a tentativa, sem consumir. Devolve o registro se valido; use [consume]
     * depois (permite exigir dois canais sem queimar o primeiro por erro no segundo).
     */
    fun check(
        accountId: UUID,
        purpose: OtpPurpose,
        channel: Channel,
        ref: String,
        code: String?,
    ): OtpRecord? {
        val rec =
            repo
                .latest(accountId, purpose, channel)
                ?.takeIf { it.consumedAt == null && it.expiresAt.isAfter(now()) && it.attempts < OTP_MAX_ATTEMPTS }
        val attempt = rec?.let { repo.addAttempt(it.id) } ?: OTP_MAX_ATTEMPTS + 1
        val given = if (code != null && CODE_REGEX.matches(code)) code else "x"
        val calc = mac(accountId, purpose, channel, ref, given)
        val expected =
            rec
                ?.codeHmac
                ?.chunked(2)
                ?.map { it.toInt(HEX_RADIX).toByte() }
                ?.toByteArray() ?: calc
        val match = MessageDigest.isEqual(calc, expected) && given != "x"
        return rec?.takeIf { match && attempt <= OTP_MAX_ATTEMPTS }
    }

    fun consume(record: OtpRecord): Boolean = repo.consume(record.id, now())

    /**
     * Conta inexistente: mesma consulta ao repositorio e mesmo HMAC de [check] para um canal, contra uma conta
     * descartavel. So nao grava tentativa (nao ha registro), o que a espera minima do servico cobre.
     */
    fun probe(
        channel: Channel,
        code: String?,
    ) {
        repo.latest(PROBE_ACCOUNT, OtpPurpose.RECOVERY, channel)
        mac(PROBE_ACCOUNT, OtpPurpose.RECOVERY, channel, "", code.orEmpty())
    }

    private companion object {
        const val HEX_RADIX = 16
        val PROBE_ACCOUNT = UUID(0L, 0L)
    }
}
