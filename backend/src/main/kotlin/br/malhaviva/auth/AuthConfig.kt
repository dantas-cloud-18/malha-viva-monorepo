package br.malhaviva.auth

import br.malhaviva.auth.recovery.JdbcTotpRepository
import br.malhaviva.auth.recovery.RecoveryConfig
import br.malhaviva.auth.recovery.TotpService
import br.malhaviva.auth.recovery.otpKeyFromEnv
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.auth.AuthConfig")

/** `true` libera o [NoopCaptchaVerifier] (aceita qualquer token). Somente desenvolvimento. */
const val DEV_CAPTCHA_ENV = "MALHAVIVA_DEV_CAPTCHA"

/**
 * Monta o plano de conta para producao. Falta de MALHAVIVA_PEPPER, MALHAVIVA_JWT_SECRET ou MALHAVIVA_OTP_KEY
 * derruba a inicializacao (fail-closed), e tambem a falta de um [CaptchaVerifier] real: sem ele a subida falha,
 * a menos que `MALHAVIVA_DEV_CAPTCHA=true` (so desenvolvimento; ai o no-op entra e o log avisa em alto e bom som).
 * A verificacao de senha vazada usa o HIBP publico.
 */
object AuthConfig {
    /** O verificador de CAPTCHA a usar: o injetado, o no-op de desenvolvimento ou nada (a subida falha). */
    fun captchaFrom(
        env: (String) -> String?,
        provided: CaptchaVerifier? = null,
    ): CaptchaVerifier =
        provided ?: if (env(DEV_CAPTCHA_ENV) == "true") {
            log.error("$DEV_CAPTCHA_ENV=true: NoopCaptchaVerifier ativo, qualquer token passa. NUNCA em producao")
            NoopCaptchaVerifier
        } else {
            error("Nenhum CaptchaVerifier real configurado; defina $DEV_CAPTCHA_ENV=true apenas em desenvolvimento")
        }

    fun fromEnv(
        dataSource: DataSource,
        env: (String) -> String? = System::getenv,
        captcha: CaptchaVerifier? = null,
    ): AuthComponents {
        val verifier = captchaFrom(env, captcha)
        val hasher = PasswordHasher(pepperFromEnv(env))
        val access = AccessTokenService(jwtSecretFromEnv(env))
        val sessions = SessionService(JdbcSessionRepository(dataSource), access)
        val accounts = JdbcAccountRepository(dataSource)
        val policy = PasswordPolicy(HttpPwnedPasswordsChecker())
        val totp = TotpService(JdbcTotpRepository(dataSource), otpKeyFromEnv(env))
        val mfa = MfaService(accounts, totp, hasher, access)
        val auth = AuthService(accounts, sessions, policy, hasher, LoginGuard(), verifier, mfa)
        val recovery = RecoveryConfig.build(dataSource, env, accounts, sessions, policy, hasher, verifier)
        return AuthComponents(auth, sessions, access, recovery = recovery)
    }
}
