package br.malhaviva.core.data.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCodecTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val aad = "token".toByteArray()
    private val plain = "segredo-curto".toByteArray()

    @Test
    fun `round trip devolve o texto original`() {
        val codec = AesGcmCodec(newKey())
        assertArrayEquals(plain, codec.decrypt(codec.encrypt(plain, aad), aad))
    }

    @Test
    fun `adulteracao de qualquer byte e detectada`() {
        val codec = AesGcmCodec(newKey())
        val blob = codec.encrypt(plain, aad)
        for (i in blob.indices) {
            val tampered = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { codec.decrypt(tampered, aad) }
        }
    }

    @Test
    fun `blob truncado falha`() {
        val codec = AesGcmCodec(newKey())
        assertThrows(GeneralSecurityException::class.java) { codec.decrypt(ByteArray(5), aad) }
    }

    @Test
    fun `iv e unico por escrita`() {
        val codec = AesGcmCodec(newKey())
        val ivs = (1..200).map { codec.encrypt(plain, aad).copyOfRange(0, AesGcmCodec.IV_BYTES).toList() }
        assertEquals(200, ivs.toSet().size)
        assertTrue(ivs.all { it.size == AesGcmCodec.IV_BYTES })
    }

    @Test
    fun `chave errada falha`() {
        val blob = AesGcmCodec(newKey()).encrypt(plain, aad)
        assertThrows(GeneralSecurityException::class.java) { AesGcmCodec(newKey()).decrypt(blob, aad) }
    }

    @Test
    fun `aad diferente falha`() {
        val codec = AesGcmCodec(newKey())
        val blob = codec.encrypt(plain, aad)
        assertThrows(GeneralSecurityException::class.java) { codec.decrypt(blob, "outro".toByteArray()) }
    }

    @Test
    fun `fake em memoria guarda e remove`() {
        val store: SecretStore = InMemorySecretStore()
        assertNull(store.get("a"))
        store.put("a", "1")
        assertEquals("1", store.get("a"))
        store.remove("a")
        assertNull(store.get("a"))
    }
}
