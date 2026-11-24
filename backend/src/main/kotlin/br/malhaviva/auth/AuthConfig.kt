package br.malhaviva.auth

import br.malhaviva.auth.recovery.RecoveryConfig
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.auth.AuthConfig")

/**
 * Monta o plano de conta para producao. Falta de MALHAVIVA_PEPPER, MALHAVIVA_JWT_SECRET ou MALHAVIVA_OTP_KEY
 * derruba a inicializacao (fail-closed). O CAPTCHA ainda e o NoopCaptchaVerifier e a verificacao de senha vazada
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
        val accounts = JdbcAccountRepository(dataSource)
        val policy = PasswordPolicy(HttpPwnedPasswordsChecker())
        val auth = AuthService(accounts, sessions, policy, hasher, LoginGuard(), NoopCaptchaVerifier)
        val recovery = RecoveryConfig.build(dataSource, env, accounts, sessions, policy, hasher, NoopCaptchaVerifier)
        return AuthComponents(auth, sessions, access, recovery = recovery)
    }
}
