package br.malhaviva.auth.recovery

import org.slf4j.LoggerFactory

class MessageDeliveryException(
    message: String,
) : RuntimeException(message)

/**
 * Saida de SMS e e-mail. Provedores reais (Twilio Verify ou Zenvia; SES ou Resend) sao PROPOSTA, ainda
 * nao decididos: ate la, producao usa [FailClosedMessageSender]. Implementacoes lancam
 * [MessageDeliveryException] sem dados pessoais na mensagem.
 */
interface MessageSender {
    suspend fun sendSms(
        toE164: String,
        text: String,
    )

    suspend fun sendEmail(
        to: String,
        subject: String,
        text: String,
    )
}

/**
 * ATENCAO: PADRAO DE PRODUCAO ATE A ESCOLHA DO PROVEDOR. Recusa todo envio (fail-closed): o pedido de
 * recuperacao continua respondendo 202 generico e o erro e logado sem dado pessoal.
 */
object FailClosedMessageSender : MessageSender {
    override suspend fun sendSms(
        toE164: String,
        text: String,
    ): Unit = throw MessageDeliveryException("no message provider configured")

    override suspend fun sendEmail(
        to: String,
        subject: String,
        text: String,
    ): Unit = throw MessageDeliveryException("no message provider configured")
}

/**
 * Para desenvolvimento. Em producao registra so que uma mensagem saiu. Com [showOtp] (env
 * MALHAVIVA_DEV_SHOW_OTP=true) imprime destino e texto, que contem o OTP: nunca ligar fora do dev.
 */
class LoggingMessageSender(
    private val showOtp: Boolean = false,
) : MessageSender {
    private val log = LoggerFactory.getLogger("br.malhaviva.auth.recovery.LoggingMessageSender")

    override suspend fun sendSms(
        toE164: String,
        text: String,
    ) = record("sms", toE164, text)

    override suspend fun sendEmail(
        to: String,
        subject: String,
        text: String,
    ) = record("email", to, "$subject: $text")

    private fun record(
        channel: String,
        to: String,
        text: String,
    ) {
        if (showOtp) {
            log.warn(
                "DEV mensagem {} para {}: {}",
                channel,
                to,
                text,
            )
        } else {
            log.info("Mensagem {} enviada", channel)
        }
    }
}
