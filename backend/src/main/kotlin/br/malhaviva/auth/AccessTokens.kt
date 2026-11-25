package br.malhaviva.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val ACCESS_TOKEN_TTL_SECONDS = 15L * 60
const val AMR_PASSWORD = "pwd"
const val AMR_OTP = "otp"

/** Token restrito do admin sem TOTP: so abre as rotas de enrolamento. */
const val SCOPE_MFA_ENROLL = "mfa_enroll_only"
private const val MIN_SECRET_BYTES = 32
private const val JWT_SECRET_ENV = "MALHAVIVA_JWT_SECRET"
private const val JWT_HEADER = """{"alg":"HS256","typ":"JWT"}"""
private const val MS_PER_SECOND = 1000L
private val enc = Base64.getUrlEncoder().withoutPadding()
private val dec = Base64.getUrlDecoder()
private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

fun amrFor(mfa: Boolean): List<String> = if (mfa) listOf(AMR_PASSWORD, AMR_OTP) else listOf(AMR_PASSWORD)

/** Apenas o necessario: sub = id da conta, metodos de autenticacao e escopo restrito. Nada de PII. */
@Serializable
private class AccessClaims(
    val sub: String,
    val iat: Long,
    val exp: Long,
    val amr: List<String> = listOf(AMR_PASSWORD),
    val scope: String? = null,
)

/** Quem o access token autentica. [scope] nao nulo = token restrito. */
class AccessPrincipal(
    val accountId: UUID,
    val amr: List<String>,
    val scope: String?,
) {
    val mfa: Boolean get() = AMR_OTP in amr
}

/** Le o segredo HS256 (base64, >= 32 bytes) do ambiente. Ausente ou curto derruba a inicializacao. */
fun jwtSecretFromEnv(env: (String) -> String? = System::getenv): ByteArray {
    val raw = env(JWT_SECRET_ENV)?.takeIf { it.isNotBlank() } ?: error("Missing environment variable $JWT_SECRET_ENV")
    val bytes = runCatching { Base64.getDecoder().decode(raw.trim()) }.getOrNull()
    check(bytes != null && bytes.size >= MIN_SECRET_BYTES) { "$JWT_SECRET_ENV must be base64 of at least 32 bytes" }
    return bytes
}

/** JWT HS256 com JDK puro. Verificacao exige exatamente o cabecalho HS256 (nada de `alg: none`). */
class AccessTokenService(
    secret: ByteArray,
    private val ttlSeconds: Long = ACCESS_TOKEN_TTL_SECONDS,
) {
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")

    fun issue(
        accountId: UUID,
        nowMs: Long,
        amr: List<String> = amrFor(false),
        scope: String? = null,
    ): String {
        val iat = nowMs / MS_PER_SECOND
        val claims = AccessClaims(accountId.toString(), iat, iat + ttlSeconds, amr, scope)
        val signingInput =
            enc.encodeToString(JWT_HEADER.toByteArray()) + "." +
                enc.encodeToString(json.encodeToString(AccessClaims.serializer(), claims).toByteArray())
        return signingInput + "." + enc.encodeToString(sign(signingInput))
    }

    /** Devolve o id da conta so para token completo (sem escopo restrito) valido; senao null. */
    fun verify(
        token: String,
        nowMs: Long,
    ): UUID? = verifyPrincipal(token, nowMs)?.takeIf { it.scope == null }?.accountId

    /** Assinatura, cabecalho e validade conferem: devolve o principal, inclusive de token restrito. */
    fun verifyPrincipal(
        token: String,
        nowMs: Long,
    ): AccessPrincipal? =
        runCatching {
            val parts = token.split('.')
            require(parts.size == JWT_PARTS)
            val valid =
                MessageDigest.isEqual(sign("${parts[0]}.${parts[1]}"), dec.decode(parts[2])) &&
                    String(dec.decode(parts[0])) == JWT_HEADER
            val claims = json.decodeFromString(AccessClaims.serializer(), String(dec.decode(parts[1])))
            AccessPrincipal(UUID.fromString(claims.sub), claims.amr, claims.scope)
                .takeIf { valid && nowMs / MS_PER_SECOND < claims.exp }
        }.getOrNull()

    private fun sign(input: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(input.toByteArray())
        }

    private companion object {
        const val JWT_PARTS = 3
    }
}
