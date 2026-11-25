package br.malhaviva.feature.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build

internal const val ACTION_STOP = "br.malhaviva.feature.tracking.STOP"
private const val CHANNEL_ID = "tracking"
private const val NOTIFICATION_ID = 1001
private const val PERCENT = 100
private const val LOW_BATTERY_PERCENT = 15

internal fun Context.hasFineLocation(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasBackgroundLocation(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun Context.batteryLow(): Boolean {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return level >= 0 && scale > 0 && level * PERCENT / scale < LOW_BATTERY_PERCENT
}

internal fun Service.startForegroundWithNotification() {
    val notification = buildTrackingNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
}

/** Notificacao fixa com a acao "Parar" (1 toque encerra o compartilhamento). */
internal fun Context.buildTrackingNotification(): Notification {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
    val stopIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return Notification
        .Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.tracking_notification_title))
        .setContentText(getString(R.string.tracking_notification_text))
        .setOngoing(true)
        .addAction(
            Notification.Action
                .Builder(null, getString(R.string.tracking_notification_stop), stopIntent)
                .build(),
        ).build()
}
