package io.github.bornaradusin.launcher.channels

import android.content.Context
import android.content.Intent
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
 * rows, Netflix's and so on), read from Android's TV listings. Needs the "TV listings" permission:
 * Android asks once, or adb shell pm grant … com.android.providers.tv.permission.READ_EPG_DATA.
 */
class Channels(private val context: Context, private val store: Store, private val scope: CoroutineScope) {
    private val _channels = MutableStateFlow<List<AppChannel>>(emptyList())
    val channels: StateFlow<List<AppChannel>> = _channels

    private val _allowed = MutableStateFlow(allowed())
    val allowedState: StateFlow<Boolean> = _allowed

    private var job: Job? = null

    fun allowed() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        refresh()
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

    private suspend fun load() {
        _allowed.value = allowed()
        if (!_allowed.value) return
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
        const val PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"
    }
}
