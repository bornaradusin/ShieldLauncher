package io.github.bornaradusin.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** An app that can be opened: its TV banner if it has one, else its icon. */
class AppEntry(
    val key: String,
    val pkg: String,
    val activity: String,
    val label: String,
    /** The 16:9 TV banner, or null (then [icon] shows with the name). */
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    /** For the phone page. */
    val png: ByteArray?,
    val tv: Boolean,
)

/**
 * The apps installed on the Shield, for the apps row and "Add an app": TV apps (and phone apps that
 * were sideloaded), each once. Kept up to date as apps are installed, updated or removed.
 */
class Apps(private val context: Context, private val scope: CoroutineScope) {
    private val pm = context.packageManager

    private val _all = MutableStateFlow<List<AppEntry>>(emptyList())
    val all: StateFlow<List<AppEntry>> = _all

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    fun start() {
        reload()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = reload()
            },
            filter,
        )
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            _all.value = load()
            _loaded.value = true
        }
    }

    fun find(key: String): AppEntry? = _all.value.firstOrNull { it.key == key }

    private fun load(): List<AppEntry> {
        val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<AppEntry>()
        for ((list, isTv) in listOf(tv to true, phone to false)) {
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName || !seen.add(ai.packageName)) continue
                runCatching { out += entry(ai, isTv) }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun entry(ai: ActivityInfo, tv: Boolean): AppEntry {
        val label = ai.loadLabel(pm).toString().trim()
        val bannerDrawable = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        val iconDrawable = ai.loadIcon(pm)
        val banner = bannerDrawable?.let { bitmap(it, 320, 180) }
        val icon = bitmap(iconDrawable, 96, 96)
        val png = ByteArrayOutputStream().use { out ->
            (banner ?: icon).compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return AppEntry(
            key = "${ai.packageName}/${ai.name}",
            pkg = ai.packageName,
            activity = ai.name,
            label = label,
            banner = banner?.asImageBitmap(),
            icon = icon.asImageBitmap(),
            png = png,
            tv = tv,
        )
    }

    private fun bitmap(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    /** Opens the app; false if it can't be opened (removed meanwhile). */
    fun open(entry: AppEntry): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (entry.tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(entry.pkg, entry.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun info(entry: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * The apps row the first time: the usual TV apps that are installed (Emby, SmartTube, Netflix…),
     * then other TV apps, up to eight.
     */
    fun firstRow(): List<String> {
        val all = _all.value
        val picked = mutableListOf<AppEntry>()
        FAVOURITES.forEach { pkg -> all.firstOrNull { it.pkg == pkg }?.let { picked += it } }
        all.filter { it.tv && it !in picked && it.pkg !in HIDDEN }.forEach { if (picked.size < 8) picked += it }
        return picked.take(8).map { it.key }
    }

    companion object {
        val FAVOURITES = listOf(
            "tv.emby.embyatv", "com.mb.android",
            "org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
            "com.google.android.youtube.tv",
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
            "com.spotify.tv.android", "com.limelight.noir", "com.limelight",
        )

        /** System bits that aren't worth a place in the first row. */
        private val HIDDEN = setOf(
            "com.android.tv.settings", "com.google.android.tvlauncher", "com.android.vending",
            "com.google.android.katniss", "com.nvidia.shieldtech.hooks", "com.google.android.youtube.tvkids",
        )
    }
}
