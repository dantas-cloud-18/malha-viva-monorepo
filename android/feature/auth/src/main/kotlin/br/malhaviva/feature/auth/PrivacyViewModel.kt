package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.PrivacyApi
import kotlinx.coroutines.launch

/** Avisado quando o servidor confirma o estado do consentimento de localizacao (carga ou alteracao). */
fun interface LocationConsentListener {
    fun onLocationConsent(granted: Boolean)
}

class PrivacyViewModel(
    private val session: AuthSession,
    private val privacy: PrivacyApi,
    private val locationListener: LocationConsentListener = LocationConsentListener {},
) : MviViewModel<PrivacyState, PrivacyIntent>(PrivacyState()) {
    override fun reduce(
        state: PrivacyState,
        intent: PrivacyIntent,
    ): PrivacyState = reducePrivacy(state, intent)

    fun act(intent: PrivacyIntent) {
        val before = state.value
        onIntent(intent)
        val submit = intent == PrivacyIntent.SubmitHandle || intent == PrivacyIntent.SubmitDelete
        if (submit && (before.busy || !state.value.busy)) return
        viewModelScope.launch {
            when (intent) {
                PrivacyIntent.Open -> load()
                is PrivacyIntent.Toggle -> toggle(intent)
                PrivacyIntent.ShowHistory -> history()
                PrivacyIntent.SubmitHandle -> saveHandle(before)
                PrivacyIntent.SubmitDelete -> delete(before)
                PrivacyIntent.Export -> export()
                else -> Unit
            }
        }
    }

    private suspend fun load() {
        when (val result = session.authed { privacy.getConsents(it) }) {
            is ApiResult.Ok -> {
                locationListener.onLocationConsent(result.value.isLocationGranted())
                onIntent(PrivacyIntent.Loaded(result.value))
            }
            is ApiResult.Fail -> onIntent(PrivacyIntent.Failed(result.error))
        }
    }

    /** Conceder envia a versao da politica; revogar e um toque so e nao depende de nada alem da rede. */
    private suspend fun toggle(intent: PrivacyIntent.Toggle) {
        val result =
            session.authed {
                if (intent.on) {
                    privacy.grantConsent(it, intent.purpose.wire, POLICY_VERSION)
                } else {
                    privacy.revokeConsent(it, intent.purpose.wire)
                }
            }
        if (result is ApiResult.Ok && intent.purpose == ConsentPurpose.Location) {
            locationListener.onLocationConsent(intent.on)
        }
        onIntent(
            when (result) {
                is ApiResult.Ok -> PrivacyIntent.Toggled(intent.purpose, intent.on)
                is ApiResult.Fail -> PrivacyIntent.Failed(result.error)
            },
        )
    }

    private suspend fun history() {
        when (val result = session.authed { privacy.consentHistory(it) }) {
            is ApiResult.Ok -> onIntent(PrivacyIntent.HistoryLoaded(result.value))
            is ApiResult.Fail -> onIntent(PrivacyIntent.Failed(result.error))
        }
    }

    private suspend fun saveHandle(form: PrivacyState) {
        when (val result = session.authed { privacy.correctHandle(it, normalizeHandle(form.newHandle)) }) {
            is ApiResult.Ok -> onIntent(PrivacyIntent.HandleSaved(result.value.ageBracket?.let { it == ADULT }))
            is ApiResult.Fail -> onIntent(PrivacyIntent.Failed(result.error))
        }
    }

    private suspend fun export() {
        when (val result = session.authed { privacy.exportData(it) }) {
            is ApiResult.Ok -> onIntent(PrivacyIntent.Exported(result.value))
            is ApiResult.Fail -> onIntent(PrivacyIntent.Failed(result.error))
        }
    }

    /** Exclusao exige a senha; depois dela os tokens morrem no servidor, entao so limpamos o aparelho. */
    private suspend fun delete(form: PrivacyState) {
        when (val result = session.authed { privacy.deleteAccount(it, form.password) }) {
            is ApiResult.Ok -> {
                session.logout()
                onIntent(PrivacyIntent.Deleted)
            }
            is ApiResult.Fail -> onIntent(PrivacyIntent.Failed(result.error))
        }
    }

    private companion object {
        const val ADULT = "adult"
    }
}
