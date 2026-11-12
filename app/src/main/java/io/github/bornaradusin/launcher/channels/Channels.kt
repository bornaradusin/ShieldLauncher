package io.github.bornaradusin.launcher.channels

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import io.github.bornaradusin.launcher.Store
import io.github.bornaradusin.launcher.apps.SmartTube
import io.github.bornaradusin.launcher.channelRowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something an app put on one of its channels: a video, an episode, a film. */
data class ChannelProgram(
    val id: Long,
    val title: String,
    val description: String,
    val poster: String?,
    val thumbnail: String?,
    /** A tall poster (2:3) rather than a wide picture. */
    val tall: Boolean,
    val intentUri: String?,
    val durationMs: Long,
    val positionMs: Long,
)

/** An app that makes rows for the TV's home screen, and what it has made so far. */
data class ChannelApp(
    val pkg: String,
    val name: String,
    /** It makes its rows when the home screen asks (the stock one asks once it's installed). */
    val asks: Boolean,
    val channels: List<AppChannel>,
)

/** One of an app's channels (SmartTube's Subscriptions, say), with what's on it. */
data class AppChannel(
    val rowId: String,
    val pkg: String,
    val name: String,
    val appName: String,
    val programs: List<ChannelProgram>,
)

/**
 * The channels apps share with the TV's home screen (what the stock launcher shows as SmartTube's
 * rows, Netflix's and so on), read from Android's TV listings. Needs the "read TV listings" permission
 * (android.permission.READ_TV_LISTINGS): without it Android only shows an app its own channels.
 * Android asks once, or adb shell pm grant … android.permission.READ_TV_LISTINGS.
 */
class Channels(private val context: Context, private val store: Store, private val scope: CoroutineScope) {
    private val _channels = MutableStateFlow<List<AppChannel>>(emptyList())
    val channels: StateFlow<List<AppChannel>> = _channels

    private var job: Job? = null
    private val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

    /** When the apps were last asked to make their rows (0: never). */
    private val _askedAt = MutableStateFlow(prefs.getLong("askedAt", 0L))
    val askedAt: StateFlow<Long> = _askedAt

