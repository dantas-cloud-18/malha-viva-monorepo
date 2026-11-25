package br.malhaviva.auth.recovery

import br.malhaviva.auth.AccountRepository
import br.malhaviva.auth.PasswordHasher
import br.malhaviva.auth.normalizePassword
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val CHANGE_WAIT: Duration = Duration.ofHours(24)

sealed interface SetContactResult {
    data object Invalid : SetContactResult

    data object BadPassword : SetContactResult

    data object VerificationSent : SetContactResult

    data class ChangePending(
        val changeId: UUID,
        val effectiveAt: Instant,
    ) : SetContactResult
}

/**
 * Contatos de recuperacao do titular autenticado: cadastro, verificacao e troca. Fora do cadastro, TODA operacao
 * (primeiro contato, verificacao do contato gravado ou troca) exige a senha atual: um access token roubado nao
 * basta para plantar um canal de recuperacao. Troca de canal ja verificado exige ainda OTP no NOVO canal e 24 h
 * de espera cancelavel; os canais ja verificados sao avisados em toda inclusao e troca. A troca e aplicada de
 * forma preguicosa ([applyDue]) na proxima leitura ou recuperacao.
 * shortcut: sem job periodico; chamar applyDue por varredura se for preciso aplicar sem trafego da conta.
 *
 * Suppress: injecao de dependencias e um unico fluxo de contato; dividir partiria o estado compartilhado.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ContactService(
    private val accounts: AccountRepository,
    private val contacts: ContactRepository,
    private val changes: ContactChangeRepository,
    private val otp: OtpService,
    private val gateway: MessageGateway,
    private val hasher: PasswordHasher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun now(): Instant = Instant.ofEpochMilli(clock())

    suspend fun set(
        accountId: UUID,
        channel: Channel,
        rawValue: String,
        currentPassword: String?,
    ): SetContactResult {
        applyDue(accountId)
        val value = normalizeContact(channel, rawValue)
        val info = contacts.find(accountId)
        return when {
            value == null -> SetContactResult.Invalid
            !passwordMatches(accountId, currentPassword) -> SetContactResult.BadPassword
            !info.verified(channel) -> startVerification(accountId, channel, value, SendContext())
            value == info.value(channel) -> SetContactResult.Invalid
            else -> startChange(accountId, channel, value, info.value(channel))
        }
    }

    fun validSignupContacts(
        phone: String?,
        email: String?,
    ): Boolean = (phone == null || normalizePhone(phone) != null) && (email == null || normalizeEmail(email) != null)

    /**
     * Grava os contatos do cadastro sem verificar e envia o OTP de verificacao de cada um, na cota e nos limites
     * de [SendPurpose.SIGNUP]. E o unico caminho sem senha: a conta acabou de ser criada e nao tem canal verificado.
     */
    suspend fun registerContacts(
        handle: String,
        phone: String?,
        email: String?,
        ip: String,
    ) {
        val id = accounts.findByHandle(handle)?.id ?: return
        val context = SendContext(SendPurpose.SIGNUP, ip)
        phone?.let { normalizePhone(it)?.let { v -> startVerification(id, Channel.PHONE, v, context) } }
        email?.let { normalizeEmail(it)?.let { v -> startVerification(id, Channel.EMAIL, v, context) } }
    }

    /** Senha atual confere? Usada tambem pela rota de confirmacao do contato gravado. */
    fun verifyPassword(
        accountId: UUID,
        password: String?,
    ): Boolean = passwordMatches(accountId, password)

    private fun passwordMatches(
        accountId: UUID,
        password: String?,
    ): Boolean {
        val hash = accounts.findById(accountId)?.passwordHash
        return password != null && hash != null && hasher.verify(normalizePassword(password), hash)
    }

    private suspend fun startVerification(
        accountId: UUID,
        channel: Channel,
        value: String,
        context: SendContext,
    ): SetContactResult {
        contacts.store(accountId, channel, value)
        val code = otp.issue(accountId, OtpPurpose.VERIFY_CONTACT, channel, value)
        val text = "Malha Viva: codigo de verificacao $code"
        gateway.deliver(accountId, channel, value, "Codigo Malha Viva", text, context)
        notifyVerified(accountId, "Um novo contato foi adicionado a sua conta. Se nao foi voce, troque sua senha.")
        return SetContactResult.VerificationSent
    }

    /** Avisa todos os canais JA verificados da conta (o canal que acabou de entrar ainda nao conta). */
    private suspend fun notifyVerified(
        accountId: UUID,
        text: String,
        except: Channel? = null,
    ) {
        val info = contacts.find(accountId)
        info.verifiedChannels().filter { it != except }.forEach { ch ->
            info.value(ch)?.let { notify(accountId, ch, it, text) }
        }
    }

    private suspend fun startChange(
        accountId: UUID,
        channel: Channel,
        value: String,
        oldValue: String?,
    ): SetContactResult {
        changes.cancel(accountId, channel, now())
        val change = ContactChange(UUID.randomUUID(), accountId, channel, value, now(), now().plus(CHANGE_WAIT))
        changes.create(change)
        val code = otp.issue(accountId, OtpPurpose.CONTACT_CHANGE, channel, change.id.toString())
        gateway.deliver(accountId, channel, value, "Codigo Malha Viva", "Malha Viva: codigo para trocar contato $code")
        oldValue?.let { notify(accountId, channel, it, "Pedido de troca deste contato. Vale em 24 h; cancele no app.") }
        notifyVerified(accountId, "Pedido de troca de contato. Vale em 24 h; cancele no app.", except = channel)
        return SetContactResult.ChangePending(change.id, change.effectiveAt)
    }

    private suspend fun notify(
        accountId: UUID,
        channel: Channel,
        to: String,
        text: String,
    ) {
        gateway.deliver(accountId, channel, to, "Aviso Malha Viva", "Malha Viva: $text")
    }

    /** Sem [changeId]: verifica o contato gravado. Com [changeId]: confirma o novo canal de uma troca. */
    suspend fun confirm(
        accountId: UUID,
        channel: Channel,
        code: String,
        changeId: UUID?,
    ): Boolean {
        val ok =
            if (changeId == null) {
                confirmStored(accountId, channel, code)
            } else {
                confirmChange(accountId, changeId, code)
            }
        applyDue(accountId)
        return ok
    }

    private fun confirmStored(
        accountId: UUID,
        channel: Channel,
        code: String,
    ): Boolean {
        val value = contacts.find(accountId).value(channel)
        val rec = value?.let { otp.check(accountId, OtpPurpose.VERIFY_CONTACT, channel, it, code) }
        return rec != null &&
            value != null &&
            otp.consume(rec) &&
            contacts.setVerified(accountId, channel, value, now())
    }

    private fun confirmChange(
        accountId: UUID,
        changeId: UUID,
        code: String,
    ): Boolean {
        val change = changes.find(changeId)?.takeIf { it.accountId == accountId && it.open }
        val rec = change?.let { otp.check(accountId, OtpPurpose.CONTACT_CHANGE, it.channel, changeId.toString(), code) }
        return rec != null && otp.consume(rec).also { if (it) changes.markConfirmed(changeId, now()) }
    }

    /** Aplica as trocas confirmadas cuja espera acabou. */
    suspend fun applyDue(accountId: UUID) {
        val now = now()
        changes.open(accountId).filter { it.confirmedAt != null && !it.effectiveAt.isAfter(now) }.forEach { c ->
            val old = contacts.find(accountId).value(c.channel)
            if (contacts.setVerified(accountId, c.channel, c.newValue, now)) {
                changes.markApplied(c.id, now)
                old?.let { notify(accountId, c.channel, it, "Este contato foi substituido por outro.") }
            } else {
                changes.cancelOne(accountId, c.id, now)
            }
        }
    }

    /** Cancela a troca; false se nao existe ou e de outra conta (mesma resposta, sem IDOR). */
    suspend fun cancel(
        accountId: UUID,
        changeId: UUID,
    ): Boolean {
        val change = changes.find(changeId)?.takeIf { it.accountId == accountId && it.open }
        val done = change != null && changes.cancelOne(accountId, changeId, now())
        val old = change?.takeIf { done }?.let { contacts.find(accountId).value(it.channel) }
        if (change != null && old != null) notify(accountId, change.channel, old, "Troca de contato cancelada.")
        return done
    }

    suspend fun state(accountId: UUID): Pair<ContactInfo, List<ContactChange>> {
        applyDue(accountId)
        return contacts.find(accountId) to changes.open(accountId)
    }
}
