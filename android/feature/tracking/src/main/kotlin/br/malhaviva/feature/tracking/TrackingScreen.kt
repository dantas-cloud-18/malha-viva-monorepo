package br.malhaviva.feature.tracking

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import br.malhaviva.core.designsystem.CheckInButton
import br.malhaviva.core.designsystem.EmptyState
import br.malhaviva.core.designsystem.MalhaVivaSpacing

/** Tela do compartilhamento de viagem: cuida de permissao e de iniciar e parar o servico. */
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            viewModel.onIntent(
                TrackingIntent.PermissionResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true),
            )
        }
    LaunchedEffect(state) {
        when (state) {
            TrackingState.NeedsPermission -> permissionLauncher.launch(requiredPermissions())
            TrackingState.Starting -> TrackingService.start(context)
            else -> Unit
        }
    }
    TrackingContent(
        state = state,
        sharingBlocked = viewModel.sharingBlocked,
        onIntent = { intent ->
            viewModel.onIntent(intent)
            if (intent == TrackingIntent.StopRequested) TrackingService.stop(context)
        },
        modifier = modifier,
    )
}

/** Apenas "durante o uso": nunca pedimos localizacao em segundo plano. */
private fun requiredPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@Composable
fun TrackingContent(
    state: TrackingState,
    sharingBlocked: Boolean,
    onIntent: (TrackingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        sharingBlocked ->
            EmptyState(
                title = stringResource(R.string.tracking_blocked_title),
                message = stringResource(R.string.tracking_blocked_message),
                modifier = modifier,
            )
        state == TrackingState.NeedsDisclosure -> DisclosureContent(onIntent, modifier)
        else -> StateContent(state, onIntent, modifier)
    }
}

@Composable
private fun StateContent(
    state: TrackingState,
    onIntent: (TrackingIntent) -> Unit,
    modifier: Modifier,
) {
    val title: Int
    val message: Int
    var actionLabel: Int? = null
    var action: TrackingIntent? = null
    when (state) {
        TrackingState.Off, TrackingState.NeedsDisclosure -> {
            title = R.string.tracking_off_title
            message = R.string.tracking_off_message
            actionLabel = R.string.tracking_off_action
            action = TrackingIntent.OptIn
        }
        TrackingState.NeedsPermission, TrackingState.Starting -> {
            title = R.string.tracking_preparing_title
            message = R.string.tracking_preparing_message
        }
        is TrackingState.Active -> {
            title = R.string.tracking_active_title
            message = statusMessage(state.lastStatus)
            actionLabel = R.string.tracking_active_stop
            action = TrackingIntent.StopRequested
        }
        is TrackingState.Stopped -> {
            title = R.string.tracking_stopped_title
            message = stopMessage(state.reason)
            actionLabel = R.string.tracking_stopped_dismiss
            action = TrackingIntent.Dismissed
        }
    }
    EmptyState(
        title = stringResource(title),
        message = stringResource(message),
        modifier = modifier,
        action =
            actionLabel?.let { label ->
                { CheckInButton(label = stringResource(label), onClick = { action?.let(onIntent) }) }
            },
    )
}

private fun statusMessage(status: TripStatus): Int =
    when (status) {
        TripStatus.Waiting -> R.string.tracking_status_waiting
        TripStatus.Sending -> R.string.tracking_status_sending
        TripStatus.Paused -> R.string.tracking_status_paused
    }

private fun stopMessage(cause: StopCause): Int =
    when (cause) {
        StopCause.UserStopped -> R.string.tracking_stopped_user
        StopCause.PermissionDenied -> R.string.tracking_stopped_permission
        StopCause.NoRoute -> R.string.tracking_stopped_no_route
        StopCause.LeftCorridor -> R.string.tracking_stopped_left_corridor
        StopCause.StoppedOutsideStation -> R.string.tracking_stopped_outside_station
        StopCause.MaxDuration -> R.string.tracking_stopped_max_duration
    }

/** Divulgacao destacada: so aparece depois do toque do usuario e nao pede nada antes do aceite. */
@Composable
private fun DisclosureContent(
    onIntent: (TrackingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MalhaVivaSpacing.l),
        verticalArrangement = Arrangement.spacedBy(MalhaVivaSpacing.m),
    ) {
        Text(stringResource(R.string.tracking_disclosure_title), style = MaterialTheme.typography.headlineSmall)
        listOf(
            R.string.tracking_disclosure_use,
            R.string.tracking_disclosure_anonymous,
            R.string.tracking_disclosure_delete,
            R.string.tracking_disclosure_trim,
            R.string.tracking_disclosure_optin,
        ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
        CheckInButton(
            label = stringResource(R.string.tracking_disclosure_accept),
            onClick = { onIntent(TrackingIntent.DisclosureAccepted) },
        )
        OutlinedButton(onClick = { onIntent(TrackingIntent.DisclosureDeclined) }) {
            Text(stringResource(R.string.tracking_disclosure_decline))
        }
    }
}
