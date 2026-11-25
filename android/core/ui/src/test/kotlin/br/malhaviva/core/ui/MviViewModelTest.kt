package br.malhaviva.core.ui

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MviViewModelTest {
    private class CounterViewModel : MviViewModel<Int, Int>(initialState = 0) {
        override fun reduce(
            state: Int,
            intent: Int,
        ) = state + intent
    }

    @Test
    fun `intents reduce into emitted states`() =
        runTest {
            val viewModel = CounterViewModel()
            viewModel.state.test {
                assertEquals(0, awaitItem())
                viewModel.onIntent(2)
                assertEquals(2, awaitItem())
                viewModel.onIntent(3)
                assertEquals(5, awaitItem())
            }
        }
}
