package br.malhaviva.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import br.malhaviva.feature.auth.AuthEntry
import br.malhaviva.feature.auth.AuthFlows
import br.malhaviva.feature.auth.AuthIntent
import br.malhaviva.feature.auth.AuthScreen
import br.malhaviva.feature.auth.AuthViewModel
import br.malhaviva.feature.auth.SessionState
import br.malhaviva.feature.tracking.TrackingScreen
import br.malhaviva.feature.tracking.TrackingViewModel
import kotlinx.coroutines.flow.StateFlow

private enum class Tab(
    val label: String,
) {
    Trains("Trens"),
    Trip("Viagem"),
    Account("Conta"),
}

// Telas que so existem com sessao: se a sessao cair por outro caminho, o fluxo de conta volta ao login.
private val SESSION_SCREENS =
    setOf(AuthScreen.Devices, AuthScreen.Contacts, AuthScreen.Privacy, AuthScreen.Totp)

/** Raiz da navegacao: sem sessao, o fluxo de conta; com sessao, as abas Trens, Viagem e Conta. */
@Composable
fun AppRoot(
    sessionState: StateFlow<SessionState>,
    auth: AuthViewModel,
    flows: AuthFlows,
    tracking: TrackingViewModel,
) {
    val session by sessionState.collectAsState()
    LaunchedEffect(session) {
        if (session == SessionState.LoggedOut && auth.state.value.screen in SESSION_SCREENS) {
            auth.act(AuthIntent.SessionLost)
        }
    }
    if (session == SessionState.LoggedIn) {
        MainShell(auth, flows, tracking)
    } else {
        AuthEntry(auth, flows, Modifier.systemBarsPadding())
    }
}

@Composable
private fun MainShell(
    auth: AuthViewModel,
    flows: AuthFlows,
    tracking: TrackingViewModel,
) {
    var selected by rememberSaveable { mutableIntStateOf(Tab.Trains.ordinal) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab.ordinal,
                        onClick = { selected = tab.ordinal },
                        icon = {},
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[selected]) {
                Tab.Trains -> CatalogScreen()
                Tab.Trip -> TrackingScreen(tracking, Modifier.statusBarsPadding())
                Tab.Account -> AuthEntry(auth, flows, Modifier.statusBarsPadding())
            }
        }
    }
}
