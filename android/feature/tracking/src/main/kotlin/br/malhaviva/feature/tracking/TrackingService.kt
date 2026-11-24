package br.malhaviva.feature.tracking

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import br.malhaviva.core.domain.checkin.GeoPoint
import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.sampling.SamplingPolicy
import br.malhaviva.core.domain.tracking.Route
import br.malhaviva.core.domain.tracking.TrackPolicy
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servico em primeiro plano (tipo location) que coleta posicoes durante a viagem.
 * START_NOT_STICKY: se o sistema matar o processo, nada reinicia sozinho (o usuario precisa ativar de novo).
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: FusedLocationProviderClient
    private var pipeline: TripPipeline? = null
    private var intervalMs = SamplingPolicy.SLOW_INTERVAL_MS
    private var lastStatus = TripStatus.Waiting

    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::onLocation)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when {
            intent?.action == ACTION_STOP -> finish(StopCause.UserStopped)
            pipeline == null -> begin()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (pipeline != null) client.removeLocationUpdates(callback)
        pipeline = null
        scope.cancel()
        super.onDestroy()
    }

    private fun begin() {
        startForegroundWithNotification()
        val route = TrackingEnvironment.routeProvider.current()
        when {
            route == null -> abort(StopCause.NoRoute)
            !hasFineLocation() -> abort(StopCause.PermissionDenied)
            else -> startTrip(route)
        }
    }

    private fun startTrip(route: Route) {
        val now = System.currentTimeMillis()
        pipeline = TripPipeline(route, now)
        intervalMs = SamplingPolicy.SLOW_INTERVAL_MS
        lastStatus = TripStatus.Waiting
        if (requestUpdates()) {
            TrackingBridge.publish(ServiceEvent.Started(now))
        } else {
            abort(StopCause.PermissionDenied)
        }
    }

    private fun abort(cause: StopCause) {
        pipeline = null
        TrackingBridge.publish(ServiceEvent.Stopped(cause))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onLocation(location: Location) {
        val current = pipeline
        if (current == null || location.isMockLocation()) return
        val step = current.onReading(location.toReading(), batteryLow())
        val status = if (step.paused) TripStatus.Paused else TripStatus.Sending
        if (status != lastStatus) {
            lastStatus = status
            TrackingBridge.publish(ServiceEvent.Status(status))
        }
        if (step.stop != null) {
            finish(step.stop.toCause())
        } else if (step.nextIntervalMs != intervalMs) {
            intervalMs = step.nextIntervalMs
            requestUpdates()
        }
    }

    private fun finish(cause: StopCause) {
        val current = pipeline
        if (current == null) {
            stopSelf()
            return
        }
        pipeline = null
        client.removeLocationUpdates(callback)
        TrackingBridge.publish(ServiceEvent.Stopped(cause))
        val batches = current.finish()
        val uploader = TrackingEnvironment.uploader
        // o servico fica em primeiro plano ate o envio terminar; lote que falha e descartado
        scope.launch {
            batches.forEach { runCatching { uploader.upload(it) } }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(): Boolean {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build()
        return try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Amostra de localizacao simulada nao entra: API 31+ usa isMock; antes, isFromMockProvider. */
internal fun Location.isMockLocation(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }

internal fun Location.toReading(): Reading =
    Reading(
        point = GeoPoint(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        speedKmh = if (hasSpeed()) speed * TrackPolicy.KMH_TO_MS.toFloat() else 0f,
        timeMs = time,
    )
