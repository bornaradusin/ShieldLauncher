package io.github.bornaradusin.launcher.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.R

/**
 * Keeps the launcher listening on the home network while other apps are open: for the phone's set-up
 * page, and for Home Assistant's notifications, which it shows over whatever is playing.
 */
class ListenService : Service() {
    private var server: LocalServer? = null
    lateinit var overlay: Overlay
        private set

    override fun onCreate() {
        super.onCreate()
        overlay = Overlay(this)
        running = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for the phone and Home Assistant", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Listening for your phone and Home Assistant")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        listen()
    }

    private fun listen() {
        if (server?.isAlive == true) return
        server = LocalServer(LauncherApp.instance, overlay).also { s ->
            runCatching { s.start(SOCKET_TIMEOUT, false) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listen()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "listen"
        private const val SOCKET_TIMEOUT = 15_000

        /** The running service, for a test notification from Settings. */
        @Volatile var running: ListenService? = null
            private set

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java)) }
        }
    }
}
