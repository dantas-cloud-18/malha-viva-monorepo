package br.malhaviva.app

import br.malhaviva.feature.auth.LocationConsentListener
import br.malhaviva.feature.auth.SessionState
import br.malhaviva.feature.tracking.LocationConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Consentimento de localizacao da conta, em memoria. Falha fechada: nasce `false`, volta a `false` sempre
 * que a sessao deixa de estar ativa e so vira `true` quando o servidor confirma (carga ao entrar/abrir o
 * app ou o interruptor da tela de Privacidade). Uma carga que falha por rede mantem o ultimo valor
 * confirmado; sem confirmacao previa continua `false`.
 *
 * [fetch] devolve o consentimento vigente no servidor, ou null se nao deu para saber.
 */
class LocationConsentStore(
    sessionState: StateFlow<SessionState>,
    private val fetch: suspend () -> Boolean?,
    scope: CoroutineScope,
) : LocationConsent,
    LocationConsentListener {
    private val mutable = MutableStateFlow(false)
    override val granted: StateFlow<Boolean> = mutable.asStateFlow()

    /** Conta mudancas confirmadas pelo usuario; uma carga que comecou antes de uma mudanca nao a sobrescreve. */
    @Volatile
    private var epoch = 0

    @Volatile
    private var loggedIn = false

    init {
        scope.launch {
            sessionState.collect { state ->
                loggedIn = state == SessionState.LoggedIn
                if (loggedIn) refresh() else set(false)
            }
        }
    }

    override fun onLocationConsent(granted: Boolean) = set(granted)

    /** Consulta o servidor; chamada ao abrir o app para pegar revogacoes feitas em outro aparelho. */
    suspend fun refresh() {
        if (!loggedIn) return
        val started = epoch
        val value = fetch() ?: return
        if (loggedIn && epoch == started) mutable.value = value
    }

    private fun set(value: Boolean) {
        epoch++
        mutable.value = value
    }
}
