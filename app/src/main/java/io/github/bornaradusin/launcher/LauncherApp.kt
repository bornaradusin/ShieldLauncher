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
import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyLauncher
import io.github.bornaradusin.launcher.games.Games
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.server.PhoneLink
import io.github.bornaradusin.launcher.trailer.Trailers
import io.github.bornaradusin.launcher.update.Updater
import io.github.bornaradusin.launcher.weather.Weather
import io.github.bornaradusin.launcher.youtube.YouTube
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
    lateinit var youtube: YouTube
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
        youtube = YouTube(this, http)
        feed = Feed(store, emby, youtube, scope)
        trailers = Trailers(http, emby)
        games = Games(this, store)
        weather = Weather(http, scope)
        phoneLink = PhoneLink(this)

        apps.start()
        updater.start()
        feed.start()
        weather.start()
        ListenService.start(this)
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
