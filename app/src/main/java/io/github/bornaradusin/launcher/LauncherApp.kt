package io.github.bornaradusin.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.bornaradusin.launcher.apps.Apps
import io.github.bornaradusin.launcher.apps.SmartTube
import io.github.bornaradusin.launcher.channels.Channels
import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyLauncher
import io.github.bornaradusin.launcher.games.Games
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.seerr.RequesterFeed
import io.github.bornaradusin.launcher.seerr.Seerr
import io.github.bornaradusin.launcher.server.PhoneLink
import io.github.bornaradusin.launcher.trailer.Trailers
import io.github.bornaradusin.launcher.update.Updater
import io.github.bornaradusin.launcher.weather.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The launcher's parts, made once and shared by the screens, the phone page and the listener. */
class LauncherApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    lateinit var store: Store
        private set
    lateinit var apps: Apps
        private set
    lateinit var updater: Updater
        private set
    lateinit var emby: Emby
        private set
    lateinit var embyLauncher: EmbyLauncher
        private set
    lateinit var smartTube: SmartTube
        private set
    lateinit var channels: Channels
        private set
    lateinit var seerr: Seerr
        private set
    lateinit var requester: RequesterFeed
        private set
    lateinit var feed: Feed
        private set
    lateinit var trailers: Trailers
        private set
    lateinit var games: Games
        private set
    lateinit var weather: Weather
        private set
    lateinit var phoneLink: PhoneLink
        private set
    lateinit var facts: io.github.bornaradusin.launcher.facts.Facts
        private set
    lateinit var discord: io.github.bornaradusin.launcher.discord.Discord
        private set
    lateinit var discordChats: io.github.bornaradusin.launcher.discord.DiscordChats
        private set

    val version: String
        get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        apps = Apps(this, scope)
        updater = Updater(this, scope)
        emby = Emby(http, store.deviceId, version)
        embyLauncher = EmbyLauncher(this, emby, store.deviceId)
        smartTube = SmartTube(this)
        channels = Channels(this, store, scope)
        feed = Feed(store, emby, scope)
        seerr = Seerr(http)
        requester = RequesterFeed(store, seerr, feed, emby, scope)
        trailers = Trailers(http, emby)
        games = Games(this, store)
        weather = Weather(http, scope, placeSetting = { store.config.value.weather }, onNewWarning = ::warn)
        phoneLink = PhoneLink(this)
        facts = io.github.bornaradusin.launcher.facts.Facts(this, http, scope, version)
        discord = io.github.bornaradusin.launcher.discord.Discord(http, version)
        discordChats = io.github.bornaradusin.launcher.discord.DiscordChats(this, store, discord, http, scope)

        apps.start()
        updater.start()
        feed.start()
        channels.start()
        requester.start()
        weather.start()
        discordChats.start()
        facts.start()
        ListenService.start(this)
    }

    /**
     * A weather warning that's new: shown over whatever is on, once (warnings already shown aren't
     * shown again after a restart).
     */
    private fun warn(w: io.github.bornaradusin.launcher.weather.Warning) {
        if (!store.config.value.warnings) return
        val prefs = getSharedPreferences("warnings", MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (w.id in seen) return
        prefs.edit().putStringSet("seen", (seen + w.id).toList().takeLast(200).toSet()).apply()
        val overlay = ListenService.running?.overlay ?: return
        val colour = when (w.level) {
            3 -> android.graphics.Color.rgb(150, 24, 24)
            2 -> android.graphics.Color.rgb(170, 84, 0)
            else -> android.graphics.Color.rgb(120, 98, 0)
        }
        val level = when (w.level) { 3 -> "Red"; 2 -> "Orange"; else -> "Yellow" }
        val until = w.expires?.let { " until " + java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: ""
        overlay.show(
            io.github.bornaradusin.launcher.server.Notice(
                title = "$level warning: ${w.event}",
                message = "${w.area}$until",
                seconds = 15, position = 2, fontSize = 0, color = colour, transparency = 0f,
                interrupt = false, icon = null, image = null,
            ),
        )
    }

    /** Pictures (posters, backdrops, thumbnails) kept in memory and on disk, so rows come back at once. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("pictures")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: LauncherApp
            private set
    }
}
