package br.malhaviva.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Parametros do argon2id (OWASP / BRIEF 4.1: m >= 19 MiB, t = 2, p = 1). */
data class Argon2Params(
    val memoryKib: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
)

private const val SALT_BYTES = 16
private const val HASH_BYTES = 32
private const val MIN_PEPPER_BYTES = 32
private const val PEPPER_ENV = "MALHAVIVA_PEPPER"
private val b64 = Base64.getEncoder().withoutPadding()
private val b64d = Base64.getDecoder()

/** Le o pepper (base64, >= 32 bytes) do ambiente. Ausente ou curto derruba a inicializacao. */
fun pepperFromEnv(env: (String) -> String? = System::getenv): ByteArray {
    val raw = env(PEPPER_ENV)?.takeIf { it.isNotBlank() } ?: error("Missing environment variable $PEPPER_ENV")
    val bytes = runCatching { b64d.decode(raw.trim()) }.getOrNull()
    check(bytes != null && bytes.size >= MIN_PEPPER_BYTES) { "$PEPPER_ENV must be base64 of at least 32 bytes" }
    return bytes
}

/**
 * argon2id com pepper: a entrada do argon2 e HMAC-SHA256(pepper, senha). O hash fica em formato PHC:
 * `$argon2id$v=19$m=..,t=..,p=..$salt$hash`, com salt aleatorio de 16 bytes.
 */
open class PasswordHasher(
    pepper: ByteArray,
    private val params: Argon2Params = Argon2Params(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val pepperKey = SecretKeySpec(pepper.copyOf(), "HmacSHA256")

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return encode(params, salt, derive(password, params, salt, HASH_BYTES))
    }

    /** Tempo constante na comparacao; PHC malformado simplesmente nao confere. */
    open fun verify(
        password: String,
        phc: String,
    ): Boolean {
        val parsed = parse(phc) ?: return false
        val actual = derive(password, parsed.params, parsed.salt, parsed.hash.size)
        return MessageDigest.isEqual(actual, parsed.hash)
    }

    /** True se o hash foi gerado com parametros diferentes dos atuais (ou nao e legivel). */
    fun needsRehash(phc: String): Boolean = parse(phc)?.params != params

    private fun derive(
        password: String,
        p: Argon2Params,
        salt: ByteArray,
        length: Int,
    ): ByteArray {
        val input =
            Mac.getInstance("HmacSHA256").run {
                init(pepperKey)
                doFinal(password.toByteArray(Charsets.UTF_8))
            }
        val config =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(p.memoryKib)
                .withIterations(p.iterations)
                .withParallelism(p.parallelism)
                .withSalt(salt)
                .build()
        return ByteArray(length).also { out ->
            Argon2BytesGenerator().apply { init(config) }.generateBytes(input, out)
        }
    }

    private class Parsed(
        val params: Argon2Params,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    private fun encode(
        p: Argon2Params,
        salt: ByteArray,
        hash: ByteArray,
    ) = "\$argon2id\$v=19\$m=${p.memoryKib},t=${p.iterations},p=${p.parallelism}\$${b64.encodeToString(salt)}" +
        "\$${b64.encodeToString(hash)}"

    private fun parse(phc: String): Parsed? =
        runCatching {
            val parts = phc.split('$')
            require(parts.size == PHC_PARTS && parts[0].isEmpty())
            val (alg, version, config) = parts.drop(1)
            val (salt, hash) = parts.takeLast(2)
            require(alg == "argon2id" && version == "v=19")
            val kv = config.split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
            Parsed(
                Argon2Params(kv.getValue("m"), kv.getValue("t"), kv.getValue("p")),
                b64d.decode(salt),
                b64d.decode(hash),
            )
        }.getOrNull()

    private companion object {
        const val PHC_PARTS = 6
    }
}
