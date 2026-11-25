package br.malhaviva.app

import br.malhaviva.feature.auth.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationConsentStoreTest {
    private class Rig(
        scope: kotlinx.coroutines.CoroutineScope,
        var server: Boolean? = false,
    ) {
        val session = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var fetches = 0
        val store =
            LocationConsentStore(
                session,
                {
                    fetches++
                    server
                },
                scope,
            )
    }

    @Test
    fun `starts denied and stays denied while logged out`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = true)
            assertFalse(rig.store.granted.value)
            rig.store.refresh() // sem sessao nao consulta nem concede
            assertFalse(rig.store.granted.value)
            assertTrue(rig.fetches == 0)
        }

    @Test
    fun `login loads the server consent`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = true)
            rig.session.value = SessionState.LoggedIn
            assertTrue(rig.store.granted.value)
        }

    @Test
    fun `login with consent not granted stays denied`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = false)
            rig.session.value = SessionState.LoggedIn
            assertFalse(rig.store.granted.value)
        }

    @Test
    fun `failed load without a prior confirmation stays denied`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = null)
            rig.session.value = SessionState.LoggedIn
            assertFalse(rig.store.granted.value)
        }

    @Test
    fun `failed refresh keeps the last confirmed value`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = true)
            rig.session.value = SessionState.LoggedIn
            rig.server = null
            rig.store.refresh()
            assertTrue(rig.store.granted.value)
        }

    @Test
    fun `refresh picks up a revocation made elsewhere`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = true)
            rig.session.value = SessionState.LoggedIn
            rig.server = false
            rig.store.refresh()
            assertFalse(rig.store.granted.value)
        }

    @Test
    fun `toggle in the privacy screen grants and revokes immediately`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = false)
            rig.session.value = SessionState.LoggedIn
            rig.store.onLocationConsent(true)
            assertTrue(rig.store.granted.value)
            rig.store.onLocationConsent(false)
            assertFalse(rig.store.granted.value)
        }

    @Test
    fun `logout and enrollment-only sessions revoke the consent`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope, server = true)
            rig.session.value = SessionState.LoggedIn
            assertTrue(rig.store.granted.value)
            rig.session.value = SessionState.LoggedOut
            assertFalse(rig.store.granted.value)
            rig.session.value = SessionState.LoggedIn
            rig.session.value = SessionState.EnrollmentOnly
            assertFalse(rig.store.granted.value)
        }

    @Test
    fun `a stale load cannot override a revocation made while it was in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Boolean?>()
            val session = MutableStateFlow<SessionState>(SessionState.LoggedIn)
            val store = LocationConsentStore(session, { gate.await() }, backgroundScope)
            store.onLocationConsent(true)
            val pending = backgroundScope.launch { store.refresh() }
            store.onLocationConsent(false) // usuario revoga enquanto a carga antiga voa
            gate.complete(true) // resposta velha: ainda diz "concedido"
            pending.join()
            assertFalse(store.granted.value)
        }
}
