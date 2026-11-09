package io.github.bornaradusin.launcher.emby

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.bornaradusin.launcher.EmbyAccount
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Films and episodes play in the Emby app: the launcher opens the item there, then asks the app (by
 * Emby's remote control, the way the phone app's "play on" does) to play it from where it was left.
 * If the app doesn't answer, the item's page is open in Emby and Play is one press away.
 */
class EmbyLauncher(private val context: Context, private val emby: Emby, private val deviceId: String) {

    /** The Emby app to use: the one chosen, else Emby for Android TV, else Emby. */
    fun app(chosen: String?): String? {
        val installed = APPS.filter { installed(it) }
        return chosen?.takeIf { it in installed } ?: installed.firstOrNull()
    }

    fun installedApps(): List<Pair<String, String>> = APPS.filter { installed(it) }.map { it to name(it) }

    /** Plays [item] in the Emby app: from where it was left, or from the start. */
    suspend fun play(a: EmbyAccount, item: EmbyItem, chosenApp: String?, fromStart: Boolean = false): Boolean {
        val pkg = app(chosenApp) ?: return false
        if (!open(a, item, pkg)) return false
        // A show opens on its page, to pick the episode
        if (item.isSeries) return true
        // Wait for the app to tell the server it's there, then hand it the item
        val ip = localIp()
        repeat(20) {
            delay(if (it == 0) 1200 else 600)
            val session = runCatching { emby.sessions(a) }.getOrNull()
                ?.filter { s -> s.deviceId != deviceId && s.supportsRemoteControl && isThisShield(s.remoteEndPoint, s.deviceName, ip) }
                ?.filter { s -> s.client.contains("Android", true) || s.client.contains("Emby", true) }
                ?.maxByOrNull { s -> s.lastActivity }
            if (session != null) {
                val start = if (fromStart) 0L else item.positionTicks
                return runCatching { emby.playOn(a, session, item, start) }.isSuccess
            }
        }
        return true
    }

    /** Opens [item]'s page in the Emby app (a show, or the episodes of one). */
    fun open(a: EmbyAccount, item: EmbyItem, pkg: String): Boolean {
        val uri = when (pkg) {
            "tv.emby.embyatv" -> "embyatv://tv.emby.embyatv/play/${item.id}"
            else -> "emby://items/${a.serverId}/${item.id}"
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            // An older app without links: just open it
            runCatching {
                val pm = context.packageManager
                val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                if (i != null) context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                i != null
            }.getOrDefault(false)
        }
    }

    private fun isThisShield(endPoint: String, deviceName: String, ip: String?): Boolean {
        val host = endPoint.substringBefore(':').removePrefix("::ffff:")
        if (ip != null && host == ip) return true
        // Emby's own address for apps on the same machine, or the Shield by its name
        return host == "127.0.0.1" || deviceName.contains("SHIELD", true)
    }

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun name(pkg: String) = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        val APPS = listOf("tv.emby.embyatv", "com.mb.android")

        /** The Shield's own address on the home network. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
