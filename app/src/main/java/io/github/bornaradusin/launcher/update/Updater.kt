package io.github.bornaradusin.launcher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A newer Shield Launcher, published on GitHub. */
data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sumsUrl: String?,
)

/** Where an update is at. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    /** Downloaded, but Android needs the launcher allowed to install apps first. */
    data class NeedsPermission(val update: AppUpdate, val apk: File) : UpdateState
    data class Failed(val message: String, val update: AppUpdate? = null) : UpdateState
}

/**
 * Updates from GitHub: when the Shield starts and then every few hours the launcher looks for a newer
 * published release of bornaradusin/ShieldLauncher (drafts and pre-releases don't count). Update
 * downloads its APK into the app's cache, checks it against the release's SHA256SUMS.txt and opens
 * Android's installer. The APK is deleted once the update is in.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** The update card is up: an update found by itself (not one put off), or one asked about. */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt

    private val _auto = MutableStateFlow(prefs.getBoolean("auto", true))
    /** Look on GitHub by itself, every few hours. */
    val auto: StateFlow<Boolean> = _auto

    private var job: Job? = null

    val installedVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private val _updated = MutableStateFlow<String?>(null)
    /** Just updated: the version now running, for "Updated to …" on Home. The download is gone by then. */
    val updated: StateFlow<String?> = _updated

    fun dismissUpdated() {
        _updated.value = null
    }

    /** While the launcher runs (it's the home screen, so that's all day): every few hours. */
    fun start() {
        val ran = prefs.getString("ran", null)
        if (ran != null && isNewer(installedVersion, ran)) _updated.value = installedVersion
        prefs.edit().putString("ran", installedVersion).apply()
        scope.launch {
            cleanLeftovers(context)
            delay(20_000)
            while (true) {
                if (_auto.value && System.currentTimeMillis() - prefs.getLong("last", 0) > EVERY_MS) check(manual = false)
                delay(3600_000)
            }
        }
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _auto.value = on
    }

    /** Looks on GitHub. [manual]: asked for in Settings, so the card shows what was found, even nothing. */
    fun check(manual: Boolean) {
        if (manual) _prompt.value = true
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val found = latest()
                prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                if (found == null) {
                    UpdateState.UpToDate
                } else {
                    if (!manual && found.version != prefs.getString("later", "")) _prompt.value = true
                    UpdateState.Available(found)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Couldn't reach GitHub. Check the connection and try again.") else UpdateState.Idle
            }
        }
    }

    private fun pending(): AppUpdate? = when (val s = _state.value) {
        is UpdateState.Available -> s.update
        is UpdateState.Downloading -> s.update
        is UpdateState.NeedsPermission -> s.update
        is UpdateState.Failed -> s.update
        else -> null
    }

    /** Downloads the update, checks it and opens Android's installer. */
    fun update() {
        val update = pending() ?: return
        if (_state.value is UpdateState.Downloading) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            try {
                val apk = download(update) { p -> _state.value = UpdateState.Downloading(update, p) }
                install(update, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(
                    if (e.message?.contains("checksum") == true) "The download was damaged. Try again."
                    // Why, so a failure that keeps happening can be put right
                    else "The download didn't finish (${e.message ?: e.javaClass.simpleName}). Try again.",
                    update,
                )
            }
        }
    }

    private fun install(update: AppUpdate, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update, apk)
            return
        }
        // Still offered, in case the installer is closed without updating
        _state.value = UpdateState.Available(update)
        _prompt.value = false
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Android's installer didn't open. Try again.", update)
            _prompt.value = true
        }
    }

    /** The switch that lets the launcher install its update. */
    fun allowInstalls() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Back from that switch: on with the install if it's now allowed. */
    fun resume() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) install(s.update, s.apk)
    }

    fun cancel() {
        val update = pending()
        job?.cancel()
        _state.value = update?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    /** "Later": the note on Home doesn't pop up again for this version; Update stays in the top bar. */
    fun later() {
        pending()?.let { prefs.edit().putString("later", it.version).apply() }
        if (_state.value is UpdateState.Downloading) cancel()
        _prompt.value = false
    }

    fun open() {
        _prompt.value = true
    }

    fun close() {
        if (_state.value is UpdateState.Downloading) return
        _prompt.value = false
        if (_state.value is UpdateState.UpToDate || _state.value is UpdateState.Failed && pending() == null) _state.value = UpdateState.Idle
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "ShieldLauncher/$installedVersion (+https://github.com/bornaradusin/ShieldLauncher)")

    /** The newest release if it's newer than this app, or null. Throws if GitHub can't be reached. */
    private suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        // Connections kept from before the Shield slept may be dead
        client.connectionPool.evictAll()
        val release = client.newCall(request(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("GitHub answered ${res.code}")
            JSONObject(res.body?.string().orEmpty())
        }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
        val version = release.getString("tag_name").trim().removePrefix("v")
        if (!isNewer(version, installedVersion)) return@withContext null
        val assets = release.optJSONArray("assets")
        val list = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        val apk = list.firstOrNull { it.getString("name").let { n -> n.endsWith(".apk") && !n.contains("debug") } } ?: return@withContext null
        AppUpdate(
            version = version,
            notes = readable(release.optString("body", "")),
            apkUrl = apk.getString("browser_download_url"),
            apkName = apk.getString("name"),
            apkSize = apk.optLong("size", 0),
            sumsUrl = list.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }?.getString("browser_download_url"),
        )
    }

    /**
     * Downloads the APK into the cache and checks it against SHA256SUMS.txt. Connections kept from
     * before the Shield slept are dropped first, and a download that stops is picked up where it got
     * to (up to [TRIES] times) rather than failing.
     */
    private suspend fun download(update: AppUpdate, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        client.connectionPool.evictAll()
        val expected = update.sumsUrl?.let { url ->
            retrying {
                client.newCall(request(url).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("GitHub answered ${res.code}")
                    res.body?.string().orEmpty().lineSequence()
                        .map { it.trim().split(Regex("\\s+")) }
                        .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == update.apkName }
                        ?.first()?.lowercase()
                }
            }
        }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, update.apkName)
        val partial = File(dir, "${update.apkName}.part")
        retrying {
            val have = partial.length()
            val req = request(update.apkUrl).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("GitHub answered ${res.code}")
                val body = res.body ?: throw IOException("nothing came back")
                // 206: the rest of it, after what's already here; 200: the whole file again
                val resumed = res.code == 206
                var done = if (resumed) have else 0L
                val total = if (resumed) have + body.contentLength() else body.contentLength().takeIf { it > 0 } ?: update.apkSize
                body.byteStream().use { input ->
                    java.io.FileOutputStream(partial, resumed).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (update.apkSize > 0 && partial.length() < update.apkSize) throw IOException("stopped at ${partial.length() * 100 / update.apkSize}%")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            partial.delete()
            throw IOException("The download doesn't match the release's checksum")
        }
        if (!partial.renameTo(file)) throw IOException("couldn't keep the download")
        file
    }

    /** [block], tried again a couple of seconds later on fresh connections if the network lets it down. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: IOException? = null
        repeat(TRIES) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                if (e.message?.contains("checksum") == true) throw e
                last = e
                client.connectionPool.evictAll()
                if (attempt < TRIES - 1) delay(2_000L * (attempt + 1))
            }
        }
        throw last ?: IOException("the download failed")
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/bornaradusin/ShieldLauncher/releases/latest"
        private const val DIR = "updates"
        private const val EVERY_MS = 6 * 3600 * 1000L
        private const val TRIES = 4

        /** Deletes downloaded APKs, once an update is installed. */
        fun clean(context: Context) {
            File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        }

        /**
         * When the app starts: deletes APKs of this version or older (already installed) and any
         * left from an install given up a day ago. Not all of them, as Android may start the app
         * just so its installer can read the one it's installing.
         */
        fun cleanLeftovers(context: Context) {
            val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
            val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
            File(context.cacheDir, DIR).listFiles()?.forEach { f ->
                val version = Regex("(\\d+(\\.\\d+)+)").find(f.name)?.value
                if (version == null || !isNewer(version, installed) || f.lastModified() < dayAgo || f.name.endsWith(".part")) f.delete()
            }
        }

        /** Whether version [a] is newer than [b] ("1.1.10" is newer than "1.1.9"). */
        fun isNewer(a: String, b: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '+').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        /** Release notes as plain text: no Markdown headings or code fences, bullets as dots. */
        fun readable(markdown: String): String = markdown
            .replace("\r", "")
            .lines()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n") { line ->
                val t = line.trimEnd()
                val body = t.trimStart()
                when {
                    body.startsWith("#") -> body.trimStart('#').trim()
                    body.startsWith("- ") || body.startsWith("* ") -> " ".repeat(t.length - body.length) + "• " + body.drop(2)
                    else -> t
                }
            }
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

/** Just updated: the downloaded APK isn't needed any more. */
class UpdateCleaner : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.clean(context)
    }
}
