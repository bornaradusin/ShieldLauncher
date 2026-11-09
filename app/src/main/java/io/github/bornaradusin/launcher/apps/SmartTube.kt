package io.github.bornaradusin.launcher.apps

import android.content.Context
import android.content.Intent
import android.net.Uri

/** YouTube links (trailers) open in SmartTube when it's installed, else in whatever opens them. */
class SmartTube(private val context: Context) {

    /** SmartTube's package: the stable one, else the beta. */
    fun player(): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun open(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        player()?.let { intent.setPackage(it) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    companion object {
        val PACKAGES = listOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
    }
}
