package br.malhaviva.feature.tracking

import app.cash.turbine.test
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingReducerTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun run(
        vararg intents: TrackingIntent,
        blocked: Boolean = false,
        consent: Boolean = true,
    ): TrackingState =
        intents.fold<TrackingIntent, TrackingState>(TrackingState.Off) { s, i ->
            reduceTracking(s, i, blocked, consent)
        }

    @Test
    fun `happy path goes from Off to Active`() {
        val state =
            run(
                TrackingIntent.OptIn,
                TrackingIntent.DisclosureAccepted,
                TrackingIntent.PermissionResult(granted = true),
                TrackingIntent.ServiceStarted(sinceMs = 1_000L),
                TrackingIntent.StatusChanged(TripStatus.Sending),
            )
        assertEquals(TrackingState.Active(1_000L, TripStatus.Sending), state)
    }

    @Test
    fun `starts Off and ignores events that skip the opt-in`() {
        assertEquals(TrackingState.Off, run())
        assertEquals(TrackingState.Off, run(TrackingIntent.DisclosureAccepted))
        assertEquals(TrackingState.Off, run(TrackingIntent.PermissionResult(granted = true)))
        assertEquals(TrackingState.Off, run(TrackingIntent.ServiceStarted(1L)))
    }

    @Test
    fun `declining the disclosure returns to Off`() {
        assertEquals(TrackingState.Off, run(TrackingIntent.OptIn, TrackingIntent.DisclosureDeclined))
    }

    @Test
    fun `denied permission stops with reason`() {
        val state =
            run(TrackingIntent.OptIn, TrackingIntent.DisclosureAccepted, TrackingIntent.PermissionResult(false))
        assertEquals(TrackingState.Stopped(StopCause.PermissionDenied), state)
    }

    @Test
    fun `user stop and automatic stop end an active trip`() {
        val active =
            arrayOf(
                TrackingIntent.OptIn,
                TrackingIntent.DisclosureAccepted,
                TrackingIntent.PermissionResult(true),
                TrackingIntent.ServiceStarted(1L),
            )
        assertEquals(TrackingState.Stopped(StopCause.UserStopped), run(*active, TrackingIntent.StopRequested))
        assertEquals(
            TrackingState.Stopped(StopCause.LeftCorridor),
            run(*active, TrackingIntent.ServiceStopped(StopCause.LeftCorridor)),
        )
    }

    @Test
    fun `dismissing a stopped state returns to Off and sharing can be restarted`() {
        val stopped =
            run(TrackingIntent.OptIn, TrackingIntent.DisclosureAccepted, TrackingIntent.PermissionResult(false))
        assertEquals(TrackingState.Off, reduceTracking(stopped, TrackingIntent.Dismissed, false, true))
        assertEquals(TrackingState.NeedsDisclosure, reduceTracking(stopped, TrackingIntent.OptIn, false, true))
    }

    @Test
    fun `blocked sharing never leaves Off`() {
        assertEquals(TrackingState.Off, run(TrackingIntent.OptIn, blocked = true))
    }

    @Test
    fun `viewmodel reduces user intents and service events`() =
        runTest {
            val events = MutableSharedFlow<ServiceEvent>()
            val viewModel = TrackingViewModel(serviceEvents = events, consent = MutableStateFlow(true))
            viewModel.state.test {
                assertEquals(TrackingState.Off, awaitItem())
                viewModel.onIntent(TrackingIntent.OptIn)
                assertEquals(TrackingState.NeedsDisclosure, awaitItem())
                viewModel.onIntent(TrackingIntent.DisclosureAccepted)
                assertEquals(TrackingState.NeedsPermission, awaitItem())
                viewModel.onIntent(TrackingIntent.PermissionResult(true))
                assertEquals(TrackingState.Starting, awaitItem())
                events.emit(ServiceEvent.Started(5L))
                assertEquals(TrackingState.Active(5L, TripStatus.Waiting), awaitItem())
                events.emit(ServiceEvent.Stopped(StopCause.MaxDuration))
                assertEquals(TrackingState.Stopped(StopCause.MaxDuration), awaitItem())
            }
        }

    @Test
    fun `viewmodel with blocked sharing stays Off`() =
        runTest {
            val viewModel =
                TrackingViewModel(
                    sharingBlocked = true,
                    serviceEvents = MutableSharedFlow(),
                    consent = MutableStateFlow(true),
                )
            viewModel.onIntent(TrackingIntent.OptIn)
            assertEquals(TrackingState.Off, viewModel.state.value)
        }

    @Test
    fun `fresh viewmodel learns that the service is already running`() =
        runTest {
            val running = MutableStateFlow<TrackingState.Active?>(TrackingState.Active(9L, TripStatus.Paused))
            val viewModel =
                TrackingViewModel(
                    serviceEvents = MutableSharedFlow(),
                    runningNow = running,
                    consent = MutableStateFlow(true),
                )
            assertEquals(TrackingState.Active(9L, TripStatus.Paused), viewModel.state.value)
        }

    @Test
    fun `bridge replays the active trip until it stops`() {
        TrackingBridge.publish(ServiceEvent.Started(3L))
        TrackingBridge.publish(ServiceEvent.Status(TripStatus.Sending))
        assertEquals(TrackingState.Active(3L, TripStatus.Sending), TrackingBridge.active.value)
        TrackingBridge.publish(ServiceEvent.Stopped(StopCause.UserStopped))
        assertEquals(null, TrackingBridge.active.value)
    }
}
