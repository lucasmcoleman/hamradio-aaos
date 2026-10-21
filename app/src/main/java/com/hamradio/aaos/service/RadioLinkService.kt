package com.hamradio.aaos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.hamradio.aaos.MainActivity

private const val TAG = "RadioLinkService"
private const val CHANNEL_ID = "radio_link"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service that keeps the app process alive while a radio is connected, so the
 * Bluetooth link and audio keep running when another app (Maps) is in front.
 *
 * It holds no radio state itself: RadioController is a process-wide singleton, and this service
 * only stops Android from treating the process as background. MainActivity starts it when the link
 * connects and stops it when the link drops.
 */
class RadioLinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // e.g. missing BLUETOOTH_CONNECT on API 34+: run without foreground status rather than crash
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Radio connection", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Radio connected")
            .setContentText("Keeping the radio link open")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RadioLinkService::class.java))
            } catch (e: Exception) {
                // Starting from the background is refused on API 31+; the link still works in the foreground.
                Log.w(TAG, "could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioLinkService::class.java))
        }
    }
}
