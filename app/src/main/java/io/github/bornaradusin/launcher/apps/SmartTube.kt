package io.github.bornaradusin.launcher.apps

import android.content.Context
import android.content.Intent
import android.net.Uri

/** YouTube links (trailers) open in SmartTube when it's installed, else in whatever opens them. */
class SmartTube(private val context: Context) {

    /** SmartTube's package: the stable one, else the beta (today's, else the ones from before its new key). */
    fun player(): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun open(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        player()?.let { intent.setPackage(it) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    companion object {
        /**
         * SmartTube's packages: today's (since its new signing key, November 2025), then the old ones,
         * which still run where they weren't removed.
         */
        val PACKAGES = listOf(
            "org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid",
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
        )

        /**
         * What makes SmartTube's rows. It doesn't take the home screen's "make your rows": it makes them
         * when it opens, by sending this itself, and it takes the same from outside.
         */
        const val CHANNELS_UPDATER = "com.liskovsoft.leanbackassistant.channels.UpdateChannelsReceiver"
    }
}
