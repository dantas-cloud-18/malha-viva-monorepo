package br.malhaviva.auth.recovery

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.CaptchaVerifier
import br.malhaviva.auth.PasswordHasher
import br.malhaviva.auth.PasswordPolicy
import br.malhaviva.auth.SessionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("br.malhaviva.auth.recovery.RecoveryConfig")
private const val SENDER_ENV = "MALHAVIVA_MESSAGE_SENDER"
private const val SHOW_OTP_ENV = "MALHAVIVA_DEV_SHOW_OTP"
private const val SMS_CEILING_ENV = "MALHAVIVA_SMS_DAILY_CEILING"
private const val EMAIL_CEILING_ENV = "MALHAVIVA_EMAIL_DAILY_CEILING"

/**
 * Monta a recuperacao para producao. Falta de MALHAVIVA_OTP_KEY derruba a inicializacao (fail-closed).
 * Sem provedor de SMS/e-mail escolhido, o padrao e o [FailClosedMessageSender]: nada sai e o pedido segue
 * respondendo o 202 generico. `MALHAVIVA_MESSAGE_SENDER=log` usa o [LoggingMessageSender]; so com
 * `MALHAVIVA_DEV_SHOW_OTP=true` ele imprime o codigo (nunca em producao).
 */
object RecoveryConfig {
    fun senderFromEnv(env: (String) -> String?): MessageSender {
        val show = env(SHOW_OTP_ENV) == "true"
        return if (show || env(SENDER_ENV) == "log") {
            if (show) log.warn("$SHOW_OTP_ENV ligado: OTPs aparecem no log. Somente desenvolvimento")
            LoggingMessageSender(show)
        } else {
            log.warn("Nenhum provedor de SMS/e-mail configurado: recuperacao nao envia mensagens (fail-closed)")
            FailClosedMessageSender
        }
    }

    fun limitsFromEnv(
        env: (String) -> String?,
        clock: () -> Long = System::currentTimeMillis,
    ): SendLimits =
        SendLimits(
            clock = clock,
            sms = SpendCeiling("sms", env(SMS_CEILING_ENV)?.toIntOrNull() ?: DEFAULT_SMS_DAILY_CEILING, clock),
            email = SpendCeiling("email", env(EMAIL_CEILING_ENV)?.toIntOrNull() ?: DEFAULT_EMAIL_DAILY_CEILING, clock),
        )

    /** Tarefas de segundo plano da recuperacao; falhas logam so o tipo da excecao (sem dado pessoal). */
    fun backgroundLauncher(): (suspend () -> Unit) -> Unit {
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler {
                        _,
                        e,
                        ->
                        log.error("Falha em tarefa de recuperacao: {}", e.javaClass.simpleName)
                    },
            )
        return { task -> scope.launch { task() } }
    }

    // injecao de dependencias de producao; agrupar so deslocaria os parametros
    @Suppress("LongParameterList")
    fun build(
        dataSource: DataSource,
        env: (String) -> String?,
        accounts: AccountRepository,
        sessions: SessionService,
        policy: PasswordPolicy,
        hasher: PasswordHasher,
        captcha: CaptchaVerifier,
    ): RecoveryComponents {
        val otp = OtpService(JdbcOtpRepository(dataSource), otpKeyFromEnv(env))
        val gateway = MessageGateway(senderFromEnv(env), limitsFromEnv(env))
        val contacts = JdbcContactRepository(dataSource)
        val changes = JdbcContactChangeRepository(dataSource)
        val contactService = ContactService(accounts, contacts, changes, otp, gateway, hasher)
        val service =
            RecoveryService(
                accounts,
                contacts,
                contactService,
                changes,
                otp,
                gateway,
                sessions,
                policy,
                hasher,
                captcha,
                background = backgroundLauncher(),
            )
        return RecoveryComponents(service, contactService)
    }
}
