package br.malhaviva.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private val policy = PasswordPolicy(FakePwned())

    private fun reason(password: String) = (runBlocking { policy.check(password) } as? PolicyResult.Rejected)?.reason

    private fun randomText(length: Int) = Random(7).let { r -> String(CharArray(length) { ('a' + r.nextInt(26)) }) }

    @Test
    fun `accepts a long lowercase passphrase without composition rules`() {
        assertIs<PolicyResult.Accepted>(runBlocking { policy.check(STRONG_PASSWORD) })
    }

    @Test
    fun `rejects too short and too long by code points`() {
        assertEquals(PasswordRejection.TOO_SHORT, reason("abc1234"))
        assertEquals(PasswordRejection.TOO_SHORT, reason("🚂".repeat(4)))
        assertEquals(PasswordRejection.TOO_LONG, reason(randomText(PASSWORD_MAX_LENGTH + 1)))
    }

    @Test
    fun `accepts exactly 128 characters`() {
        assertIs<PolicyResult.Accepted>(runBlocking { policy.check(randomText(PASSWORD_MAX_LENGTH)) })
    }

    @Test
    fun `accepts unicode and normalizes with NFKC`() {
        val accepted = runBlocking { policy.check("pão de açúcar e café 🚂 saída") }
        assertIs<PolicyResult.Accepted>(accepted)
        assertEquals("pass", normalizePassword("ｐａｓｓ"))
        assertEquals(normalizePassword("é"), normalizePassword("é"))
    }

    @Test
    fun `rejects leaked and weak passwords`() {
        assertEquals(PasswordRejection.PWNED, reason(PWNED_BUT_STRONG))
        assertEquals(PasswordRejection.WEAK, reason("password1234"))
        assertEquals(PasswordRejection.WEAK, reason("12345678"))
    }

    @Test
    fun `handle used as user input weakens the password`() {
        val result = runBlocking { policy.check("fulanodetal2024", listOf("fulanodetal")) }
        assertEquals(PasswordRejection.WEAK, (result as PolicyResult.Rejected).reason)
    }

    private fun withServer(
        status: Int,
        body: String,
        block: (baseUrl: String, requests: MutableList<Pair<String, String?>>) -> Unit,
    ) {
        val seen = mutableListOf<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/range/") { ex ->
            seen += ex.requestURI.path to ex.requestHeaders.getFirst("Add-Padding")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/range/", seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http checker sends only the 5 char prefix and matches the suffix`() {
        val hash = sha1Hex("hunter2")
        val body = "0000000000000000000000000000000000A:0\r\n${hash.drop(
            5,
        )}:42\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:3"
        withServer(200, body) { url, seen ->
            val checker = HttpPwnedPasswordsChecker(url)
            assertTrue(runBlocking { checker.isPwned("hunter2") })
            assertFalse(runBlocking { checker.isPwned("another-password") })
            assertEquals("/range/${hash.take(5)}", seen.first().first)
            assertEquals("true", seen.first().second)
            assertFalse(seen.any { hash.drop(5) in it.first })
        }
    }

    @Test
    fun `padding entries with zero count do not match`() {
        val hash = sha1Hex("hunter2")
        withServer(200, "${hash.drop(5)}:0") { url, _ ->
            assertFalse(runBlocking { HttpPwnedPasswordsChecker(url).isPwned("hunter2") })
        }
    }

    @Test
    fun `http checker fails open when the service is down or errors`() {
        withServer(503, "") { url, _ -> assertFalse(runBlocking { HttpPwnedPasswordsChecker(url).isPwned("hunter2") }) }
        val closedPort =
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { it.stop(0) }
                .address.port
        assertFalse(runBlocking { HttpPwnedPasswordsChecker("http://127.0.0.1:$closedPort/range/").isPwned("x") })
    }
}
