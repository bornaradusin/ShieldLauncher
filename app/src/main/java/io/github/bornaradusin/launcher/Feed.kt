package io.github.bornaradusin.launcher

import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyException
import io.github.bornaradusin.launcher.emby.EmbyItem
import io.github.bornaradusin.launcher.emby.EmbyPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a row has: nothing yet, its items, or why it couldn't get them. */
data class RowState<T>(val items: List<T> = emptyList(), val total: Int = 0, val loaded: Boolean = false, val error: String? = null)

/**
 * The Emby rows. Fetched when the launcher starts, when Home comes back after a while, every 15
 * minutes, and straight away when Emby is signed in or out.
 */
class Feed(
    private val store: Store,
    private val emby: Emby,
    private val scope: CoroutineScope,
) {
    val continueWatching = MutableStateFlow(RowState<EmbyItem>())
    val movies = MutableStateFlow(RowState<EmbyItem>())
    val shows = MutableStateFlow(RowState<EmbyItem>())

    private var lastRefresh = 0L
    private var job: Job? = null

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
            store.config.map { it.emby?.token }.distinctUntilChanged().drop(1).collect { refresh() }
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
            val a = store.config.value.emby
            if (a == null) {
                val none = RowState<EmbyItem>(loaded = true, error = "Sign in to Emby from your phone: Settings › Set up from your phone")
                continueWatching.value = none
                movies.value = none
                shows.value = none
                return@launch
            }
            val cw = async { load { emby.continueWatching(a) } }
            val mv = async { load { emby.movies(a) } }
            val sh = async { load { emby.shows(a) } }
            continueWatching.value = cw.await()
            movies.value = mv.await()
            shows.value = sh.await()
        }
    }

    /** Takes a deleted film or show off the rows at once (Emby drops it at its next scan). */
    fun forget(embyId: String) {
        movies.value = movies.value.let { s -> s.copy(items = s.items.filterNot { it.id == embyId }, total = (s.total - 1).coerceAtLeast(0)) }
        shows.value = shows.value.let { s -> s.copy(items = s.items.filterNot { it.id == embyId }, total = (s.total - 1).coerceAtLeast(0)) }
        continueWatching.value = continueWatching.value.let { s -> s.copy(items = s.items.filterNot { it.id == embyId || it.seriesId == embyId }) }
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
}
