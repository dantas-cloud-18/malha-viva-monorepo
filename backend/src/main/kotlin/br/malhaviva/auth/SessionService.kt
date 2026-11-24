package br.malhaviva.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val REFRESH_BYTES = 32
private const val MAX_LABEL_CODE_POINTS = 64
private const val DEFAULT_LABEL = "Dispositivo"
private val REFRESH_TTL: Duration = Duration.ofDays(30)
private val log = LoggerFactory.getLogger("br.malhaviva.auth.Session")

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = ACCESS_TOKEN_TTL_SECONDS,
)

/** Remove controles/formatacao e `<>`, junta espacos e limita a 64 pontos de codigo. */
fun sanitizeDeviceLabel(raw: String?): String {
    val cleaned =
        (raw ?: "")
            .replace(Regex("[\\p{C}<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    val cut =
        cleaned.substring(
            0,
            cleaned.offsetByCodePoints(0, minOf(MAX_LABEL_CODE_POINTS, cleaned.codePointCount(0, cleaned.length))),
        )
    return cut.trim().ifEmpty { DEFAULT_LABEL }
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Sessoes: access JWT curto + refresh opaco de 256 bits, guardado so como SHA-256, rotativo a cada uso.
 * Reuso de um refresh ja trocado revoga a familia inteira. O vencimento de 30 dias e absoluto: o filho
 * herda o `expires_at` do pai.
 */
class SessionService(
    private val sessions: SessionRepository,
    private val access: AccessTokenService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private fun now(): Instant = Instant.ofEpochMilli(clock())

    private fun newRefresh(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(REFRESH_BYTES).also(random::nextBytes))

    fun startSession(
        accountId: UUID,
        deviceLabel: String?,
    ): TokenPair {
        val now = now()
        val deviceId = UUID.randomUUID()
        val raw = newRefresh()
        sessions.createDevice(
            DeviceRecord(deviceId, accountId, sanitizeDeviceLabel(deviceLabel), now, now),
            RefreshRecord(sha256Hex(raw), accountId, deviceId, now, now.plus(REFRESH_TTL)),
        )
        return TokenPair(access.issue(accountId, clock()), raw)
    }

    /** Devolve novo par ou null (401) se o token e desconhecido, vencido, revogado ou reusado. */
    fun refresh(rawToken: String): TokenPair? {
        val now = now()
        val hash = sha256Hex(rawToken)
        val current = sessions.findToken(hash)
        return when {
            current == null || current.revokedAt != null || !current.expiresAt.isAfter(now) -> null
            current.replacedAt != null -> reuseDetected(current)
            else -> rotate(current, hash, now)
        }
    }

    private fun rotate(
        current: RefreshRecord,
        hash: String,
        now: Instant,
    ): TokenPair? {
        val raw = newRefresh()
        val next = RefreshRecord(sha256Hex(raw), current.accountId, current.familyId, now, current.expiresAt)
        return if (sessions.rotate(hash, next, now)) {
            TokenPair(access.issue(current.accountId, clock()), raw)
        } else {
            reuseDetected(current)
        }
    }

    private fun reuseDetected(current: RefreshRecord): TokenPair? {
        sessions.revokeFamily(current.familyId, now())
        log.warn("Reuso de refresh token detectado; familia {} revogada", current.familyId)
        return null
    }

    /** Idempotente: token desconhecido nao e erro. */
    fun logout(rawToken: String) {
        sessions.findToken(sha256Hex(rawToken))?.let { sessions.revokeFamily(it.familyId, now()) }
    }

    /** Revoga todas as sessoes da conta. O access token em voo segue valido ate vencer (15 min). */
    fun revokeAll(accountId: UUID) = sessions.revokeAll(accountId, now())

    fun listDevices(accountId: UUID): List<DeviceRecord> = sessions.listDevices(accountId)

    fun revokeDevice(
        accountId: UUID,
        deviceId: UUID,
    ): Boolean = sessions.revokeDevice(accountId, deviceId, now())
}
