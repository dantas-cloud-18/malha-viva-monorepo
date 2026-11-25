package br.malhaviva.app

import br.malhaviva.feature.auth.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharingBlockStoreTest {
    private class Rig(
        scope: kotlinx.coroutines.CoroutineScope,
        var server: String?,
    ) {
        val session = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var fetches = 0
        val store =
            SharingBlockStore(
                session,
                {
                    fetches++
                    server
                },
                scope,
            )
    }

    @Test
    fun `starts blocked and does not query while logged out`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, "adult")
            assertTrue(rig.store.blocked.value)
            rig.store.refresh()
            assertTrue(rig.store.blocked.value)
            assertEquals(0, rig.fetches)
        }

    @Test
    fun `adult unlocks sharing after login`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, "adult")
            rig.session.value = SessionState.LoggedIn
            assertFalse(rig.store.blocked.value)
        }

    @Test
    fun `minors and unknown values stay blocked`() =
        runTest(UnconfinedTestDispatcher()) {
            for (bracket in listOf("under_12", "teen_12_17", "qualquer_coisa")) {
                val rig = Rig(backgroundScope, bracket)
                rig.session.value = SessionState.LoggedIn
                assertTrue(bracket, rig.store.blocked.value)
            }
        }

    @Test
    fun `unknown bracket without prior confirmation stays blocked`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, null)
            rig.session.value = SessionState.LoggedIn
            assertTrue(rig.store.blocked.value)
        }

    @Test
    fun `failed refresh keeps the last confirmed value and logout blocks again`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, "adult")
            rig.session.value = SessionState.LoggedIn
            rig.server = null
            rig.store.refresh()
            assertFalse(rig.store.blocked.value)
            rig.session.value = SessionState.LoggedOut
            assertTrue(rig.store.blocked.value)
        }
}
