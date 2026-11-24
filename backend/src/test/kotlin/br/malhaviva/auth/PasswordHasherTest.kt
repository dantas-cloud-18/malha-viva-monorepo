package br.malhaviva.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher(TEST_PEPPER)

    @Test
    fun `default parameters are argon2id 19 MiB t2 p1 in PHC format with a 16 byte salt`() {
        val phc = hasher.hash("senha de teste 123")
        assertTrue(phc.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), phc)
        val salt = Base64.getDecoder().decode(phc.split('$')[4])
        assertEquals(16, salt.size)
        assertFalse(hasher.needsRehash(phc))
    }

    @Test
    fun `verifies the right password and rejects the wrong one`() {
        val phc = hasher.hash("senha de teste 123")
        assertTrue(hasher.verify("senha de teste 123", phc))
        assertFalse(hasher.verify("senha de teste 124", phc))
    }

    @Test
    fun `same password gets different salts`() {
        assertNotEquals(hasher.hash("mesma senha"), hasher.hash("mesma senha"))
    }

    @Test
    fun `a different pepper cannot verify`() {
        val phc = hasher.hash("senha de teste 123")
        assertFalse(PasswordHasher(ByteArray(32) { 9 }).verify("senha de teste 123", phc))
    }

    @Test
    fun `needsRehash when parameters change or hash is unreadable`() {
        val old = PasswordHasher(TEST_PEPPER, FAST_PARAMS).hash("senha")
        assertTrue(hasher.needsRehash(old))
        assertTrue(hasher.needsRehash("lixo"))
        assertTrue(hasher.verify("senha", old))
    }

    @Test
    fun `malformed hashes never verify and never throw`() {
        assertFalse(hasher.verify("x", ""))
        assertFalse(hasher.verify("x", "\$argon2id\$v=19\$m=a,t=b,p=c\$AA\$AA"))
        assertFalse(hasher.verify("x", "\$argon2i\$v=19\$m=64,t=1,p=1\$AAAAAAAAAAAAAAAAAAAAAA\$AAAA"))
    }

    @Test
    fun `pepper from env is required and must be 32 bytes of base64`() {
        assertFailsWith<IllegalStateException> { pepperFromEnv { null } }
        assertFailsWith<IllegalStateException> { pepperFromEnv { "  " } }
        assertFailsWith<IllegalStateException> { pepperFromEnv { "c2hvcnQ=" } }
        assertFailsWith<IllegalStateException> { pepperFromEnv { "!!!nao-e-base64!!!" } }
        assertContentEquals(TEST_PEPPER, pepperFromEnv { Base64.getEncoder().encodeToString(TEST_PEPPER) })
    }
}
