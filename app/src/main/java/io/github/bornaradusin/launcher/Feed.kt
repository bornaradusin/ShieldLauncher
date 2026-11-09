package io.github.bornaradusin.launcher

import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyException
import io.github.bornaradusin.launcher.emby.EmbyItem
import io.github.bornaradusin.launcher.emby.EmbyPage
import io.github.bornaradusin.launcher.youtube.YouTube
import io.github.bornaradusin.launcher.youtube.YtCode
import io.github.bornaradusin.launcher.youtube.YtPoll
import io.github.bornaradusin.launcher.youtube.YtVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a row has: nothing yet, its items, or why it couldn't get them. */
data class RowState<T>(val items: List<T> = emptyList(), val total: Int = 0, val loaded: Boolean = false, val error: String? = null)

/** Signing in to YouTube with a code: the code to type, and how it's going. */
data class YtSignIn(val code: YtCode? = null, val message: String? = null, val busy: Boolean = false)

/**
 * The rows' content from Emby and YouTube. Fetched when the launcher starts, when Home comes back
 * after a while, every 15 minutes, and straight away when an account is signed in or out.
 */
class Feed(
    private val store: Store,
    private val emby: Emby,
    private val youtube: YouTube,
    private val scope: CoroutineScope,
) {
    val continueWatching = MutableStateFlow(RowState<EmbyItem>())
    val movies = MutableStateFlow(RowState<EmbyItem>())
    val shows = MutableStateFlow(RowState<EmbyItem>())
    val recommended = MutableStateFlow(RowState<YtVideo>())
    val subscriptions = MutableStateFlow(RowState<YtVideo>())

    private val _ytSignIn = MutableStateFlow(YtSignIn())
    val ytSignIn: StateFlow<YtSignIn> = _ytSignIn

    private var lastRefresh = 0L
    private var job: Job? = null
    private var signInJob: Job? = null

    fun start() {
        refresh()
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                refresh()
            }
        }
        // A sign-in or sign-out: the rows change straight away
        scope.launch {
            store.config.map { it.emby?.token to it.youtube?.refreshToken }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    /** Home is back in front: fresh rows if the last ones are more than a minute old. */
    fun resumed() {
        if (System.currentTimeMillis() - lastRefresh > 60_000) refresh()
    }

    fun refresh() {
        lastRefresh = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val c = store.config.value
            val a = c.emby
            if (a == null) {
                val none = RowState<EmbyItem>(loaded = true, error = "Sign in to Emby from your phone: Settings › Set up from your phone")
                continueWatching.value = none
                movies.value = none
                shows.value = none
            } else {
                val cw = async { load { emby.continueWatching(a) } }
                val mv = async { load { emby.movies(a) } }
                val sh = async { load { emby.shows(a) } }
                continueWatching.value = cw.await()
                movies.value = mv.await()
                shows.value = sh.await()
            }
            loadYouTube(c.youtube)
        }
    }

    private suspend fun load(block: suspend () -> EmbyPage): RowState<EmbyItem> = try {
        val page = block()
        RowState(page.items, page.total, loaded = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EmbyException) {
        RowState(loaded = true, error = e.message)
    } catch (e: Exception) {
        RowState(loaded = true, error = "Couldn't reach Emby")
    }

    private suspend fun loadYouTube(account: YouTubeAccount?) {
        if (account == null) {
            val none = RowState<YtVideo>(loaded = true, error = "Sign in to YouTube: Settings › YouTube")
            recommended.value = none
            subscriptions.value = none
            return
        }
        val fresh = try {
            youtube.fresh(account).also { f -> if (f != account) store.change { it.copy(youtube = f) } }
        } catch (e: YouTube.YtSignedOut) {
            store.change { it.copy(youtube = null) }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val off = RowState<YtVideo>(loaded = true, error = "Couldn't reach YouTube")
            recommended.value = off
            subscriptions.value = off
            return
        }
        val rows = store.config.value.rows.filter { it.on }.map { it.kind }
        if (RowKind.RECOMMENDED in rows) recommended.value = loadVideos { youtube.recommended(fresh) }
        if (RowKind.SUBSCRIPTIONS in rows) subscriptions.value = loadVideos { youtube.subscriptions(fresh) }
    }

    private suspend fun loadVideos(block: suspend () -> List<YtVideo>): RowState<YtVideo> = try {
        val list = block()
        RowState(list, list.size, loaded = true, error = if (list.isEmpty()) "Nothing from YouTube just now" else null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RowState(loaded = true, error = "Couldn't reach YouTube")
    }

    /** Gets a code to type at google.com/device, then waits until it's typed (or runs out). */
    fun startYouTubeSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            _ytSignIn.value = YtSignIn(busy = true)
            val code = try {
                youtube.startSignIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ytSignIn.value = YtSignIn(message = "Couldn't reach YouTube. Check the connection and try again.")
                return@launch
            }
            _ytSignIn.value = YtSignIn(code = code)
            var wait = code.interval * 1000L
            while (System.currentTimeMillis() < code.expiresAt) {
                delay(wait)
                when (val r = youtube.poll(code)) {
                    YtPoll.Waiting -> Unit
                    YtPoll.SlowDown -> wait += 2000
                    is YtPoll.Done -> {
                        store.change { it.copy(youtube = r.account) }
                        _ytSignIn.value = YtSignIn(message = "Signed in to YouTube")
                        return@launch
                    }
                    is YtPoll.Failed -> {
                        _ytSignIn.value = YtSignIn(message = r.message)
                        return@launch
                    }
                }
            }
            _ytSignIn.value = YtSignIn(message = "The code ran out. Start again for a new one.")
        }
    }

    fun cancelYouTubeSignIn() {
        signInJob?.cancel()
        _ytSignIn.value = YtSignIn()
    }

    fun signOutYouTube() {
        cancelYouTubeSignIn()
        store.change { it.copy(youtube = null) }
    }
}
