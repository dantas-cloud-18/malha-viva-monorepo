package br.malhaviva.auth

import org.postgresql.ds.PGSimpleDataSource
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

private object FakeRealCaptcha : CaptchaVerifier {
    override suspend fun verify(
        token: String?,
        ip: String,
    ) = token == "valido"
}

class CaptchaConfigTest {
    private fun secret(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (seed + it).toByte() })

    private val base =
        mapOf(
            "MALHAVIVA_PEPPER" to secret(1),
            "MALHAVIVA_JWT_SECRET" to secret(2),
            "MALHAVIVA_OTP_KEY" to secret(3),
        )

    private fun envOf(vararg extra: Pair<String, String>): (String) -> String? = (base + extra)::get

    // o PGSimpleDataSource so conecta quando alguem pede uma conexao: a montagem nao toca no banco
    private val dataSource = PGSimpleDataSource()

    @Test
    fun `startup fails closed without a real captcha unless the dev flag is exactly true`() {
        assertFailsWith<IllegalStateException> { AuthConfig.captchaFrom(envOf()) }
        assertFailsWith<IllegalStateException> { AuthConfig.captchaFrom(envOf(DEV_CAPTCHA_ENV to "1")) }
        assertFailsWith<IllegalStateException> { AuthConfig.captchaFrom(envOf(DEV_CAPTCHA_ENV to "false")) }
    }

    @Test
    fun `the dev flag allows the no-op verifier and an injected verifier always wins`() {
        assertSame(NoopCaptchaVerifier, AuthConfig.captchaFrom(envOf(DEV_CAPTCHA_ENV to "true")))
        assertSame(FakeRealCaptcha, AuthConfig.captchaFrom(envOf(), FakeRealCaptcha))
    }

    @Test
    fun `fromEnv refuses to build the account plane without a captcha verifier`() {
        assertFailsWith<IllegalStateException> { AuthConfig.fromEnv(dataSource, envOf()) }
        assertNotNull(AuthConfig.fromEnv(dataSource, envOf(DEV_CAPTCHA_ENV to "true")))
        assertNotNull(AuthConfig.fromEnv(dataSource, envOf(), FakeRealCaptcha))
    }
}
