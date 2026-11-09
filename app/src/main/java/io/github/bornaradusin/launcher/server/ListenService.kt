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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the launcher listening on the home network while other apps are open: for the phone's set-up
 * page, and for Home Assistant's notifications, which it shows over whatever is playing.
 */
/** Which ports the launcher is listening on: 7676 for notifications, 7677 for the phone (when it's on). */
data class Ports(val notifications: Boolean, val phone: Boolean)

class ListenService : Service() {
    private var notifications: LocalServer? = null
    private var phone: LocalServer? = null
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
        // The phone page switched on or off
        watch = LauncherApp.instance.scope.launch {
            LauncherApp.instance.store.config.map { it.phonePage }.distinctUntilChanged().collect { listen() }
        }
    }

    private var watch: Job? = null

    /**
     * Starts listening on whichever port isn't yet (a port another app holds is tried again next
     * time). The phone page listens only while it's switched on (Settings › Phone set-up page).
     */
    private fun listen() {
        if (notifications?.isAlive != true) notifications = open(LocalServer.PORT, phonePage = false)
        val wanted = LauncherApp.instance.store.config.value.phonePage
        if (!wanted) {
            phone?.stop()
            phone = null
        } else if (phone?.isAlive != true) {
            phone = open(LocalServer.SETUP_PORT, phonePage = true)
        }
        ports.value = Ports(notifications?.isAlive == true, phone?.isAlive == true)
    }

    private fun open(port: Int, phonePage: Boolean): LocalServer? {
        val s = LocalServer(LauncherApp.instance, overlay, port, phonePage)
        return if (runCatching { s.start(SOCKET_TIMEOUT, false) }.isSuccess) s else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listen()
        return START_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        notifications?.stop()
        phone?.stop()
        notifications = null
        phone = null
        ports.value = Ports(false, false)
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "listen"
        private const val SOCKET_TIMEOUT = 15_000

        val ports = MutableStateFlow(Ports(false, false))

        /** The running service, for a test notification from Settings. */
        @Volatile var running: ListenService? = null
            private set

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java)) }
        }
    }
}
