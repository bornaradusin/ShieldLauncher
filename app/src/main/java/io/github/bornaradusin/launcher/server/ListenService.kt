package io.github.bornaradusin.launcher.server

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Listens for the phone and for Home Assistant while other apps are open. */
class ListenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
