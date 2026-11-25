package br.malhaviva.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.malhaviva.core.designsystem.MalhaVivaTheme
import br.malhaviva.feature.auth.AccountRecovery
import br.malhaviva.feature.auth.AuthFlows
import br.malhaviva.feature.auth.AuthSession
import br.malhaviva.feature.auth.AuthViewModel
import br.malhaviva.feature.auth.ContactsViewModel
import br.malhaviva.feature.auth.ParentalConsent
import br.malhaviva.feature.auth.ParentalViewModel
import br.malhaviva.feature.auth.PrivacyViewModel
import br.malhaviva.feature.auth.RecoveryViewModel
import br.malhaviva.feature.auth.TotpViewModel
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.PrivacyApi
import br.malhaviva.feature.tracking.TrackingViewModel
import br.malhaviva.feature.trains.TrainsViewModel
import br.malhaviva.feature.trains.net.LiveTrainsClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: AuthSession

    @Inject lateinit var account: AccountApi

    @Inject lateinit var privacyApi: PrivacyApi

    @Inject lateinit var recovery: AccountRecovery

    @Inject lateinit var parental: ParentalConsent

    @Inject lateinit var consent: LocationConsentStore

    @Inject lateinit var liveTrains: LiveTrainsClient

    // Cada ViewModel e criado sob demanda pela fabrica (os modulos de feature nao usam Hilt).
    private val auth by viewModels<AuthViewModel> {
        factory { AuthViewModel(session, account, "${Build.MANUFACTURER} ${Build.MODEL}") }
    }
    private val recoveryVm by viewModels<RecoveryViewModel> { factory { RecoveryViewModel(recovery) } }
    private val totpVm by viewModels<TotpViewModel> { factory { TotpViewModel(session, account) } }
    private val contactsVm by viewModels<ContactsViewModel> { factory { ContactsViewModel(session, account) } }
    private val parentalVm by viewModels<ParentalViewModel> { factory { ParentalViewModel(parental) } }
    private val privacyVm by viewModels<PrivacyViewModel> {
        factory { PrivacyViewModel(session, privacyApi, consent) }
    }

    private val trains by viewModels<TrainsViewModel> { factory { TrainsViewModel(liveTrains) } }

    // TODO(fase 6): sharingBlocked deve vir da faixa etaria do perfil (menor de 18 anos); hoje o servidor decide.
    private val tracking by viewModels<TrackingViewModel> { factory { TrackingViewModel() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flows = AuthFlows(recoveryVm, totpVm, contactsVm, parentalVm, privacyVm)
        setContent {
            MalhaVivaTheme { AppRoot(session.state, auth, flows, tracking, trains) }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pega revogacoes feitas em outro aparelho antes de qualquer viagem comecar.
        lifecycleScope.launch { consent.refresh() }
    }

    private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) =
        viewModelFactory { initializer { create() } }
}
