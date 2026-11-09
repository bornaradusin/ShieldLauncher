package io.github.bornaradusin.launcher.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the Shield starts: listening for the phone and Home Assistant straight away. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
