package br.malhaviva.tracking.ingest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

private const val SECRET_BYTES = 32
const val TOKEN_TTL_MS = 6L * 60 * 60 * 1000

@Serializable
data class IssuedTokenDto(
    val token: String,
    @SerialName("signing_key") val signingKey: String,
    @SerialName("anon_epoch_id") val anonEpochId: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** Emite tokens anonimos. Tudo vem de SecureRandom: nada deriva de conta, aparelho ou IP. */
class TokenIssuer(
    private val store: InMemoryTelemetryTokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun secret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun issue(): IssuedTokenDto {
        val key = secret()
        val id = b64.encodeToString(secret())
        val epoch = b64.encodeToString(secret())
        val token = TelemetryToken(id, key, epoch, clock() + TOKEN_TTL_MS)
        store.put(token)
        return IssuedTokenDto(
            token = token.tokenId,
            signingKey = b64.encodeToString(key),
            anonEpochId = token.anonEpochId,
            expiresAt = Instant.ofEpochMilli(token.expiresAtMs).toString(),
        )
    }
}
