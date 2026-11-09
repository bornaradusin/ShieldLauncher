package io.github.bornaradusin.launcher.seerr

import io.github.bornaradusin.launcher.Feed
import io.github.bornaradusin.launcher.RowState
import io.github.bornaradusin.launcher.Store
import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Requester's rows that come from Seerr. */
enum class SeerrRow(val title: String, val hint: String) {
    TRENDING("Trending", "From Seerr"),
    REQUESTS("Your requests", "From Seerr"),
    FILMS("Your films", "On Emby · newest first"),
    SHOWS("Your shows", "On Emby · newest first"),
    POPULAR_FILMS("Popular films", "From Seerr"),
    POPULAR_SHOWS("Popular shows", "From Seerr"),
    UPCOMING_FILMS("Coming soon: films", "From Seerr"),
    UPCOMING_SHOWS("Coming soon: shows", "From Seerr"),
}

/**
 * Requester's rows: Seerr's trending, popular and coming-soon lists and your requests, and your films
 * and shows (the Emby library, so you can tidy it up). Fetched when Requester opens after a while,
 * and again after a request or a delete.
 */
class RequesterFeed(
    private val store: Store,
    private val seerr: Seerr,
    private val feed: Feed,
    private val emby: Emby,
    private val scope: CoroutineScope,
) {
    val rows: Map<SeerrRow, MutableStateFlow<RowState<SeerrTitle>>> = SeerrRow.entries.associateWith { MutableStateFlow(RowState()) }

    /** Deleted just now: kept off the rows until Emby and Seerr have caught up. */
    val gone = MutableStateFlow<Set<String>>(emptySet())

    private var lastRefresh = 0L
    private var job: Job? = null

    fun start() {
        scope.launch {
            store.config.map { it.seerr?.apiKey to it.seerr?.server }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        // Your films and shows follow Emby's rows
        scope.launch { feed.movies.collect { s -> rows.getValue(SeerrRow.FILMS).value = library(s) } }
        scope.launch { feed.shows.collect { s -> rows.getValue(SeerrRow.SHOWS).value = library(s) } }
    }

    /** Requester is showing: fresh rows if the last ones are more than a minute old. */
    fun opened() {
        if (System.currentTimeMillis() - lastRefresh > 60_000) refresh()
    }

    fun refresh() {
        lastRefresh = System.currentTimeMillis()
        job?.cancel()
        val a = store.config.value.seerr
        if (a == null) {
            val none = RowState<SeerrTitle>(loaded = true, error = "Connect Seerr from your phone: Settings › Set up from your phone")
            listOf(SeerrRow.TRENDING, SeerrRow.REQUESTS, SeerrRow.POPULAR_FILMS, SeerrRow.POPULAR_SHOWS, SeerrRow.UPCOMING_FILMS, SeerrRow.UPCOMING_SHOWS)
                .forEach { rows.getValue(it).value = none }
            return
        }
        job = scope.launch {
            val lists = mapOf(
                SeerrRow.TRENDING to async { load { seerr.trending(a) } },
                SeerrRow.REQUESTS to async { load(empty = "Nothing asked for yet: OK on a film or show to request it") { seerr.requests(a) } },
                SeerrRow.POPULAR_FILMS to async { load { seerr.popularFilms(a) } },
                SeerrRow.POPULAR_SHOWS to async { load { seerr.popularShows(a) } },
                SeerrRow.UPCOMING_FILMS to async { load { seerr.upcomingFilms(a) } },
                SeerrRow.UPCOMING_SHOWS to async { load { seerr.upcomingShows(a) } },
            )
            lists.forEach { (row, job) -> rows.getValue(row).value = job.await() }
        }
    }

    /** After asking for something: its badge changes, and Your requests has it. */
    fun requested() {
        lastRefresh = 0
        refresh()
    }

    /** After a delete: off the rows straight away, and Emby looks over its library. */
    fun deleted(t: SeerrTitle) {
        gone.value = gone.value + t.key
        t.embyId?.let { feed.forget(it) }
        store.config.value.emby?.let { a -> scope.launch { emby.scanLibrary(a) } }
        requested()
    }

    private suspend fun load(empty: String = "Nothing here just now", block: suspend () -> List<SeerrTitle>): RowState<SeerrTitle> = try {
        val list = block()
        RowState(list, list.size, loaded = true, error = if (list.isEmpty()) empty else null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SeerrException) {
        RowState(loaded = true, error = e.message)
    } catch (e: Exception) {
        RowState(loaded = true, error = "Couldn't reach Seerr")
    }

    private fun library(s: RowState<EmbyItem>): RowState<SeerrTitle> {
        val items = s.items.map { fromEmby(it) }
        return RowState(items, s.total, s.loaded, s.error ?: if (s.loaded && items.isEmpty()) "Nothing on Emby yet" else null)
    }

    companion object {
        /** A film or show on Emby, as Requester shows it (Seerr finds it by its TMDB id). */
        fun fromEmby(i: EmbyItem) = SeerrTitle(
            tmdbId = i.tmdbId,
            movie = !i.isSeries,
            title = i.name,
            year = i.year?.toString(),
            overview = i.overview.orEmpty(),
            poster = i.poster,
            backdrop = i.backdrop,
            rating = 0.0,
            state = MediaState.AVAILABLE,
            embyId = i.id,
        )
    }
}
