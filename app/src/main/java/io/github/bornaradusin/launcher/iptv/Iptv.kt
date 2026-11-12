package io.github.bornaradusin.launcher.iptv

import android.content.Context
import android.util.LruCache
import io.github.bornaradusin.launcher.IptvAccount
import io.github.bornaradusin.launcher.Store
import io.github.bornaradusin.launcher.TvGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The provider's channels, by category. */
class LiveCatalog(val categories: List<XCategory> = emptyList(), val channels: List<XChannel> = emptyList()) {
    val byId: Map<String, XChannel> by lazy { channels.associateBy { it.id } }
    val byCategory: Map<String, List<XChannel>> by lazy { channels.groupBy { it.category } }
    val categoryNames: Map<String, String> by lazy { categories.associate { it.id to it.name } }
}

/** The provider's films or series, by category. */
class VodCatalog<T>(val categories: List<XCategory> = emptyList(), val items: List<T> = emptyList(), val at: Long = 0L)

/** How IPTV stands, for Settings and the phone. */
data class IptvStatus(
    val account: AccountInfo? = null,
    val liveLoading: Boolean = false,
    val liveDone: Int = 0,
    val liveTotal: Int = 0,
    val liveAt: Long = 0L,
    val filmsLoading: Boolean = false,
    val seriesLoading: Boolean = false,
    val vodAt: Long = 0L,
    /** What's wrong with the account or the channels; null: nothing. */
    val problem: String? = null,
    val vodProblem: String? = null,
    val channels: Int = 0,
    val films: Int = 0,
    val series: Int = 0,
)

/** A row of channels on the TV tab: one of your groups, a provider's category, or the ones watched last. */
data class TvRow(
    val id: String,
    val title: String,
    val hint: String,
    val channels: List<XChannel>,
    /** Your group (null for a provider's category or Recently watched). */
    val group: TvGroup?,
)

/** Where a film or episode was stopped (all times in milliseconds). */
data class Resume(
    val key: String,
    val position: Long,
    val duration: Long,
    val at: Long,
    val title: String,
    val poster: String?,
    /** The series an episode is from (to open it again); null for a film. */
    val seriesId: String?,
)

/**
 * IPTV from an Xtream provider, the way Emby5 loads it: categories one at a time, each usable as soon
 * as it's in, a category that fails keeping what was there before, and everything kept on the Shield
 * so the TV tab opens at once. Big refreshes wait while something is playing. The guide is asked for
 * one channel at a time, only for the channels on screen, the focused one first.
 */
