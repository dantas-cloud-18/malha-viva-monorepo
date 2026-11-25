package br.malhaviva.auth.recovery

import java.util.UUID

/** Eventos do 2FA que avisam o dono da conta. Os textos nunca carregam codigo nenhum. */
enum class SecurityEvent(
    val text: String,
) {
    BACKUP_CODE_USED(
        "um codigo de recuperacao do 2FA foi usado para entrar na sua conta. " +
            "Se nao foi voce, troque sua senha e gere novos codigos.",
    ),
    BACKUP_CODES_REGENERATED(
        "seus codigos de recuperacao do 2FA foram regenerados e os antigos deixaram de valer. " +
            "Se nao foi voce, troque sua senha.",
    ),
}

fun interface SecurityNotifier {
    fun notify(
        accountId: UUID,
        event: SecurityEvent,
    )
}

/** Avisa todos os canais VERIFICADOS da conta pelo [MessageGateway], fora do caminho da requisicao. */
class ContactSecurityNotifier(
    private val contacts: ContactRepository,
    private val gateway: MessageGateway,
    private val background: (suspend () -> Unit) -> Unit,
) : SecurityNotifier {
    override fun notify(
        accountId: UUID,
        event: SecurityEvent,
    ) {
        val info = contacts.find(accountId)
        background {
            info.verifiedChannels().forEach { channel ->
                info.value(channel)?.let {
                    gateway.deliver(accountId, channel, it, "Aviso Malha Viva", "Malha Viva: ${event.text}")
                }
            }
        }
    }
}
