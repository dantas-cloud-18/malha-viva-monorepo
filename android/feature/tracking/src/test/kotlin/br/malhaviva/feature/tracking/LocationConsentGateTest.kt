package br.malhaviva.feature.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationConsentGateTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun reduce(
        state: TrackingState,
        intent: TrackingIntent,
        consent: Boolean,
    ) = reduceTracking(state, intent, sharingBlocked = false, consentGranted = consent)

    @Test
    fun `without consent opt-in stops at NeedsConsent and never asks for permission`() {
        val state = reduce(TrackingState.Off, TrackingIntent.OptIn, consent = false)
        assertEquals(TrackingState.NeedsConsent, state)
        // nenhum passo seguinte avanca sem consentimento
        assertEquals(state, reduce(state, TrackingIntent.DisclosureAccepted, consent = false))
        assertEquals(state, reduce(state, TrackingIntent.PermissionResult(true), consent = false))
        assertEquals(TrackingState.Off, reduce(state, TrackingIntent.Dismissed, consent = false))
    }

    @Test
    fun `consent revoked during setup or run stops with ConsentRevoked`() {
        val revoked = TrackingState.Stopped(StopCause.ConsentRevoked)
        listOf(
            TrackingState.NeedsDisclosure,
            TrackingState.NeedsPermission,
            TrackingState.Starting,
            TrackingState.Active(1L, TripStatus.Sending),
        ).forEach { assertEquals(revoked, reduce(it, TrackingIntent.ConsentChanged(false), consent = false)) }
    }

    @Test
    fun `consent revoked while idle changes nothing`() {
        assertEquals(TrackingState.Off, reduce(TrackingState.Off, TrackingIntent.ConsentChanged(false), false))
        val stopped = TrackingState.Stopped(StopCause.UserStopped)
        assertEquals(stopped, reduce(stopped, TrackingIntent.ConsentChanged(false), false))
    }

    @Test
    fun `granting consent only clears the consent notice, it never starts tracking`() {
        assertEquals(TrackingState.Off, reduce(TrackingState.NeedsConsent, TrackingIntent.ConsentChanged(true), true))
        assertEquals(TrackingState.Off, reduce(TrackingState.Off, TrackingIntent.ConsentChanged(true), true))
    }

    @Test
    fun `consent lost before the permission answer cancels the start`() {
        val revoked = TrackingState.Stopped(StopCause.ConsentRevoked)
        assertEquals(revoked, reduce(TrackingState.NeedsPermission, TrackingIntent.PermissionResult(true), false))
        assertEquals(revoked, reduce(TrackingState.NeedsDisclosure, TrackingIntent.DisclosureAccepted, false))
    }

    @Test
    fun `a stopped trip can restart only with consent`() {
        val stopped = TrackingState.Stopped(StopCause.ConsentRevoked)
        assertEquals(TrackingState.NeedsConsent, reduce(stopped, TrackingIntent.OptIn, consent = false))
        assertEquals(TrackingState.NeedsDisclosure, reduce(stopped, TrackingIntent.OptIn, consent = true))
    }

    @Test
    fun `default environment denies consent`() {
        assertFalse(DeniedLocationConsent.granted.value)
        assertFalse(TrackingEnvironment.consent.granted.value)
    }

    @Test
    fun `viewmodel stops a running trip when consent is revoked and blocks a new one`() =
        runTest {
            val consent = MutableStateFlow(true)
            val running = MutableStateFlow<TrackingState.Active?>(TrackingState.Active(9L, TripStatus.Sending))
            val viewModel =
                TrackingViewModel(serviceEvents = MutableSharedFlow(), runningNow = running, consent = consent)
            assertEquals(TrackingState.Active(9L, TripStatus.Sending), viewModel.state.value)
            consent.value = false
            assertEquals(TrackingState.Stopped(StopCause.ConsentRevoked), viewModel.state.value)
            viewModel.onIntent(TrackingIntent.OptIn)
            assertEquals(TrackingState.NeedsConsent, viewModel.state.value)
        }
}
