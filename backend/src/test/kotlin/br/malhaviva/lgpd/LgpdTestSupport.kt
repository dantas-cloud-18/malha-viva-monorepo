package br.malhaviva.lgpd

import br.malhaviva.auth.AccessTokenService
import br.malhaviva.auth.AuthComponents
import br.malhaviva.auth.AuthLimiters
import br.malhaviva.auth.AuthService
import br.malhaviva.auth.CountingHasher
import br.malhaviva.auth.FakePwned
import br.malhaviva.auth.JdbcAccountRepository
import br.malhaviva.auth.JdbcSessionRepository
import br.malhaviva.auth.LoginGuard
import br.malhaviva.auth.MfaService
import br.malhaviva.auth.NoopCaptchaVerifier
import br.malhaviva.auth.PasswordPolicy
import br.malhaviva.auth.STRONG_PASSWORD
import br.malhaviva.auth.SessionService
import br.malhaviva.auth.TEST_JWT_SECRET
import br.malhaviva.auth.recovery.BackupCodeService
import br.malhaviva.auth.recovery.FakeSender
import br.malhaviva.auth.recovery.JdbcBackupCodeRepository
import br.malhaviva.auth.recovery.JdbcContactRepository
import br.malhaviva.auth.recovery.JdbcOtpRepository
import br.malhaviva.auth.recovery.JdbcTotpRepository
import br.malhaviva.auth.recovery.MessageGateway
import br.malhaviva.auth.recovery.OtpService
import br.malhaviva.auth.recovery.RecordingSecurityNotifier
import br.malhaviva.auth.recovery.TEST_OTP_KEY
import br.malhaviva.auth.recovery.TotpService
import br.malhaviva.auth.recovery.roomyLimits
import br.malhaviva.tracking.ingest.RateLimiter
import javax.sql.DataSource

const val TEST_POLICY = "2026-11-25"

/** Plano de conta com repositorios JDBC reais (Postgres de teste) e a Fase 5 ligada. */
class LgpdEnv(
    ds: DataSource,
    val sender: FakeSender = FakeSender(),
    limiters: AuthLimiters = AuthLimiters(RateLimiter(1000.0, 1.0), RateLimiter(1000.0, 1.0), RateLimiter(1000.0, 1.0)),
    genderEnabled: Boolean = true,
) {
    private val clock = System::currentTimeMillis
    private val accounts = JdbcAccountRepository(ds)
    private val hasher = CountingHasher()
    private val access = AccessTokenService(TEST_JWT_SECRET)
    private val sessions = SessionService(JdbcSessionRepository(ds), access)
    private val mfa =
        MfaService(
            accounts,
            TotpService(JdbcTotpRepository(ds), TEST_OTP_KEY),
            BackupCodeService(JdbcBackupCodeRepository(ds), TEST_OTP_KEY),
            RecordingSecurityNotifier(),
            hasher,
            access,
        )
    private val accessLogs = AccessLogRepository(ds)
    private val consents = ConsentService(JdbcConsentRepository(ds), accounts, TEST_POLICY)
    val components =
        AuthComponents(
            AuthService(
                accounts,
                sessions,
                PasswordPolicy(FakePwned()),
                hasher,
                LoginGuard(),
                NoopCaptchaVerifier,
                mfa,
                onLogin = { id, ip -> accessLogs.record(id, "login", ip) },
            ),
            sessions,
            access,
            limiters,
            clock,
            lgpd =
                LgpdComponents(
                    consents,
                    DataRightsService(ds, accounts, hasher, consents),
                    ParentalConsentService(
                        accounts,
                        JdbcParentalConsentRepository(ds),
                        OtpService(JdbcOtpRepository(ds), TEST_OTP_KEY),
                        MessageGateway(sender, roomyLimits(clock)),
                        TEST_POLICY,
                    ),
                    accessLogs,
                    accounts,
                    JdbcContactRepository(ds),
                ),
            genderEnabled = genderEnabled,
        )
}

fun signupBody(
    handle: String,
    birthDate: String?,
    extra: String = "",
): String {
    val birth = birthDate?.let { ""","birth_date":"$it"""" }.orEmpty()
    return """{"handle":"$handle","password":"$STRONG_PASSWORD"$birth$extra}"""
}

fun loginBody(handle: String) = """{"handle":"$handle","password":"$STRONG_PASSWORD"}"""
