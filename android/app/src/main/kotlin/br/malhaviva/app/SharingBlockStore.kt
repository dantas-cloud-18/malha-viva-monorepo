package br.malhaviva.app

import br.malhaviva.feature.auth.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val ADULT = "adult"

/**
 * Bloqueio de compartilhamento de localizacao pela faixa etaria da conta (politica de menores: so adulto
 * compartilha). Falha fechada: nasce bloqueado, volta a bloqueado quando a sessao deixa de estar ativa e so
 * libera quando o servidor informa `adult`. Faixa desconhecida (sem rede, campo ausente) continua bloqueada;
 * uma carga que falha por rede mantem o ultimo valor confirmado.
 *
 * [fetch] devolve a faixa (`under_12`, `teen_12_17`, `adult`) ou null se nao deu para saber.
 */
class SharingBlockStore(
    sessionState: StateFlow<SessionState>,
    private val fetch: suspend () -> String?,
    scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(true)
    val blocked: StateFlow<Boolean> = mutable.asStateFlow()

    @Volatile
    private var loggedIn = false

    init {
        scope.launch {
            sessionState.collect { state ->
                loggedIn = state == SessionState.LoggedIn
                if (loggedIn) refresh() else mutable.value = true
            }
        }
    }

    suspend fun refresh() {
        if (!loggedIn) return
        val bracket = fetch() ?: return
        if (loggedIn) mutable.value = bracket != ADULT
    }
}
