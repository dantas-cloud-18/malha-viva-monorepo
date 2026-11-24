package br.malhaviva.feature.tracking.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BatchSignerTest {
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)
    private val body = """{"a":1}""".toByteArray()

    @Test
    fun `body hash is lowercase hex sha256`() {
        assertEquals("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862", BatchSigner.bodyHash(body))
    }

    @Test
    fun `signature matches a fixed vector computed outside the signer`() {
        // vetor calculado com python (hmac + sha256) sobre "{ts}\n{nonce}\n{hash}"
        assertEquals(
            "V2tQvv9RsHhqoPgT7vlvB2I3EAKZIqypOtUVlm-eeSE",
            BatchSigner.signature(key, 1_700_000_000_000L, "0123456789abcdef", body),
        )
    }

    @Test
    fun `signature matches independent javax crypto computation`() {
        val ts = 1_800_000_123_456L
        val nonce = "nonce-nonce-nonce-1"
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyBytes, "HmacSHA256")) }
        val expected =
            Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$ts\n$nonce\n$hash".toByteArray()))
        val actual = BatchSigner.signature(key, ts, nonce, body)
        assertEquals(expected, actual)
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(actual))
    }

    @Test
    fun `nonce has valid length and is unique`() {
        val nonces = List(500) { BatchSigner.newNonce() }
        assertTrue(nonces.all { it.length in 16..64 })
        assertEquals(nonces.size, nonces.toSet().size)
    }
}
