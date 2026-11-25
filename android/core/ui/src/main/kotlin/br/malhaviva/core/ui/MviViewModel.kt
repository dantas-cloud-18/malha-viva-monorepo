package br.malhaviva.core.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

abstract class MviViewModel<State, Intent>(
    initialState: State,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun onIntent(intent: Intent) {
        mutableState.update { reduce(it, intent) }
    }

    protected abstract fun reduce(
        state: State,
        intent: Intent,
    ): State
}