    fun allowed() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        // Apps make their rows when the home screen asks: the stock one did, once each was installed
        askNew()
        refresh()
        val installs = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                scope.launch {
                    delay(3000)
                    ask(listOf(pkg), force = true)
                    delay(20_000)
                    load()
                }
            }
        }
        runCatching {
            context.registerReceiver(installs, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            })
        }
        // Apps change their channels as they go (new videos): fetched again a moment after
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshSoon()
        }
        runCatching {
            context.contentResolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer)
        }
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                refresh()
            }
        }
    }

    private fun refreshSoon() {
        job?.cancel()
        job = scope.launch {
            delay(1500)
            load()
        }
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch { load() }
    }

    /**
     * The apps that make rows when asked: those with the home screen's "make your rows" receiver
     * (YouTube, Netflix, Plex…), and SmartTube, which has its own (see [SmartTube.CHANNELS_UPDATER]).
     */
    private fun receivers(): List<ComponentName> {
        val pm = context.packageManager
        val home = runCatching {
            pm.queryBroadcastReceivers(Intent(TvContract.ACTION_INITIALIZE_PROGRAMS), 0)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName != context.packageName }
                .map { ComponentName(it.packageName, it.name) }
        }.getOrDefault(emptyList())
        val smartTube = SmartTube.PACKAGES.map { ComponentName(it, SmartTube.CHANNELS_UPDATER) }
            .filter { runCatching { pm.getReceiverInfo(it, 0); true }.getOrDefault(false) }
        return (home + smartTube).distinct()
    }

    /** Asks the apps that haven't been asked since they were installed or updated. */
    private fun askNew() = scope.launch {
        val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
        val todo = receivers().map { it.packageName }.distinct().filter { "$it@${versionOf(it)}" !in asked }
        if (todo.isNotEmpty()) ask(todo, force = true)
    }

    /**
     * Sends the apps the home screen's "make your rows" (what the stock home screen sends once an app
     * is installed): each app adds its channels to the TV listings, and they show up as rows here.
     * [packages] null: every app that can.
     */
    fun ask(packages: List<String>? = null, force: Boolean = false) {
        if (!force && packages == null && System.currentTimeMillis() - _askedAt.value < 10_000) return
        val targets = receivers().filter { packages == null || it.packageName in packages }
        targets.forEach { target ->
            // SmartTube's own one is sent as SmartTube sends it when it opens: to it, with nothing else
            val intent = if (target.className == SmartTube.CHANNELS_UPDATER) Intent() else Intent(TvContract.ACTION_INITIALIZE_PROGRAMS)
            runCatching {
                context.sendBroadcast(
                    intent.setComponent(target).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND),
                )
            }
        }
        if (targets.isEmpty()) return
        val asked = prefs.getStringSet("asked", emptySet()).orEmpty().filter { a -> targets.none { a.startsWith(it.packageName + "@") } }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putStringSet("asked", (asked + targets.map { "${it.packageName}@${versionOf(it.packageName)}" }).toSet())
            .putLong("askedAt", now)
            .apply()
        _askedAt.value = now
        // The apps take a moment to make them
        scope.launch {
            delay(8000)
            load()
        }
    }

    /** Every app that makes rows, or has some, with what it has: for Settings › Home › App channels. */
    fun apps(): List<ChannelApp> {
        val makers = receivers().map { it.packageName }.distinct()
        val have = _channels.value.groupBy { it.pkg }
        return (makers + have.keys).distinct()
            .map { pkg -> ChannelApp(pkg, have[pkg]?.firstOrNull()?.appName ?: appName(pkg), pkg in makers, have[pkg].orEmpty()) }
            .sortedWith(compareByDescending<ChannelApp> { it.channels.isNotEmpty() }.thenBy { it.name.lowercase() })
    }

    private fun versionOf(pkg: String): Long = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private suspend fun load() {
        if (!allowed()) return
        val list = withContext(Dispatchers.IO) { runCatching { read() }.getOrDefault(emptyList()) }
        _channels.value = list
        // New channels become rows: SmartTube's on, other apps' off until turned on
        store.addChannelRows(list.map { it.rowId }) { id -> SmartTube.PACKAGES.any { id.startsWith("ch:$it|") } }
    }

    private fun read(): List<AppChannel> {
        val out = mutableListOf<AppChannel>()
        val cr = context.contentResolver
        cr.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "package_name", "display_name", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.str("type") != TvContract.Channels.TYPE_PREVIEW) continue
                val id = c.getLong(c.getColumnIndexOrThrow("_id"))
                val pkg = c.str("package_name") ?: continue
                val name = c.str("display_name")?.takeIf { it.isNotBlank() } ?: continue
                if (pkg == context.packageName) continue
                val programs = programs(id)
                out += AppChannel(channelRowId(pkg, name), pkg, name, appName(pkg), programs)
            }
        }
        return out.distinctBy { it.rowId }
    }

    private fun programs(channelId: Long): List<ChannelProgram> {
        val out = mutableListOf<ChannelProgram>()
        val cols = arrayOf(
            "_id", "title", "short_description", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio",
            "intent_uri", "duration_millis", "last_playback_position_millis", "browsable", "weight",
        )
        context.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId), cols, null, null, null)?.use { c ->
            val rows = mutableListOf<Pair<Int, ChannelProgram>>()
            while (c.moveToNext()) {
                if (c.int("browsable", 1) == 0) continue
                val title = c.str("title")?.takeIf { it.isNotBlank() } ?: continue
                val ratio = c.int("poster_art_aspect_ratio", 0)
                rows += c.int("weight", 0) to ChannelProgram(
                    id = c.getLong(c.getColumnIndexOrThrow("_id")),
                    title = title,
                    description = c.str("short_description").orEmpty(),
                    poster = c.str("poster_art_uri"),
                    thumbnail = c.str("thumbnail_uri"),
                    // TvContract's 2:3 and "movie poster" shapes
                    tall = ratio == 4 || ratio == 5,
                    intentUri = c.str("intent_uri"),
                    durationMs = c.long("duration_millis"),
                    positionMs = c.long("last_playback_position_millis"),
                )
            }
            // The order the app gave (its weights), else as they came
            out += rows.sortedByDescending { it.first }.map { it.second }
        }
        return out.take(40)
    }

    /** Opens what OK was pressed on, in its app. */
    fun open(program: ChannelProgram, pkg: String): Boolean {
        val intent = program.intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun appName(pkg: String) = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) 0L else getLong(it) } ?: 0L
    private fun Cursor.int(col: String, default: Int): Int = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) default else getInt(it) } ?: default

    companion object {
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    }
}
