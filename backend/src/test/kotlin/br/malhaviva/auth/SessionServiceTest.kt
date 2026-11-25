package br.malhaviva.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionServiceTest {
    private val t = TestAuth()
    private val account = UUID.randomUUID()

    private fun payload(jwt: String) =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]))).jsonObject

    @Test
    fun `access token carries only sub iat and exp`() {
        val pair = t.sessions.startSession(account, "Pixel")
        val claims = payload(pair.accessToken)
        assertEquals(setOf("sub", "iat", "exp", "amr"), claims.keys)
        assertEquals(account.toString(), claims.getValue("sub").toString().trim('"'))
        assertEquals(account, t.access.verify(pair.accessToken, t.now))
    }

    @Test
    fun `access token expires after 15 minutes and rejects tampering and alg none`() {
        val token = t.sessions.startSession(account, null).accessToken
        assertEquals(account, t.access.verify(token, t.now + 14 * 60_000))
        assertNull(t.access.verify(token, t.now + 15 * 60_000))
        val parts = token.split('.')
        val evilPayload =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"sub":"${UUID.randomUUID()}","iat":1,"exp":99999999999}""".toByteArray(),
            )
        assertNull(t.access.verify("${parts[0]}.$evilPayload.${parts[2]}", t.now))
        val none =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"alg":"none","typ":"JWT"}""".toByteArray(),
            )
        assertNull(t.access.verify("$none.${parts[1]}.", t.now))
        assertNull(AccessTokenService(ByteArray(32) { 1 }).verify(token, t.now))
        assertNull(t.access.verify("lixo", t.now))
    }

    @Test
    fun `jwt secret from env is required`() {
        assertFailsWith<IllegalStateException> { jwtSecretFromEnv { null } }
        assertFailsWith<IllegalStateException> { jwtSecretFromEnv { "c2hvcnQ=" } }
    }

    @Test
    fun `refresh is stored hashed and rotates on every use`() {
        val first = t.sessions.startSession(account, "Pixel")
        assertTrue(t.sessionRepo.tokenRows.none { it.tokenHash == first.refreshToken })
        assertEquals(
            sha256Hex(first.refreshToken),
            t.sessionRepo.tokenRows
                .single()
                .tokenHash,
        )
        val second = assertNotNull(t.sessions.refresh(first.refreshToken))
        assertNotEquals(first.refreshToken, second.refreshToken)
        val third = assertNotNull(t.sessions.refresh(second.refreshToken))
        assertNotEquals(second.refreshToken, third.refreshToken)
        assertEquals(1, t.sessions.listDevices(account).size)
    }

    @Test
    fun `reusing a rotated refresh revokes the whole family`() {
        val first = t.sessions.startSession(account, "Pixel")
        val second = assertNotNull(t.sessions.refresh(first.refreshToken))
        assertNull(t.sessions.refresh(first.refreshToken))
        assertNull(t.sessions.refresh(second.refreshToken))
        assertTrue(t.sessionRepo.tokenRows.all { it.revokedAt != null })
        assertTrue(t.sessions.listDevices(account).isEmpty())
    }

    @Test
    fun `reuse in one device does not touch another device`() {
        val a = t.sessions.startSession(account, "A")
        val b = t.sessions.startSession(account, "B")
        val a2 = assertNotNull(t.sessions.refresh(a.refreshToken))
        assertNull(t.sessions.refresh(a.refreshToken))
        assertNotNull(t.sessions.refresh(b.refreshToken))
        assertNull(t.sessions.refresh(a2.refreshToken))
    }

    @Test
    fun `expiry is absolute at 30 days and not extended by rotation`() {
        var pair = t.sessions.startSession(account, "Pixel")
        repeat(29) {
            t.now += Duration.ofDays(1).toMillis()
            pair = assertNotNull(t.sessions.refresh(pair.refreshToken))
        }
        t.now += Duration.ofDays(1).toMillis() + 1
        assertNull(t.sessions.refresh(pair.refreshToken))
        assertNull(t.sessions.refresh("token-desconhecido"))
    }

    @Test
    fun `logout revokes the family and is idempotent`() {
        val pair = t.sessions.startSession(account, "Pixel")
        t.sessions.logout(pair.refreshToken)
        t.sessions.logout(pair.refreshToken)
        t.sessions.logout("desconhecido")
        assertNull(t.sessions.refresh(pair.refreshToken))
    }

    @Test
    fun `device label is sanitized and capped at 64 code points`() {
        assertEquals("Dispositivo", sanitizeDeviceLabel(null))
        assertEquals("Dispositivo", sanitizeDeviceLabel(" \u0000\n "))
        assertEquals("Pixel 8 script", sanitizeDeviceLabel("Pixel\n8 <script>"))
        assertEquals(64, sanitizeDeviceLabel("x".repeat(500)).length)
        assertEquals(64, sanitizeDeviceLabel("🚂".repeat(100)).codePointCount(0, 128))
        assertFalse(
            t.sessions.startSession(account, "<b>x</b>").let {
                t.sessions
                    .listDevices(account)
                    .first()
                    .label
                    .contains('<')
            },
        )
    }
}
