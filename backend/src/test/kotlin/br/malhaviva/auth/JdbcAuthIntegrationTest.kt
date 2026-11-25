package br.malhaviva.auth

import br.malhaviva.PostgresIntegrationTest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contas, dispositivos e refresh tokens nos repositorios JDBC, contra Postgres de verdade. */
class JdbcAuthIntegrationTest : PostgresIntegrationTest() {
    private val accounts get() = JdbcAccountRepository(ds)
    private val sessionRepo get() = JdbcSessionRepository(ds)
    private val sessions get() = SessionService(sessionRepo, AccessTokenService(TEST_JWT_SECRET))

    private fun account(handle: String) = accounts.create(handle, "hash-$handle")!!

    @Test
    fun `accounts are created once per handle ignoring case and found by handle or id`() {
        val id = accounts.create("@Maria_1", "h1")
        assertNotNull(id)
        assertNull(accounts.create("@maria_1", "h2"), "handle repetido sem diferenciar maiusculas")
        val found = assertNotNull(accounts.findByHandle("@MARIA_1"))
        assertEquals(id, found.id)
        assertEquals("@Maria_1", found.handle)
        assertEquals("h1", found.passwordHash)
        assertEquals(ROLE_USER, found.role)
        assertEquals(found, accounts.findById(id))
        assertNull(accounts.findByHandle("@ninguem"))
        assertNull(accounts.findById(UUID.randomUUID()))
        accounts.updatePasswordHash(id, "h3")
        assertEquals("h3", accounts.findById(id)!!.passwordHash)
    }

    @Test
    fun `admin enrollment code is single use and only works for admins`() {
        val user = account("@comum_1")
        val admin = account("@chefe_1")
        val hash = sha256Hex("codigo-fora-de-banda")
        scalars("UPDATE accounts SET admin_enroll_hash = ? WHERE account_id = ?", hash, user)
        scalars("UPDATE accounts SET role = 'admin', admin_enroll_hash = ? WHERE account_id = ?", hash, admin)
        assertFalse(accounts.consumeAdminEnrollCode(user, hash), "papel user nunca consome")
        assertFalse(accounts.consumeAdminEnrollCode(admin, sha256Hex("outro")))
        assertTrue(accounts.consumeAdminEnrollCode(admin, hash))
        assertFalse(accounts.consumeAdminEnrollCode(admin, hash), "uso unico")
        assertEquals(listOf<Any?>(null), scalars("SELECT admin_enroll_hash FROM accounts WHERE account_id = ?", admin))
        assertEquals(ROLE_ADMIN, accounts.findById(admin)!!.role)
    }

    @Test
    fun `refresh rotation is atomic, inherits mfa and rejects a second swap of the same token`() {
        val acc = account("@rotacao")
        val now = Instant.now()
        val device = DeviceRecord(UUID.randomUUID(), acc, "Pixel", now, now)
        val first = RefreshRecord("hash-1", acc, device.id, now, now.plusSeconds(3600), mfa = true)
        sessionRepo.createDevice(device, first)
        assertTrue(assertNotNull(sessionRepo.findToken("hash-1")).mfa)
        assertTrue(sessionRepo.isFamilyActive(device.id))

        val second = first.copy(tokenHash = "hash-2")
        assertTrue(sessionRepo.rotate("hash-1", second, now))
        assertFalse(sessionRepo.rotate("hash-1", first.copy(tokenHash = "hash-3"), now), "reuso do token trocado")
        assertNull(sessionRepo.findToken("hash-3"), "troca recusada nao grava o novo token")
        assertNotNull(sessionRepo.findToken("hash-1")!!.replacedAt)
        assertTrue(sessionRepo.findToken("hash-2")!!.mfa)
    }

    @Test
    fun `concurrent rotations of the same refresh token have exactly one winner`() {
        val acc = account("@corrida_rt")
        val now = Instant.now()
        val device = DeviceRecord(UUID.randomUUID(), acc, "Pixel", now, now)
        val first = RefreshRecord("hash-0", acc, device.id, now, now.plusSeconds(3600))
        sessionRepo.createDevice(device, first)
        val wins = race(8) { sessionRepo.rotate("hash-0", first.copy(tokenHash = "novo-$it"), now) }
        assertEquals(1, wins.count { it })
        assertEquals(
            1,
            scalars("SELECT count(*) FROM refresh_tokens WHERE token_hash LIKE 'novo-%'").single().toString().toInt(),
        )
    }

    @Test
    fun `reusing a rotated refresh token revokes the whole family`() {
        val acc = account("@reuso")
        val pair = sessions.startSession(acc, "Pixel", mfa = true)
        val next = assertNotNull(sessions.refresh(pair.refreshToken))
        assertNull(sessions.refresh(pair.refreshToken), "reuso detectado")
        assertNull(sessions.refresh(next.refreshToken), "a familia inteira foi revogada")
        val sid =
            AccessTokenService(
                TEST_JWT_SECRET,
            ).verifyPrincipal(pair.accessToken, System.currentTimeMillis())!!.sessionId!!
        assertFalse(sessions.isActive(sid))
        assertEquals(emptyList(), sessions.listDevices(acc))
    }

    @Test
    fun `devices list only live ones and revoking is owner-only`() {
        val me = account("@dono")
        val other = account("@intruso")
        sessions.startSession(me, "Pixel")
        val mine = sessions.startSession(me, "iPhone")
        val theirs = sessions.startSession(other, "Galaxy")
        assertEquals(setOf("Pixel", "iPhone"), sessions.listDevices(me).map { it.label }.toSet())

        val myDevice = sessions.listDevices(me).first { it.label == "iPhone" }
        assertFalse(sessions.revokeDevice(other, myDevice.id), "dispositivo de outra conta")
        assertNotNull(sessions.refresh(mine.refreshToken), "continua vivo apos a tentativa alheia")
        assertTrue(sessions.revokeDevice(me, myDevice.id))
        assertFalse(sessions.revokeDevice(me, myDevice.id), "ja revogado")
        assertEquals(listOf("Pixel"), sessions.listDevices(me).map { it.label })

        sessions.revokeAll(me)
        assertEquals(emptyList(), sessions.listDevices(me))
        assertEquals(1, sessions.listDevices(other).size)
        assertNotNull(sessions.refresh(theirs.refreshToken))
    }

    @Test
    fun `logout revokes the family and deleting the account cascades to devices and tokens`() {
        val acc = account("@sai")
        val pair = sessions.startSession(acc, "Pixel")
        sessions.logout(pair.refreshToken)
        assertNull(sessions.refresh(pair.refreshToken))
        sessions.startSession(acc, "Outro")
        scalars("DELETE FROM accounts WHERE account_id = ?", acc)
        assertEquals(0L, scalars("SELECT count(*) FROM devices").single())
        assertEquals(0L, scalars("SELECT count(*) FROM refresh_tokens").single())
    }
}