class Iptv(
    private val context: Context,
    private val store: Store,
    val xtream: Xtream,
    private val scope: CoroutineScope,
    /** The guarded client every IPTV connection uses (the player's too). */
    val http: okhttp3.OkHttpClient,
) {
    private val _live = MutableStateFlow(LiveCatalog())
    val live: StateFlow<LiveCatalog> = _live
    private val _films = MutableStateFlow(VodCatalog<XFilm>())
    val films: StateFlow<VodCatalog<XFilm>> = _films
    private val _series = MutableStateFlow(VodCatalog<XSeries>())
    val series: StateFlow<VodCatalog<XSeries>> = _series
    private val _state = MutableStateFlow(IptvStatus())
    val state: StateFlow<IptvStatus> = _state

    /** Something plays full screen: big refreshes wait for it to stop. */
    val watching = MutableStateFlow(false)

    /** Goes up whenever a channel's guide comes in. */
    private val _guideVersion = MutableStateFlow(0)
    val guideVersion: StateFlow<Int> = _guideVersion

    /** Goes up whenever a resume point changes. */
    private val _resumeVersion = MutableStateFlow(0)
    val resumeVersion: StateFlow<Int> = _resumeVersion

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    /** The channels watched last, newest first. */
    val recents: StateFlow<List<String>> = _recents

    private var key: String? = null
    private var dir: File? = null
    private var jobs = mutableListOf<Job>()
    private var liveJob: Job? = null
    private var filmsJob: Job? = null
    private var seriesJob: Job? = null

    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    val account: IptvAccount? get() = store.config.value.iptv

    fun start() {
        scope.launch {
            store.config.map { it.iptv?.let { a -> keyOf(a) } }.distinctUntilChanged().collect { k -> switchTo(k) }
        }
        // Films or series turned on: fetched if they aren't here
        scope.launch {
            store.config.map { it.iptv?.let { a -> a.films to a.series } }.distinctUntilChanged().collect { on ->
                val a = account ?: return@collect
                if (on == null || key != keyOf(a)) return@collect
                if (on.first && _films.value.items.isEmpty()) refreshFilms(a)
                if (on.second && _series.value.items.isEmpty()) refreshSeries(a)
            }
        }
        repeat(2) { scope.launch { guideWorker() } }
        // Kept fresh: channels twice a day, films and series as often as Settings says
        scope.launch {
            while (true) {
                delay(30 * 60_000L)
                val a = account ?: continue
                refreshIfOld(a)
            }
        }
    }

    private fun keyOf(a: IptvAccount) = "${a.server}\n${a.username}"

    private suspend fun switchTo(k: String?) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        listOfNotNull(liveJob, filmsJob, seriesJob).forEach { it.cancel() }
        liveJob = null
        filmsJob = null
        seriesJob = null
        guides.evictAll()
        synchronized(wanted) { wanted.clear() }
        filmInfos.evictAll()
        seriesInfos.evictAll()
        key = k
        _live.value = LiveCatalog()
        _films.value = VodCatalog()
        _series.value = VodCatalog()
        _state.value = IptvStatus()
        if (k == null) {
            dir = null
            _recents.value = emptyList()
            return
        }
        val hash = MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val d = File(context.filesDir, "iptv/$hash").apply { mkdirs() }
        dir = d
        _recents.value = prefs.getString("recents:$hash", "").orEmpty().split(',').filter { it.isNotBlank() }
        withContext(Dispatchers.IO) {
            readLive(d)?.let { _live.value = it }
            readFilms(d)?.let { _films.value = it }
            readSeries(d)?.let { _series.value = it }
        }
        counts()
        val a = account ?: return
        // What the provider said about the account last time: it's only asked again with the lists
        val known = prefs.getString("account:$hash", null)?.let { t ->
            runCatching {
                val o = JSONObject(t)
                AccountInfo(o.optString("status"), o.optLong("expires").takeIf { it > 0 }, o.optInt("active"), o.optInt("max"))
            }.getOrNull()
        }
        _state.update { it.copy(account = known, liveAt = prefs.getLong("liveAt:$hash", 0L), vodAt = _films.value.at.coerceAtLeast(_series.value.at)) }
        refreshIfOld(a)
    }

    private fun refreshIfOld(a: IptvAccount) {
        val now = System.currentTimeMillis()
        if (_live.value.channels.isEmpty() || now - _state.value.liveAt > 12 * 3_600_000L) refreshLive(a)
        val every = a.vodHours * 3_600_000L
        if (a.films && (_films.value.items.isEmpty() || (every > 0 && now - _films.value.at > every))) refreshFilms(a)
        if (a.series && (_series.value.items.isEmpty() || (every > 0 && now - _series.value.at > every))) refreshSeries(a)
    }

    /** Everything again: the account, the channels, the films and the series. */
    fun refreshAll() {
        val a = account ?: return
        refreshLive(a)
        if (a.films) refreshFilms(a)
        if (a.series) refreshSeries(a)
    }

    fun refreshVod() {
        val a = account ?: return
        if (a.films) refreshFilms(a)
        if (a.series) refreshSeries(a)
    }

    private suspend fun checkAccount(a: IptvAccount) {
        val info = runCatching { io { xtream.account(a) } }
        info.onSuccess { i ->
            _state.update { it.copy(account = i, problem = if (it.problem?.startsWith("The provider turned") == true) null else it.problem) }
            dir?.let { d ->
                prefs.edit().putString(
                    "account:${d.name}",
                    JSONObject().put("status", i.status).put("expires", i.expires ?: 0L).put("active", i.active).put("max", i.max).toString(),
                ).apply()
            }
        }
        info.onFailure { e -> if (e is XtreamException && e.auth) _state.update { it.copy(problem = e.message) } }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Waits while something plays full screen. */
    private suspend fun idle() {
        watching.first { !it }
    }

    // ---- channels ----------------------------------------------------------------------------------

    fun refreshLive(account: IptvAccount? = this.account) {
        val a = account ?: return
        if (liveJob?.isActive == true) return
        liveJob = scope.launch { loadLive(a) }
    }

    private suspend fun loadLive(a: IptvAccount) {
        val d = dir ?: return
        val mine = key
        _state.update { it.copy(liveLoading = true, liveDone = 0, liveTotal = 0) }
        try {
            // The account's state comes with the lists (never on its own)
            checkAccount(a)
            val cats = try {
                io { xtream.liveCategories(a) }
            } catch (e: XtreamException) {
                _state.update { it.copy(problem = e.message) }
                return
            }
            val old = _live.value
            if (cats.isEmpty()) {
                // A provider without categories: the whole list at once
                val all = ArrayList<XChannel>()
                try {
                    io { xtream.channels(a, null) { all += it } }
                } catch (e: XtreamException) {
                    _state.update { it.copy(problem = e.message) }
                    return
                }
                publishLive(LiveCatalog(emptyList(), all), d, complete = true)
                return
            }
            val fresh = HashMap<String, List<XChannel>>()
            var failed = 0
            var inARow = 0
            var stopped: String? = null
            var shownAt = 0L
            _state.update { it.copy(liveTotal = cats.size) }
            for ((i, cat) in cats.withIndex()) {
                idle()
                val list = ArrayList<XChannel>()
                try {
                    io { xtream.channels(a, cat.id) { c -> list += if (c.category == cat.id) c else c.copy(category = cat.id) } }
                    fresh[cat.id] = list.distinctBy { it.id }
                    inARow = 0
                } catch (e: XtreamException) {
                    failed++
                    if (e.auth) {
                        stopped = e.message
                        break
                    }
                    if (e.transport && ++inARow >= 3) {
                        stopped = e.message
                        break
                    }
                }
                _state.update { it.copy(liveDone = i + 1) }
                // What's in so far shows straight away (a category that hasn't come keeps what it had)
                val now = System.currentTimeMillis()
                if (now - shownAt > 700 || i == cats.lastIndex) {
                    shownAt = now
                    _live.value = merge(cats, fresh, old)
                    counts()
                }
            }
            val merged = merge(cats, fresh, old)
            publishLive(merged, d, complete = failed == 0)
            _state.update {
                it.copy(problem = stopped ?: if (failed > 0) "$failed of ${cats.size} groups didn't come: the ones from before are kept" else null)
            }
        } finally {
            if (key == mine) _state.update { it.copy(liveLoading = false) }
        }
    }

    private fun merge(cats: List<XCategory>, fresh: Map<String, List<XChannel>>, old: LiveCatalog): LiveCatalog {
        val channels = ArrayList<XChannel>()
        cats.forEach { c -> channels += fresh[c.id] ?: old.byCategory[c.id].orEmpty() }
        return LiveCatalog(cats, channels)
    }

    private suspend fun publishLive(c: LiveCatalog, d: File, complete: Boolean) {
        _live.value = c
        counts()
        if (complete || c.channels.isNotEmpty()) io { writeLive(d, c) }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("liveAt:${d.name}", now).apply()
        _state.update { it.copy(liveAt = now) }
    }

    // ---- films and series ----------------------------------------------------------------------------

    private fun refreshFilms(a: IptvAccount) {
        if (filmsJob?.isActive == true) return
        filmsJob = scope.launch { loadVod(a, films = true) }
    }

    private fun refreshSeries(a: IptvAccount) {
        if (seriesJob?.isActive == true) return
        seriesJob = scope.launch { loadVod(a, films = false) }
    }

    private suspend fun loadVod(a: IptvAccount, films: Boolean) {
        val d = dir ?: return
        val mine = key
        _state.update { if (films) it.copy(filmsLoading = true, vodProblem = null) else it.copy(seriesLoading = true, vodProblem = null) }
        try {
            val cats = try {
                io { if (films) xtream.filmCategories(a) else xtream.seriesCategories(a) }
            } catch (e: XtreamException) {
                _state.update { it.copy(vodProblem = e.message) }
                return
            }
            val fresh = HashMap<String, List<Any>>()
            var failed = 0
            var inARow = 0
            var shownAt = 0L
            val oldFilms = _films.value
            val oldSeries = _series.value
            val oldBy: Map<String, List<Any>> = if (films) oldFilms.items.groupBy { it.category } else oldSeries.items.groupBy { it.category }
            fun merged(): List<Any> = if (cats.isEmpty()) fresh[""].orEmpty() else cats.flatMap { c -> fresh[c.id] ?: oldBy[c.id].orEmpty() }
            val ids = if (cats.isEmpty()) listOf<String?>(null) else cats.map { it.id }
            for ((i, id) in ids.withIndex()) {
                idle()
                val list = ArrayList<Any>()
                try {
                    io {
                        if (films) xtream.films(a, id) { f -> list += if (id == null || f.category == id) f else f.copy(category = id) }
                        else xtream.series(a, id) { s -> list += if (id == null || s.category == id) s else s.copy(category = id) }
                    }
                    fresh[id ?: ""] = list
                    inARow = 0
                } catch (e: XtreamException) {
                    failed++
                    if (e.auth || (e.transport && ++inARow >= 3)) {
                        _state.update { it.copy(vodProblem = e.message) }
                        break
                    }
                }
                val now = System.currentTimeMillis()
                if (now - shownAt > 1000 || i == ids.lastIndex) {
                    shownAt = now
                    show(films, cats, merged(), if (films) oldFilms.at else oldSeries.at)
                }
            }
            val at = System.currentTimeMillis()
            show(films, cats, merged(), at)
            io { if (films) writeFilms(d, _films.value) else writeSeries(d, _series.value) }
            _state.update {
                it.copy(vodAt = at, vodProblem = if (failed > 0) "Some ${if (films) "film" else "series"} groups didn't come: the ones from before are kept" else it.vodProblem)
            }
        } finally {
            if (key == mine) _state.update { if (films) it.copy(filmsLoading = false) else it.copy(seriesLoading = false) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun show(films: Boolean, cats: List<XCategory>, items: List<Any>, at: Long) {
        if (films) _films.value = VodCatalog(cats, (items as List<XFilm>).distinctBy { it.id }, at)
        else _series.value = VodCatalog(cats, (items as List<XSeries>).distinctBy { it.id }, at)
        counts()
    }

    private fun counts() {
        _state.update {
            it.copy(
                channels = _live.value.channels.distinctBy { c -> c.id }.size,
                films = _films.value.items.size,
                series = _series.value.items.size,
            )
        }
    }

    private val filmInfos = LruCache<String, FilmInfo>(300)
    private val seriesInfos = LruCache<String, SeriesInfo>(100)

    suspend fun filmInfo(id: String): FilmInfo? {
        filmInfos.get(id)?.let { return it }
        val a = account ?: return null
        return runCatching { io { xtream.filmInfo(a, id) } }.getOrNull()?.also { filmInfos.put(id, it) }
    }

    fun cachedFilmInfo(id: String): FilmInfo? = filmInfos.get(id)

    suspend fun seriesInfo(id: String): SeriesInfo? {
        seriesInfos.get(id)?.let { return it }
        val a = account ?: return null
        return runCatching { io { xtream.seriesInfo(a, id) } }.getOrNull()?.also { seriesInfos.put(id, it) }
    }

    // ---- the guide -----------------------------------------------------------------------------------

    private class Guide(val at: Long, val list: List<Programme>)

    private val guides = LruCache<String, Guide>(1500)
    private val wanted = ArrayDeque<String>()
    private val asking = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The programmes known for [channelId] (null: not asked for yet, or on the way). */
    fun guide(channelId: String): List<Programme>? = guides.get(channelId)?.list

    /** What's on [channelId] now, and next. */
    fun nowNext(channelId: String, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = guide(channelId) ?: return null to null
        val i = list.indexOfFirst { it.start <= now && it.end > now }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }

    /** [channelId] is on screen: its guide is fetched if it isn't here or is getting old. */
    fun want(channelId: String) {
        val g = guides.get(channelId)
        val now = System.currentTimeMillis()
        if (g != null) {
            val age = now - g.at
            val runsOut = (g.list.lastOrNull()?.end ?: 0L) < now + 90 * 60_000L
            if (age < 10 * 60_000L || (age < 60 * 60_000L && !runsOut)) return
        }
        synchronized(wanted) {
            if (channelId in asking) return
            wanted.remove(channelId)
            // The latest asked for comes first: what's on screen now, not what was scrolled past
            wanted.addFirst(channelId)
            while (wanted.size > 60) wanted.removeLast()
        }
        wake.trySend(Unit)
    }

    private suspend fun guideWorker() {
        while (true) {
            wake.receive()
            while (true) {
                val a = account ?: break
                val id = synchronized(wanted) { wanted.removeFirstOrNull()?.also { asking += it } } ?: break
                // More than one waiting: the other worker can help
                if (synchronized(wanted) { wanted.isNotEmpty() }) wake.trySend(Unit)
                val list = runCatching { io { xtream.guide(a, id) } }
                synchronized(wanted) { asking -= id }
                list.onSuccess {
                    guides.put(id, Guide(System.currentTimeMillis(), it))
                    _guideVersion.update { v -> v + 1 }
                }
                if (list.isFailure) delay(500)
            }
        }
    }

    // ---- playing -------------------------------------------------------------------------------------

    fun liveUrl(c: XChannel): String? = account?.let { xtream.liveUrl(it, c) }
    fun filmUrl(f: XFilm, ext: String? = null): String? = account?.let { xtream.filmUrl(it, f.id, ext ?: f.ext) }
    fun episodeUrl(e: Episode): String? = account?.let { xtream.episodeUrl(it, e) }

    /** [c] was watched: first in Recently watched. */
    fun watched(c: XChannel) {
        val d = dir ?: return
        val list = (listOf(c.id) + _recents.value.filter { it != c.id }).take(12)
        _recents.value = list
        prefs.edit().putString("recents:${d.name}", list.joinToString(",")).apply()
    }

    private fun resumePrefs() = dir?.let { context.getSharedPreferences("iptv-resume-${it.name}", Context.MODE_PRIVATE) }

    fun resume(key: String): Resume? {
        val p = resumePrefs() ?: return null
        val text = p.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(text)
            Resume(key, o.getLong("pos"), o.optLong("dur"), o.optLong("at"), o.optString("title"), o.optString("poster").takeIf { it.isNotBlank() }, o.optString("series").takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    fun isWatched(key: String): Boolean = resumePrefs()?.getBoolean("w:$key", false) == true

    /** Where [key] ("f:id" for a film, "e:id" for an episode) was stopped; near the end counts as watched. */
    fun saveResume(key: String, position: Long, duration: Long, title: String, poster: String?, seriesId: String?) {
        val p = resumePrefs() ?: return
        val done = duration > 0 && position >= duration * 0.92
        val e = p.edit()
        if (done || position < 30_000) {
            e.remove(key)
            if (done) e.putBoolean("w:$key", true)
        } else {
            e.putString(
                key,
                JSONObject().put("pos", position).put("dur", duration).put("at", System.currentTimeMillis())
                    .put("title", title).put("poster", poster ?: "").put("series", seriesId ?: "").toString(),
            )
        }
        e.apply()
        _resumeVersion.update { it + 1 }
    }

    fun clearResume(key: String) {
        resumePrefs()?.edit()?.remove(key)?.remove("w:$key")?.apply()
        _resumeVersion.update { it + 1 }
    }

    /** Films and episodes started and not finished, the latest first. */
    fun continueWatching(): List<Resume> {
        val p = resumePrefs() ?: return emptyList()
        return p.all.keys.filter { it.startsWith("f:") || it.startsWith("e:") }.mapNotNull { resume(it) }
            .sortedByDescending { it.at }
            // One per series: its latest episode
            .distinctBy { it.seriesId ?: it.key }
            .take(20)
    }

    // ---- rows and groups -----------------------------------------------------------------------------

    /** The TV tab's rows: Recently watched, your groups, then (if wanted) the provider's other groups. */
    fun rows(a: IptvAccount, c: LiveCatalog, recents: List<String>, withHidden: Boolean = false): List<TvRow> {
        val out = ArrayList<TvRow>()
        val adultCats = c.categories.filter { it.adult }.map { it.id }.toSet()
        fun ok(ch: XChannel) = !a.hideAdult || (!ch.adult && ch.category !in adultCats)
        if (!withHidden) {
            val r = recents.mapNotNull { c.byId[it] }.filter(::ok)
            if (r.isNotEmpty()) out += TvRow("recent", "Recently watched", "On this Shield", r, null)
        }
        val followed = HashSet<String>()
        a.groups.forEach { g ->
            g.category?.let { followed += it }
            if (!g.on && !withHidden) return@forEach
            val channels = groupChannels(g, c).filter(::ok)
            val hint = if (g.category != null) "From your provider · kept up to date" else "Your group · ${channels.size} channel${if (channels.size == 1) "" else "s"}"
            out += TvRow("g:${g.id}", g.name, hint, channels, g)
        }
        if (a.providerGroups && !withHidden) {
            c.categories.forEach { cat ->
                if (cat.id in followed || (a.hideAdult && cat.adult)) return@forEach
                val channels = c.byCategory[cat.id].orEmpty().filter(::ok)
                if (channels.isNotEmpty()) out += TvRow("c:${cat.id}", cat.name, "From your provider · ${channels.size}", channels, null)
            }
        }
        return out
    }

    fun groupChannels(g: TvGroup, c: LiveCatalog): List<XChannel> =
        if (g.category != null) c.byCategory[g.category].orEmpty() else g.channels.mapNotNull { c.byId[it] }.distinctBy { it.id }

    private fun change(block: (IptvAccount) -> IptvAccount) = store.change { c -> c.copy(iptv = c.iptv?.let(block)) }

    private fun newId() = "g" + System.currentTimeMillis().toString(36) + (0..99).random()

    /** A group of yours for [g]: one that follows the provider's category becomes yours (its channels as they are now). */
    private fun own(g: TvGroup): TvGroup =
        if (g.category == null) g else g.copy(channels = _live.value.byCategory[g.category].orEmpty().map { it.id }, category = null)

    fun newGroup(name: String, channels: List<String> = emptyList()): String {
        val id = newId()
        change { a -> a.copy(groups = a.groups + TvGroup(id, name.trim().ifBlank { "My channels" }, channels.distinct())) }
        return id
    }

    fun importCategory(categoryId: String): Boolean {
        val cat = _live.value.categories.firstOrNull { it.id == categoryId } ?: return false
        if (account?.groups?.any { it.category == categoryId } == true) return false
        change { a -> a.copy(groups = a.groups + TvGroup(newId(), cat.name, category = cat.id)) }
        return true
    }

    fun setInGroup(groupId: String, channelId: String, inside: Boolean) = change { a ->
        a.copy(groups = a.groups.map { g ->
            if (g.id != groupId) g else {
                val mine = own(g)
                mine.copy(channels = if (inside) (mine.channels - channelId) + channelId else mine.channels - channelId)
            }
        })
    }

    fun saveGroup(groupId: String, name: String, channels: List<String>?) = change { a ->
        a.copy(groups = a.groups.map { g ->
            if (g.id != groupId) g
            else if (channels == null) g.copy(name = name.trim().ifBlank { g.name })
            else g.copy(name = name.trim().ifBlank { g.name }, channels = channels.distinct(), category = null)
        })
    }

    fun deleteGroup(groupId: String) = change { a -> a.copy(groups = a.groups.filter { it.id != groupId }) }

    fun showGroup(groupId: String, on: Boolean) = change { a -> a.copy(groups = a.groups.map { if (it.id == groupId) it.copy(on = on) else it }) }

    fun moveGroup(groupId: String, by: Int) = change { a ->
        val i = a.groups.indexOfFirst { it.id == groupId }
        val j = i + by
        if (i < 0 || j !in a.groups.indices) a else a.copy(groups = a.groups.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun orderGroups(ids: List<String>, on: Map<String, Boolean>) = change { a ->
        val byId = a.groups.associateBy { it.id }
        val given = ids.mapNotNull { byId[it] }.map { g -> on[g.id]?.let { g.copy(on = it) } ?: g }
        a.copy(groups = given + a.groups.filter { g -> g.id !in ids })
    }

    /** Moves [channelId] one place along [groupId]. */
    fun moveChannel(groupId: String, channelId: String, by: Int) = change { a ->
        a.copy(groups = a.groups.map { g ->
            if (g.id != groupId) g else {
                val mine = own(g)
                val i = mine.channels.indexOf(channelId)
                val j = i + by
                if (i < 0 || j !in mine.channels.indices) mine
                else mine.copy(channels = mine.channels.toMutableList().apply { add(j, removeAt(i)) })
            }
        })
    }

    fun setChannelOrder(groupId: String, channels: List<String>) = change { a ->
        a.copy(groups = a.groups.map { g -> if (g.id == groupId) g.copy(channels = channels.distinct(), category = null) else g })
    }

    // ---- what's kept on the Shield -------------------------------------------------------------------

    private fun cats(list: List<XCategory>) = JSONArray().apply { list.forEach { put(JSONArray().put(it.id).put(it.name).put(it.adult)) } }

    private fun cats(a: JSONArray?): List<XCategory> = a?.let { (0 until it.length()).map { i -> val c = it.getJSONArray(i); XCategory(c.getString(0), c.optString(1), c.optBoolean(2)) } } ?: emptyList()

    private fun write(f: File, o: JSONObject) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun writeLive(d: File, c: LiveCatalog) = runCatching {
        write(
            File(d, "live.json"),
            JSONObject().put("categories", cats(c.categories)).put("channels", JSONArray().apply {
                c.channels.forEach { put(JSONArray().put(it.id).put(it.num).put(it.name).put(it.logo ?: "").put(it.category).put(it.ext ?: "").put(it.adult)) }
            }),
        )
    }

    private fun readLive(d: File): LiveCatalog? = runCatching {
        val o = JSONObject(File(d, "live.json").readText())
        val a = o.getJSONArray("channels")
        LiveCatalog(cats(o.optJSONArray("categories")), (0 until a.length()).map { i ->
            val c = a.getJSONArray(i)
            XChannel(c.getString(0), c.optInt(1), c.optString(2), c.optString(3).takeIf { it.isNotBlank() }, c.optString(4), c.optString(5).takeIf { it.isNotBlank() }, c.optBoolean(6))
        })
    }.getOrNull()

    private fun writeFilms(d: File, c: VodCatalog<XFilm>) = runCatching {
        write(
            File(d, "films.json"),
            JSONObject().put("at", c.at).put("categories", cats(c.categories)).put("items", JSONArray().apply {
                c.items.forEach { put(JSONArray().put(it.id).put(it.name).put(it.poster ?: "").put(it.category).put(it.rating.toDouble()).put(it.added).put(it.ext).put(it.adult)) }
            }),
        )
    }

    private fun readFilms(d: File): VodCatalog<XFilm>? = runCatching {
        val o = JSONObject(File(d, "films.json").readText())
        val a = o.getJSONArray("items")
        VodCatalog(cats(o.optJSONArray("categories")), (0 until a.length()).map { i ->
            val f = a.getJSONArray(i)
            XFilm(f.getString(0), f.optString(1), f.optString(2).takeIf { it.isNotBlank() }, f.optString(3), f.optDouble(4, 0.0).toFloat(), f.optLong(5), f.optString(6, "mp4"), f.optBoolean(7))
        }, o.optLong("at"))
    }.getOrNull()

    private fun writeSeries(d: File, c: VodCatalog<XSeries>) = runCatching {
        write(
            File(d, "series.json"),
            JSONObject().put("at", c.at).put("categories", cats(c.categories)).put("items", JSONArray().apply {
                c.items.forEach {
                    put(
                        JSONArray().put(it.id).put(it.name).put(it.poster ?: "").put(it.category).put(it.rating.toDouble()).put(it.added)
                            .put(it.plot).put(it.year).put(it.genre).put(it.backdrop ?: "").put(it.adult),
                    )
                }
            }),
        )
    }

    private fun readSeries(d: File): VodCatalog<XSeries>? = runCatching {
        val o = JSONObject(File(d, "series.json").readText())
        val a = o.getJSONArray("items")
        VodCatalog(cats(o.optJSONArray("categories")), (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            XSeries(
                s.getString(0), s.optString(1), s.optString(2).takeIf { it.isNotBlank() }, s.optString(3), s.optDouble(4, 0.0).toFloat(), s.optLong(5),
                s.optString(6), s.optString(7), s.optString(8), s.optString(9).takeIf { it.isNotBlank() }, s.optBoolean(10),
            )
        }, o.optLong("at"))
    }.getOrNull()

    /** Signed out: what was kept for the account goes. */
    fun forget(a: IptvAccount) {
        val hash = MessageDigest.getInstance("SHA-1").digest(keyOf(a).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        scope.launch(Dispatchers.IO) { File(context.filesDir, "iptv/$hash").deleteRecursively() }
        prefs.edit().remove("recents:$hash").remove("liveAt:$hash").apply()
        prefs.edit().remove("account:$hash").apply()
        context.deleteSharedPreferences("iptv-resume-$hash")
    }
}
