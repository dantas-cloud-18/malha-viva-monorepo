package br.malhaviva.feature.auth

import androidx.lifecycle.viewModelScope
import br.malhaviva.core.ui.MviViewModel
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.ApiResult
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val session: AuthSession,
    private val account: AccountApi,
) : MviViewModel<ContactsState, ContactsIntent>(ContactsState()) {
    override fun reduce(
        state: ContactsState,
        intent: ContactsIntent,
    ): ContactsState = reduceContacts(state, intent)

    fun act(intent: ContactsIntent) {
        val before = state.value
        onIntent(intent)
        val submit = intent == ContactsIntent.SubmitAdd || intent == ContactsIntent.SubmitConfirm
        if (submit && (before.busy || !state.value.busy)) return
        viewModelScope.launch {
            when (intent) {
                ContactsIntent.Open, ContactsIntent.Reload -> load()
                ContactsIntent.SubmitAdd -> add(before)
                ContactsIntent.SubmitConfirm -> confirm(before)
                is ContactsIntent.Cancel -> cancel(intent.changeId)
                else -> Unit
            }
        }
    }

    private suspend fun load() {
        when (val result = session.authed { account.getContacts(it) }) {
            is ApiResult.Ok -> onIntent(ContactsIntent.Loaded(result.value))
            is ApiResult.Fail -> onIntent(ContactsIntent.Failed(result.error))
        }
    }

    private suspend fun add(form: ContactsState) {
        val value = if (form.channel == ContactChannel.Phone) normalizePhone(form.value) else form.value
        when (val result = session.authed { account.setContact(it, form.channel.wire, value, form.password) }) {
            is ApiResult.Ok -> onIntent(ContactsIntent.Started(result.value))
            is ApiResult.Fail -> onIntent(ContactsIntent.Failed(result.error))
        }
    }

    private suspend fun confirm(form: ContactsState) {
        val password = if (form.changeId == null) form.password else null
        val result =
            session.authed { account.confirmContact(it, form.channel.wire, form.code, form.changeId, password) }
        when (result) {
            is ApiResult.Ok -> {
                onIntent(ContactsIntent.Confirmed(wasChange = form.changeId != null))
                act(ContactsIntent.Reload)
            }
            is ApiResult.Fail -> onIntent(ContactsIntent.Failed(result.error))
        }
    }

    private suspend fun cancel(changeId: String) {
        when (val result = session.authed { account.cancelContactChange(it, changeId) }) {
            is ApiResult.Ok -> onIntent(ContactsIntent.Canceled(changeId))
            is ApiResult.Fail -> onIntent(ContactsIntent.Failed(result.error))
        }
    }
}
