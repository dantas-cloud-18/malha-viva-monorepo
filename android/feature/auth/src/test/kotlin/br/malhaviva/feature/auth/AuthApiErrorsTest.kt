package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthApiErrorsTest {
    private val env = TestEnv()

    private suspend fun errorFor(response: br.malhaviva.core.data.net.HttpResponse): AuthError {
        env.transport.on("POST /auth/token", response)
        return (env.api.login("@ana", "senha-longa-1", null, null) as ApiResult.Fail).error
    }

    @Test
    fun `429 parses Retry-After seconds`() =
        runTest {
            assertEquals(AuthError.RateLimited(42), errorFor(problem(429, headers = mapOf("retry-after" to "42"))))
        }

    @Test
    fun `429 without a numeric Retry-After has no wait time`() =
        runTest {
            assertEquals(AuthError.RateLimited(null), errorFor(problem(429)))
            assertEquals(
                AuthError.RateLimited(null),
                errorFor(problem(429, headers = mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT"))),
            )
        }

    @Test
    fun `403 codes map to typed errors`() =
        runTest {
            assertEquals(AuthError.InvalidPassword, errorFor(problem(403, "invalid_password")))
            assertEquals(AuthError.CaptchaRequired, errorFor(problem(403, "captcha_required")))
            assertEquals(AuthError.AdminMfaRequired, errorFor(problem(403, "admin_mfa_required")))
            assertEquals(AuthError.InvalidCredentials, errorFor(problem(403, "invalid_credentials")))
            assertEquals(AuthError.AdminMfaMandatory, errorFor(problem(403, "admin_mfa_mandatory")))
            assertEquals(AuthError.EnrollmentCodeRequired, errorFor(problem(403, "enrollment_code_required")))
        }

    @Test
    fun `400 invalid_or_expired_code maps to InvalidCode and other 400s stay rejected`() =
        runTest {
            assertEquals(AuthError.InvalidCode, errorFor(problem(400, "invalid_or_expired_code")))
            assertEquals(AuthError.Rejected(400, null), errorFor(problem(400)))
        }

    @Test
    fun `413 and 401 map to typed errors`() =
        runTest {
            assertEquals(AuthError.PayloadTooLarge, errorFor(problem(413)))
            assertEquals(AuthError.Unauthorized, errorFor(problem(401)))
        }

    @Test
    fun `network failure and garbage body map to Network`() =
        runTest {
            env.transport.offline = true
            assertEquals(AuthError.Network, (env.api.refresh("r") as ApiResult.Fail).error)
            env.transport.offline = false
            env.transport.on("POST /auth/refresh", json(200, "not json"))
            assertEquals(AuthError.Network, (env.api.refresh("r") as ApiResult.Fail).error)
        }
}
