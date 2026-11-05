package com.ephemeralchat.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ephemeralchat.app.EphemeralChatApp
import com.ephemeralchat.app.ui.MainActivity

/**
 * Keeps MeshTransport's advertising/discovery/active connections alive while
 * the app is backgrounded, so beta testers don't drop messages just because
 * they switched apps. Started as soon as the user opens the chat list.
 */
class MeshConnectionService : Service() {

    private var started=false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(started) return START_NOT_STICKY
        started=true
        // The actual MeshTransport instance lives in the Application singleton
        // (EphemeralChatApp) so both the UI and this service share one connection.
        val app = application as EphemeralChatApp
        app.meshTransport.startAdvertising()
        app.meshTransport.startDiscovery()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val app = application as EphemeralChatApp
        app.meshTransport.stopAll()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "mesh_connection_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "EphemeralChat mesh connection", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("EphemeralChat active")
            .setContentText("Listening for nearby devices")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun serviceType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0
    }
}
