package io.github.bornaradusin.launcher.server

import android.content.Context
import android.net.ConnectivityManager
import io.github.bornaradusin.launcher.emby.EmbyLauncher
import java.net.Inet4Address

/**
 * The Shield's address on the home network: the one of the connection Android is using (Ethernet or
 * Wi-Fi), so the phone is sent to the right place.
 */
fun homeIp(context: Context): String? = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull() ?: EmbyLauncher.localIp()
