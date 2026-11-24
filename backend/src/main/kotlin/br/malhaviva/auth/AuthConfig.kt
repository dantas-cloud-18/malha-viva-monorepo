package br.malhaviva.auth

import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.auth.AuthConfig")

/**
 * Monta o plano de conta para producao. Falta de MALHAVIVA_PEPPER ou MALHAVIVA_JWT_SECRET derruba a
 * inicializacao (fail-closed). O CAPTCHA ainda e o NoopCaptchaVerifier e a verificacao de senha vazada
 * usa o HIBP publico.
 */
object AuthConfig {
    fun fromEnv(
        dataSource: DataSource,
        env: (String) -> String? = System::getenv,
    ): AuthComponents {
        val hasher = PasswordHasher(pepperFromEnv(env))
        val access = AccessTokenService(jwtSecretFromEnv(env))
        log.warn("CaptchaVerifier padrao e NoopCaptchaVerifier: nao usar em producao")
        val sessions = SessionService(JdbcSessionRepository(dataSource), access)
        val auth =
            AuthService(
                JdbcAccountRepository(dataSource),
                sessions,
                PasswordPolicy(HttpPwnedPasswordsChecker()),
                hasher,
                LoginGuard(),
                NoopCaptchaVerifier,
            )
        return AuthComponents(auth, sessions, access)
    }
}
