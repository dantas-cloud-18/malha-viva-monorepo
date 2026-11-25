package br.malhaviva.auth

import br.malhaviva.auth.recovery.BackupCodeService
import br.malhaviva.auth.recovery.ContactSecurityNotifier
import br.malhaviva.auth.recovery.JdbcBackupCodeRepository
import br.malhaviva.auth.recovery.JdbcContactRepository
import br.malhaviva.auth.recovery.JdbcOtpRepository
import br.malhaviva.auth.recovery.JdbcTotpRepository
import br.malhaviva.auth.recovery.OtpService
import br.malhaviva.auth.recovery.RecoveryConfig
import br.malhaviva.auth.recovery.TotpService
import br.malhaviva.auth.recovery.otpKeyFromEnv
import br.malhaviva.lgpd.AccessLogRepository
import br.malhaviva.lgpd.ConsentService
import br.malhaviva.lgpd.DataRightsService
import br.malhaviva.lgpd.JdbcConsentRepository
import br.malhaviva.lgpd.JdbcParentalConsentRepository
import br.malhaviva.lgpd.LgpdComponents
import br.malhaviva.lgpd.ParentalConsentService
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.auth.AuthConfig")

/** Versao vigente da politica de privacidade/termos que o titular aceita (consentimento versionado). */
const val POLICY_VERSION_ENV = "MALHAVIVA_POLICY_VERSION"
private const val DEFAULT_POLICY_VERSION = "2026-11-25"

/** Genero opcional em stand-by: so com `true` o servidor aceita o campo. */
const val GENDER_ENABLED_ENV = "MALHAVIVA_GENDER_ENABLED"

private fun genderOn(env: (String) -> String?) = env(GENDER_ENABLED_ENV) == "true"

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
        val otpKey = otpKeyFromEnv(env)
        val totp = TotpService(JdbcTotpRepository(dataSource), otpKey)
        val backup = BackupCodeService(JdbcBackupCodeRepository(dataSource), otpKey)
        val contacts = JdbcContactRepository(dataSource)
        val gateway = RecoveryConfig.gatewayFromEnv(env)
        val notifier = ContactSecurityNotifier(contacts, gateway, RecoveryConfig.backgroundLauncher())
        val mfa = MfaService(accounts, totp, backup, notifier, hasher, access)
        val accessLogs = AccessLogRepository(dataSource)
        val auth =
            AuthService(
                accounts,
                sessions,
                policy,
                hasher,
                LoginGuard(),
                verifier,
                mfa,
                onLogin = { id, ip -> accessLogs.record(id, "login", ip) },
            )
        val recovery =
            RecoveryConfig.build(
                dataSource,
                env,
                contacts,
                gateway,
                accounts,
                sessions,
                policy,
                hasher,
                verifier,
            )
        val policyVersion = env(POLICY_VERSION_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_POLICY_VERSION
        val consents = ConsentService(JdbcConsentRepository(dataSource), accounts, policyVersion)
        val lgpd =
            LgpdComponents(
                consents,
                DataRightsService(dataSource, accounts, hasher, consents),
                ParentalConsentService(
                    accounts,
                    JdbcParentalConsentRepository(dataSource),
                    OtpService(JdbcOtpRepository(dataSource), otpKey),
                    gateway,
                    policyVersion,
                ),
                accessLogs,
                accounts,
                contacts,
            )
        return AuthComponents(auth, sessions, access, recovery = recovery, lgpd = lgpd, genderEnabled = genderOn(env))
    }
}
