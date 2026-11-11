package io.github.bornaradusin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.iptv.FilmInfo
import io.github.bornaradusin.launcher.iptv.Pictures
import io.github.bornaradusin.launcher.iptv.Resume
import io.github.bornaradusin.launcher.iptv.SeriesInfo
import io.github.bornaradusin.launcher.iptv.TvRow
import io.github.bornaradusin.launcher.iptv.XChannel
import io.github.bornaradusin.launcher.iptv.XFilm
import io.github.bornaradusin.launcher.iptv.XSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The TV tab's parts, along the top. */
enum class TvPart(val title: String) { LIVE("Live TV"), GUIDE("Guide"), FILMS("Films"), SERIES("Series"), SEARCH("Search") }

/** Kept while the TV tab isn't showing (watching, Settings), so it comes back as it was left. */
class TvMemory {
    var part by mutableStateOf(TvPart.LIVE)
    val live = HomeMemory()
    val films = HomeMemory()
    val series = HomeMemory()
    val search = HomeMemory()
    val episodes = HomeMemory()
    var openSeries by mutableStateOf<XSeries?>(null)
    var query by mutableStateOf("")
    /** The guide's focused channel (in the list of all of them) and time. */
    var guideRow = 0
    var guideAt = 0L
    /** Goes up when Groups picks a group for the guide to go to. */
    var guideJump by androidx.compose.runtime.mutableIntStateOf(0)
    val pills = HashMap<String, FocusRequester>()
    fun pill(k: String) = pills.getOrPut(k) { FocusRequester() }
}

class TvActions(
    val openSettings: () -> Unit,
    val watch: (Watch) -> Unit,
    val openTrailer: (TrailerRequest) -> Unit,
    val toast: (String) -> Unit,
)

/** What the top of the TV tab is about. */
private sealed interface TvHero {
    data object None : TvHero
    data class Channel(val c: XChannel) : TvHero
    data class Film(val f: XFilm, val resume: Resume?) : TvHero
    data class Series(val s: XSeries) : TvHero
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvScreen(app: LauncherApp, memory: TvMemory, actions: TvActions, onTab: (Tab) -> Unit, startOnTabs: Boolean) {
    val config by app.store.config.collectAsState()
    val account = config.iptv ?: return
    val live by app.iptv.live.collectAsState()
    val recents by app.iptv.recents.collectAsState()
    val status by app.iptv.state.collectAsState()
    val films by app.iptv.films.collectAsState()
    val series by app.iptv.series.collectAsState()
    val resumes by app.iptv.resumeVersion.collectAsState()
    val weather by app.weather.now.collectAsState()
    val update by app.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000 - System.currentTimeMillis() % 30_000 + 20)
            value = System.currentTimeMillis()
        }
    }
    val rows = remember(account, live, recents) { app.iptv.rows(account, live, recents) }

    var hero by remember { mutableStateOf<TvHero>(TvHero.None) }
    var shown by remember { mutableStateOf<TvHero>(TvHero.None) }
    LaunchedEffect(hero) {
        delay(150)
        shown = hero
    }

    // Over the tab
    var adding by remember { mutableStateOf<Pair<XChannel, TvRow?>?>(null) }
    var naming by remember { mutableStateOf<XChannel?>(null) }
    var picking by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var filmMenu by remember { mutableStateOf<XFilm?>(null) }
    var moving by remember { mutableStateOf<Pair<String, String>?>(null) }
    var onPills by remember { mutableStateOf(false) }
    val overlay = adding != null || naming != null || picking || searching || filmMenu != null

    val tabFocus = remember { FocusRequester() }
    fun focusKey(m: HomeMemory, key: String?) {
        val k = key ?: return
        scope.launch {
            delay(70)
            runCatching { m.requesters[k]?.requestFocus() }
        }
    }
    fun focusPill(p: TvPart = memory.part) {
        scope.launch {
            delay(60)
            runCatching { memory.pill(p.name).requestFocus() }
        }
    }

    // Films or Series turned off while showing: back to Live TV
    LaunchedEffect(account.films, account.series) {
        if ((memory.part == TvPart.FILMS && !account.films) || (memory.part == TvPart.SERIES && !account.series)) {
            memory.part = TvPart.LIVE
            memory.openSeries = null
        }
    }

    // Focus where it was left; on the tab when switched to; else on the part's pill
    LaunchedEffect(Unit) {
        delay(80)
        if (startOnTabs && runCatching { tabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        val m = when (memory.part) {
            TvPart.LIVE -> memory.live
            TvPart.FILMS -> memory.films
            TvPart.SERIES -> if (memory.openSeries != null) null else memory.series
            TvPart.SEARCH -> memory.search
            TvPart.GUIDE -> null
        }
        val key = m?.focusedKey
        if (memory.part == TvPart.GUIDE || memory.openSeries != null) return@LaunchedEffect
        if (m != null && key != null && runCatching { m.requesters[key]?.requestFocus() }.getOrNull() != null) return@LaunchedEffect
        runCatching { memory.pill(memory.part.name).requestFocus() }
    }

    BackHandler(enabled = !overlay && moving == null && memory.openSeries == null) {
        if (!onPills) focusPill() else onTab(Tab.HOME)
    }
    BackHandler(enabled = moving != null) {
        moving = null
    }

    fun watchChannel(row: TvRow, c: XChannel) {
        val r = rows.indexOfFirst { it.id == row.id }
        val i = row.channels.indexOfFirst { it.id == c.id }
        if (r >= 0 && i >= 0) actions.watch(Watch.Live(rows, r, i))
    }

    Box(Modifier.fillMaxSize().background(Tv.bg)) {
        // The top: the focused channel playing (muted), or a film's picture
        val seriesOpen = memory.openSeries
        if (memory.part == TvPart.SERIES && seriesOpen != null) SeriesBackdrop(app, seriesOpen)
        else when (val h = shown) {
            is TvHero.Channel -> ChannelBackdrop(h.c)
            is TvHero.Film -> FilmBackdrop(app, h.f)
            is TvHero.Series -> BackdropImage(Pictures.allowed(h.s.backdrop) ?: Pictures.allowed(h.s.poster), null, false, {}, {})
            TvHero.None -> BackdropImage(null, null, false, {}, {})
        }

        CompositionLocalProvider(LocalTvEnabled provides !overlay) {
            TopBar(
                tab = Tab.TV,
                onTab = onTab,
                tabFocus = tabFocus,
                small = true,
                weather = weather,
                update = (update as? io.github.bornaradusin.launcher.update.UpdateState.Available)?.update?.version,
                onUpdate = { app.updater.update() },
                onSearch = { searching = true },
                onSettings = actions.openSettings,
            )

            val open = memory.openSeries
            if (memory.part == TvPart.SERIES && open != null) {
                SeriesPage(app, open, memory, actions, onClose = {
                    memory.openSeries = null
                    focusKey(memory.series, memory.series.focusedKey)
                })
            } else {
            val pillsTop = if (memory.part == TvPart.GUIDE) 180.dp else 274.dp
            // The top's words
            when (memory.part) {
                TvPart.LIVE -> ChannelHero(app, (shown as? TvHero.Channel)?.c, now, status, rows.size, memory.part)
                TvPart.SEARCH -> if (shown is TvHero.Film || shown is TvHero.Series) VodHero(app, shown, memory.part, status)
                    else ChannelHero(app, (shown as? TvHero.Channel)?.c, now, status, rows.size, memory.part)
                TvPart.FILMS, TvPart.SERIES -> VodHero(app, shown, memory.part, status)
                TvPart.GUIDE -> Unit
            }

            // Live TV, Guide, Films, Series, Search (and Groups, to jump to one)
            Row(Modifier.padding(start = 48.dp, top = pillsTop), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val parts = TvPart.entries.filter { (it != TvPart.FILMS || account.films) && (it != TvPart.SERIES || account.series) }
                parts.forEach { p ->
                    SubnavPill(
                        if (p == TvPart.SEARCH && memory.query.isNotBlank()) "Search: ${memory.query}" else p.title,
                        when (p) {
                            TvPart.LIVE -> Ic.live
                            TvPart.GUIDE -> Ic.guide
                            TvPart.FILMS -> Ic.film
                            TvPart.SERIES -> Ic.tv
                            TvPart.SEARCH -> Ic.search
                        },
                        selected = memory.part == p,
                        onClick = {
                            if (p == TvPart.SEARCH) searching = true
                            else {
                                memory.part = p
                                hero = TvHero.None
                            }
                        },
                        focusRequester = memory.pill(p.name),
                        onFocus = { onPills = it },
                    )
                }
                if (memory.part == TvPart.LIVE || memory.part == TvPart.GUIDE) {
                    SubnavPill("Groups", Ic.layers, selected = false, onClick = { picking = true }, focusRequester = memory.pill("groups"), onFocus = { onPills = it })
                }
            }

            when (memory.part) {
                TvPart.LIVE -> LiveRows(
                    app, rows, memory.live, now, status, moving,
                    onFocus = { c, key ->
                        hero = TvHero.Channel(c)
                        memory.live.focusedKey = key
                    },
                    onWatch = ::watchChannel,
                    onHold = { c, row -> adding = c to row },
                    onMoved = { moving = it },
                )
                TvPart.GUIDE -> TvGuide(
                    app, rows, memory, now,
                    onWatch = { row, c -> watchChannel(row, c) },
                    onHold = { c, row -> adding = c to row },
                    onUp = { focusPill() },
                )
                TvPart.FILMS -> FilmRows(app, films.items, films.categories, account.hideAdult, memory.films, resumes,
                    onFocus = { f, key ->
                        hero = TvHero.Film(f, app.iptv.resume("f:${f.id}"))
                        memory.films.focusedKey = key
                    },
                    onPlay = { f -> actions.watch(Watch.Film(f, fromStart = false)) },
                    onHold = { filmMenu = it },
                    loading = status.filmsLoading,
                )
                TvPart.SERIES -> SeriesRows(app, series.items, series.categories, account.hideAdult, memory.series, resumes,
                    onFocus = { s, key ->
                        hero = TvHero.Series(s)
                        memory.series.focusedKey = key
                    },
                    onOpen = { s ->
                        memory.openSeries = s
                        memory.episodes.focusedKey = null
                    },
                    onResume = { r ->
                        val s = series.items.firstOrNull { it.id == r.seriesId }
                        if (s != null) scope.launch {
                            val info = app.iptv.seriesInfo(s.id)
                            val i = info?.episodes?.indexOfFirst { "e:${it.id}" == r.key } ?: -1
                            if (info == null || i < 0) {
                                memory.openSeries = s
                            } else actions.watch(Watch.Episodes(s, info.episodes, i, fromStart = false))
                        }
                    },
                    loading = status.seriesLoading,
                )
                TvPart.SEARCH -> SearchRows(
                    app, memory.query, live, films.items, series.items, account.films, account.series, account.hideAdult, memory.search, now,
                    onChannelFocus = { c, key ->
                        hero = TvHero.Channel(c)
                        memory.search.focusedKey = key
                    },
                    onWatch = { c ->
                        // ▲ ▼ go along the results
                        val list = rows.firstOrNull { r -> r.channels.any { it.id == c.id } }
                        val found = TvRow("search", "Search: ${memory.query}", "", searchChannels(live, memory.query, account.hideAdult), null)
                        val i = found.channels.indexOfFirst { it.id == c.id }
                        if (i >= 0) actions.watch(Watch.Live(listOf(found) + rows, 0, i))
                        else if (list != null) watchChannel(list, c)
                    },
                    onHold = { c -> adding = c to null },
                    onFilmFocus = { f, key ->
                        hero = TvHero.Film(f, app.iptv.resume("f:${f.id}"))
                        memory.search.focusedKey = key
                    },
                    onFilm = { f -> actions.watch(Watch.Film(f, false)) },
                    onFilmHold = { filmMenu = it },
                    onSeries = { s ->
                        memory.part = TvPart.SERIES
                        memory.openSeries = s
                    },
                )
            }
            }
        }

        // Holding OK on a channel
        adding?.let { (c, row) ->
            AddToGroupPanel(
                app, c, row,
                onNewGroup = {
                    adding = null
                    naming = c
                },
                onMove = if (row?.group != null) ({
                    adding = null
                    moving = row.group.id to c.id
                    focusKey(memory.live, "${row.id}:${c.id}")
                }) else null,
                onClose = { adding = null },
            )
        }
        naming?.let { c ->
            TextEntry("New group with ${c.name}", "", "Its name: the keyboard's Done key makes it · Back to leave it") { name ->
                naming = null
                if (!name.isNullOrBlank()) {
                    app.iptv.newGroup(name, listOf(c.id))
                    actions.toast("“${name.trim()}” is a new group, with ${c.name} in it")
                }
            }
        }
        if (picking) {
            GroupPicker(rows, null, onPick = { r ->
                picking = false
                if (memory.part == TvPart.GUIDE) {
                    val all = rows.flatMap { it.channels.map { c -> it.id to c.id } }
                    memory.guideRow = all.indexOfFirst { it.first == r.id }.coerceAtLeast(0)
                    memory.guideAt = 0L
                    memory.guideJump++
                } else {
                    val i = rows.indexOfFirst { it.id == r.id }
                    scope.launch {
                        if (i >= 0) memory.live.column.scrollToItem(i)
                        r.channels.firstOrNull()?.let { c -> focusKey(memory.live, "${r.id}:${c.id}") }
                    }
                }
            }, onClose = { picking = false })
        }
        if (searching) {
            TextEntry("Search channels, films and series", memory.query, "The keyboard's Search key looks · Back to leave it", search = true) { q ->
                searching = false
                if (q != null) {
                    memory.query = q.trim()
                    memory.part = TvPart.SEARCH
                    memory.search.focusedKey = null
                    hero = TvHero.None
                    scope.launch { memory.search.column.scrollToItem(0) }
                }
                focusPill(TvPart.SEARCH)
            }
        }
        filmMenu?.let { f ->
            val resume = remember(f.id, resumes) { app.iptv.resume("f:${f.id}") }
            val info = app.iptv.cachedFilmInfo(f.id)
            ChoicePanel(
                f.name,
                listOfNotNull(
                    MenuItem(Ic.restart, "Play from the beginning") {
                        filmMenu = null
                        actions.watch(Watch.Film(f, fromStart = true))
                    },
                    info?.trailer?.let { t ->
                        MenuItem(Ic.youtube, "Trailer") {
                            filmMenu = null
                            val link = if (t.contains("youtu")) t else "https://www.youtube.com/watch?v=$t"
                            actions.openTrailer(
                                TrailerRequest(
                                    title = f.name, playLabel = "Play the film",
                                    onPlay = { actions.watch(Watch.Film(f, fromStart = false)) },
                                    youtubeLink = link,
                                    load = { app.trailers.fromYouTube(link) },
                                ),
                            )
                        }
                    },
                    resume?.let {
                        MenuItem(Ic.trash, "Forget where you stopped") {
                            filmMenu = null
                            app.iptv.clearResume("f:${f.id}")
                        }
                    },
                ),
                onClose = { filmMenu = null },
            )
        }
    }
}

/** Behind the top: the focused channel's colour (nothing plays until you pick a channel). */
@Composable
private fun ChannelBackdrop(c: XChannel) {
    val colour = channelColour(c.name)
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0C1220), Tv.bg)))
                .background(Brush.radialGradient(listOf(colour.copy(alpha = 0.45f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1380f, 300f), radius = 640f)),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C06080C), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 1f to Tv.bg)))
    }
}

@Composable
private fun SeriesBackdrop(app: LauncherApp, s: XSeries) {
    val info by produceState<SeriesInfo?>(null, s.id) { value = app.iptv.seriesInfo(s.id) }
    BackdropImage(Pictures.allowed(info?.backdrop) ?: Pictures.allowed(s.backdrop) ?: Pictures.allowed(s.poster), null, false, {}, {})
}

@Composable
private fun FilmBackdrop(app: LauncherApp, f: XFilm) {
    val info by produceState(app.iptv.cachedFilmInfo(f.id), f.id) {
        delay(250)
        value = app.iptv.filmInfo(f.id)
    }
    BackdropImage(Pictures.allowed(info?.backdrop), null, false, {}, {})
}

/** The top of Live TV: what's on the focused channel, or how the channels stand. */
@Composable
private fun ChannelHero(app: LauncherApp, c: XChannel?, now: Long, status: io.github.bornaradusin.launcher.iptv.IptvStatus, groups: Int, part: TvPart) {
    val context = LocalContext.current
    val version by app.iptv.guideVersion.collectAsState()
    Column(Modifier.padding(start = 48.dp, top = 75.dp, end = 48.dp).widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (c == null) {
            Text(if (part == TvPart.SEARCH) "SEARCH · YOUR IPTV" else "LIVE TV · FROM YOUR IPTV", style = Tv.kicker)
            Text(if (part == TvPart.SEARCH) "Search" else "Live TV", style = Tv.heroTitle)
            Text(
                when {
                    status.liveLoading && status.liveTotal > 0 -> "Loading the channels: ${status.liveDone} of ${status.liveTotal} groups · ${status.channels} channels so far"
                    status.liveLoading -> "Loading the channels…"
                    status.problem != null && status.channels == 0 -> status.problem
                    else -> "%,d channels · %d groups".format(status.channels, groups)
                },
                style = Tv.meta,
            )
            Text(
                if (part == TvPart.SEARCH) "OK on Search to look for a channel, a film or a series by name."
                else "OK watches a channel · hold OK to put it in one of your groups · ▲ ▼ change channel while watching.",
                style = Tv.body,
            )
        } else {
        val (on, next) = remember(c.id, version, now) { app.iptv.nowNext(c.id, now) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogo(c, 26.dp)
            Text(if (c.num > 0) "${c.name} · ${c.num}" else c.name, style = Tv.text(12f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dot(LIVE_RED)
            Text("LIVE", style = Tv.text(10f, FontWeight.Bold, LIVE_RED, spacing = 1.5f))
        }
        Text(on?.title?.ifBlank { null } ?: c.name, style = Tv.heroTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        if (on != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${hm(context, on.start)} – ${hm(context, on.end)} · ${left(on, now)}", style = Tv.meta)
                Progress(on.fraction(now), Modifier.width(150.dp), height = 3.dp)
            }
            if (on.description.isNotBlank()) Text(on.description, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(if (app.iptv.guide(c.id) == null) "Looking for what's on…" else "No guide for this channel · OK to watch it", style = Tv.meta)
        }
        if (next != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text("NEXT ${hm(context, next.start)}", style = Tv.text(10f, FontWeight.Bold, Tv.ink2))
                Text(next.title, style = Tv.text(10f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        }
    }
}

/** The top of Films or Series: the focused one's details. */
@Composable
private fun VodHero(app: LauncherApp, hero: TvHero, part: TvPart, status: io.github.bornaradusin.launcher.iptv.IptvStatus) {
    Column(Modifier.padding(start = 48.dp, top = 75.dp, end = 48.dp).widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (hero) {
            is TvHero.Film -> {
                val f = hero.f
                val info by produceState<FilmInfo?>(app.iptv.cachedFilmInfo(f.id), f.id) {
                    delay(250)
                    value = app.iptv.filmInfo(f.id)
                }
                Text("FILM · FROM YOUR IPTV", style = Tv.kicker)
                Text(f.name, style = Tv.heroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val i = info
                val meta = listOfNotNull(
                    i?.year?.ifBlank { null },
                    i?.seconds?.let { minutes(it) }?.ifBlank { null },
                    (i?.rating?.takeIf { it > 0f } ?: f.rating.takeIf { it > 0f })?.let { "★ %.1f".format(it) },
                    i?.genre?.substringBefore(',')?.ifBlank { null },
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Tv.meta)
                if (!i?.plot.isNullOrBlank()) Text(i?.plot.orEmpty(), style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 6.dp)) {
                    val r = hero.resume
                    Label(if (r != null && r.duration > 0) "OK · Resume, ${((r.duration - r.position) / 60_000).coerceAtLeast(1)} min left" else "OK · Play", primary = true)
                    Label(if (i?.trailer != null) "Hold OK · trailer, from the start" else "Hold OK · from the start")
                }
            }
            is TvHero.Series -> {
                val s = hero.s
                Text("SERIES · FROM YOUR IPTV", style = Tv.kicker)
                Text(s.name, style = Tv.heroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(s.year.ifBlank { null }, s.rating.takeIf { it > 0f }?.let { "★ %.1f".format(it) }, s.genre.substringBefore(',').ifBlank { null })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Tv.meta)
                if (s.plot.isNotBlank()) Text(s.plot, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(modifier = Modifier.padding(top = 6.dp)) { Label("OK · seasons and episodes", primary = true) }
            }
            else -> {
                val films = part == TvPart.FILMS
                Text(if (films) "FILMS · FROM YOUR IPTV" else "SERIES · FROM YOUR IPTV", style = Tv.kicker)
                Text(if (films) "Films" else "Series", style = Tv.heroTitle)
                Text(
                    when {
                        films && status.filmsLoading -> "Loading the films · %,d so far".format(status.films)
                        !films && status.seriesLoading -> "Loading the series · %,d so far".format(status.series)
                        status.vodProblem != null && (if (films) status.films else status.series) == 0 -> status.vodProblem
                        films -> "%,d films".format(status.films)
                        else -> "%,d series".format(status.series)
                    },
                    style = Tv.meta,
                )
                Text("Played here in the launcher; where you stop is kept on the Shield.", style = Tv.body)
            }
        }
    }
}

@Composable
private fun Label(text: String, primary: Boolean = false) {
    Text(
        text, style = Tv.text(10.5f, FontWeight.SemiBold, if (primary) Tv.bg else Tv.ink),
        modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(if (primary) Tv.ink else Color(0x26FFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The rows under the top: their cards line up at the left and the focused row sits at the top. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Rows(memory: HomeMemory, top: androidx.compose.ui.unit.Dp = 312.dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val density = LocalDensity.current
    val bottom = with(density) { 16.dp.toPx() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides remember(bottom) { PinRow(0f, bottom) }) {
        LazyColumn(
            state = memory.column,
            contentPadding = PaddingValues(bottom = 200.dp),
            modifier = Modifier.padding(top = top).fillMaxSize(),
        ) {
            content()
        }
    }
}

/** Live TV: Recently watched, your groups, the provider's. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRows(
    app: LauncherApp,
    rows: List<TvRow>,
    memory: HomeMemory,
    now: Long,
    status: io.github.bornaradusin.launcher.iptv.IptvStatus,
    moving: Pair<String, String>?,
    onFocus: (XChannel, String) -> Unit,
    onWatch: (TvRow, XChannel) -> Unit,
    onHold: (XChannel, TvRow) -> Unit,
    onMoved: (Pair<String, String>?) -> Unit,
) {
    val density = LocalDensity.current
    val rowSpec = remember { PinStart(with(density) { 48.dp.toPx() }) }
    val scope = rememberCoroutineScope()
    if (rows.isEmpty()) {
        Text(
            when {
                status.liveLoading -> "Loading the channels…"
                status.problem != null -> status.problem
                else -> "No channels: Settings › IPTV to see how the account stands"
            },
            style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 322.dp),
        )
        return
    }
    Rows(memory) {
        items(rows, key = { it.id }) { row ->
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                val isMoving = moving != null && row.group?.id == moving.first
                RowFrame(row.title, if (isMoving) "Moving: ◀ ▶ to move it, OK to drop it there" else row.hint) {
                    LazyRow(
                        state = memory.row(row.id),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        items(row.channels, key = { it.id }) { c ->
                            val key = "${row.id}:${c.id}"
                            val thisMoving = isMoving && moving?.second == c.id
                            val moveKeys: (KeyEvent) -> Boolean = { e ->
                                when (e.key) {
                                    Key.DirectionLeft, Key.DirectionRight -> {
                                        if (e.isDown()) {
                                            val g = row.group
                                            if (g != null) {
                                                app.iptv.moveChannel(g.id, c.id, if (e.key == Key.DirectionLeft) -1 else 1)
                                                scope.launch {
                                                    delay(80)
                                                    runCatching { memory.requester(key).requestFocus() }
                                                }
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionUp, Key.DirectionDown -> true
                                    else -> false
                                }
                            }
                            ChannelCard(
                                app, c, now,
                                onClick = { if (thisMoving) onMoved(null) else onWatch(row, c) },
                                onLongClick = { if (!thisMoving) onHold(c, row) },
                                onFocus = { onFocus(c, key) },
                                focusRequester = memory.requester(key),
                                moving = thisMoving,
                                onKey = if (thisMoving) moveKeys else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Films: the ones you started, the newest, then each of the provider's groups. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilmRows(
    app: LauncherApp,
    films: List<XFilm>,
    categories: List<io.github.bornaradusin.launcher.iptv.XCategory>,
    hideAdult: Boolean,
    memory: HomeMemory,
    resumes: Int,
    onFocus: (XFilm, String) -> Unit,
    onPlay: (XFilm) -> Unit,
    onHold: (XFilm) -> Unit,
    loading: Boolean,
) {
    val density = LocalDensity.current
    val rowSpec = remember { PinStart(with(density) { 48.dp.toPx() }) }
    val groups = remember(films, categories, hideAdult) {
        val adult = categories.filter { it.adult }.map { it.id }.toSet()
        val shown = if (hideAdult) films.filter { !it.adult && it.category !in adult } else films
        val by = shown.groupBy { it.category }
        val list = ArrayList<Triple<String, String, List<XFilm>>>()
        list += Triple("new", "Recently added", shown.sortedByDescending { it.added }.take(40))
        categories.forEach { c -> by[c.id]?.takeIf { it.isNotEmpty() && (!hideAdult || !c.adult) }?.let { list += Triple("c:${c.id}", c.name, it) } }
        list
    }
    val started = remember(films, resumes) {
        val byId = films.associateBy { it.id }
        app.iptv.continueWatching().filter { it.key.startsWith("f:") }.mapNotNull { byId[it.key.removePrefix("f:")] }
    }
    if (films.isEmpty()) {
        Text(if (loading) "Loading the films…" else "No films from the provider", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 322.dp))
        return
    }
    Rows(memory) {
        if (started.isNotEmpty()) item(key = "started") {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                PosterRow(memory, "started", "Continue watching", "Films · on this Shield", started, onFocus, onPlay, onHold, tag = "RESUME")
            }
        }
        items(groups, key = { it.first }) { (id, title, list) ->
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                PosterRow(memory, id, title, if (id == "new") "Films · your provider" else "${list.size}", list, onFocus, onPlay, onHold)
            }
        }
    }
}

@Composable
private fun PosterRow(
    memory: HomeMemory,
    id: String,
    title: String,
    hint: String,
    list: List<XFilm>,
    onFocus: (XFilm, String) -> Unit,
    onPlay: (XFilm) -> Unit,
    onHold: (XFilm) -> Unit,
    tag: String? = null,
) {
    RowFrame(title, hint) {
        LazyRow(
            state = memory.row(id),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.id }) { f ->
                val key = "$id:${f.id}"
                PosterCard(
                    title = f.name, image = Pictures.allowed(f.poster),
                    onClick = { onPlay(f) }, onLongClick = { onHold(f) },
                    onFocus = { onFocus(f, key) }, focusRequester = memory.requester(key), tag = tag,
                )
            }
        }
    }
}

/** Series: the ones you're watching, the newest, then each of the provider's groups. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesRows(
    app: LauncherApp,
    series: List<XSeries>,
    categories: List<io.github.bornaradusin.launcher.iptv.XCategory>,
    hideAdult: Boolean,
    memory: HomeMemory,
    resumes: Int,
    onFocus: (XSeries, String) -> Unit,
    onOpen: (XSeries) -> Unit,
    onResume: (Resume) -> Unit,
    loading: Boolean,
) {
    val density = LocalDensity.current
    val rowSpec = remember { PinStart(with(density) { 48.dp.toPx() }) }
    val groups = remember(series, categories, hideAdult) {
        val adult = categories.filter { it.adult }.map { it.id }.toSet()
        val shown = if (hideAdult) series.filter { !it.adult && it.category !in adult } else series
        val by = shown.groupBy { it.category }
        val list = ArrayList<Triple<String, String, List<XSeries>>>()
        list += Triple("new", "Recently updated", shown.sortedByDescending { it.added }.take(40))
        categories.forEach { c -> by[c.id]?.takeIf { it.isNotEmpty() && (!hideAdult || !c.adult) }?.let { list += Triple("c:${c.id}", c.name, it) } }
        list
    }
    val watching = remember(series, resumes) {
        val byId = series.associateBy { it.id }
        app.iptv.continueWatching().filter { it.key.startsWith("e:") && it.seriesId != null }.mapNotNull { r -> byId[r.seriesId]?.let { it to r } }
    }
    if (series.isEmpty()) {
        Text(if (loading) "Loading the series…" else "No series from the provider", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 322.dp))
        return
    }
    Rows(memory) {
        if (watching.isNotEmpty()) item(key = "watching") {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                RowFrame("Continue watching", "Series · on this Shield") {
                    LazyRow(
                        state = memory.row("watching"),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(watching, key = { it.second.key }) { (s, r) ->
                            val key = "watching:${r.key}"
                            PosterCard(
                                title = r.title, image = Pictures.allowed(s.poster),
                                onClick = { onResume(r) }, onLongClick = { onOpen(s) },
                                onFocus = { onFocus(s, key) }, focusRequester = memory.requester(key),
                                tag = r.title.substringAfterLast(" · ", "").ifBlank { "RESUME" },
                            )
                        }
                    }
                }
            }
        }
        items(groups, key = { it.first }) { (id, title, list) ->
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                RowFrame(title, if (id == "new") "Series · your provider" else "${list.size}") {
                    LazyRow(
                        state = memory.row(id),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(list, key = { it.id }) { s ->
                            val key = "$id:${s.id}"
                            PosterCard(
                                title = s.name, image = Pictures.allowed(s.poster),
                                onClick = { onOpen(s) }, onLongClick = { onOpen(s) },
                                onFocus = { onFocus(s, key) }, focusRequester = memory.requester(key),
                            )
                        }
                    }
                }
            }
        }
    }
}

fun searchChannels(live: io.github.bornaradusin.launcher.iptv.LiveCatalog, q: String, hideAdult: Boolean): List<XChannel> {
    if (q.isBlank()) return emptyList()
    val adult = live.categories.filter { it.adult }.map { it.id }.toSet()
    return live.channels.asSequence().filter { it.name.contains(q, true) && (!hideAdult || (!it.adult && it.category !in adult)) }
        .distinctBy { it.id }.take(80).toList()
}

/** Search: channels, films and series whose names have the words in them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchRows(
    app: LauncherApp,
    query: String,
    live: io.github.bornaradusin.launcher.iptv.LiveCatalog,
    films: List<XFilm>,
    series: List<XSeries>,
    filmsOn: Boolean,
    seriesOn: Boolean,
    hideAdult: Boolean,
    memory: HomeMemory,
    now: Long,
    onChannelFocus: (XChannel, String) -> Unit,
    onWatch: (XChannel) -> Unit,
    onHold: (XChannel) -> Unit,
    onFilmFocus: (XFilm, String) -> Unit,
    onFilm: (XFilm) -> Unit,
    onFilmHold: (XFilm) -> Unit,
    onSeries: (XSeries) -> Unit,
) {
    val density = LocalDensity.current
    val rowSpec = remember { PinStart(with(density) { 48.dp.toPx() }) }
    val channels = remember(query, live, hideAdult) { searchChannels(live, query, hideAdult) }
    val foundFilms = remember(query, films, hideAdult) {
        if (query.isBlank() || !filmsOn) emptyList() else films.asSequence().filter { it.name.contains(query, true) && (!hideAdult || !it.adult) }.take(80).toList()
    }
    val foundSeries = remember(query, series, hideAdult) {
        if (query.isBlank() || !seriesOn) emptyList() else series.asSequence().filter { it.name.contains(query, true) && (!hideAdult || !it.adult) }.take(80).toList()
    }
    if (query.isBlank()) return
    if (channels.isEmpty() && foundFilms.isEmpty() && foundSeries.isEmpty()) {
        Text("Nothing called “$query”", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 322.dp))
        return
    }
    Rows(memory) {
        if (channels.isNotEmpty()) item(key = "channels") {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                RowFrame("Channels", "${channels.size}${if (channels.size == 80) "+" else ""}") {
                    LazyRow(state = memory.row("channels"), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        items(channels, key = { it.id }) { c ->
                            val key = "channels:${c.id}"
                            ChannelCard(app, c, now, onClick = { onWatch(c) }, onLongClick = { onHold(c) }, onFocus = { onChannelFocus(c, key) }, focusRequester = memory.requester(key))
                        }
                    }
                }
            }
        }
        if (foundFilms.isNotEmpty()) item(key = "films") {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                PosterRow(memory, "films", "Films", "${foundFilms.size}", foundFilms, onFilmFocus, onFilm, onFilmHold)
            }
        }
        if (foundSeries.isNotEmpty()) item(key = "series") {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                RowFrame("Series", "${foundSeries.size}") {
                    LazyRow(state = memory.row("series"), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(foundSeries, key = { it.id }) { s ->
                            val key = "series:${s.id}"
                            PosterCard(
                                title = s.name, image = Pictures.allowed(s.poster), onClick = { onSeries(s) }, onLongClick = { onSeries(s) },
                                onFocus = { memory.focusedKey = key }, focusRequester = memory.requester(key),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A series: its seasons and their episodes, each with how far you got. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesPage(app: LauncherApp, s: XSeries, memory: TvMemory, actions: TvActions, onClose: () -> Unit) {
    val info by produceState<SeriesInfo?>(null, s.id) { value = app.iptv.seriesInfo(s.id) }
    val resumes by app.iptv.resumeVersion.collectAsState()
    var season by remember(s.id) { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf<io.github.bornaradusin.launcher.iptv.Episode?>(null) }
    val scope = rememberCoroutineScope()
    val m = memory.episodes
    BackHandler(enabled = menu == null, onBack = onClose)
    val i = info
    // Start on the season of the episode you were on
    LaunchedEffect(i) {
        if (i == null || season != null) return@LaunchedEffect
        val last = app.iptv.continueWatching().firstOrNull { it.seriesId == s.id }
        val ep = last?.let { r -> i.episodes.firstOrNull { "e:${it.id}" == r.key } }
        season = ep?.season ?: i.seasons.firstOrNull()
        delay(80)
        val target = ep ?: i.episodes.firstOrNull { it.season == season && !app.iptv.isWatched("e:${it.id}") } ?: i.episodes.firstOrNull { it.season == season }
        target?.let { runCatching { m.requester("ep:${it.id}").requestFocus() } }
    }
    CompositionLocalProvider(LocalTvEnabled provides (menu == null)) {
        Column(Modifier.padding(start = 48.dp, top = 75.dp, end = 48.dp).widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("SERIES · FROM YOUR IPTV", style = Tv.kicker)
            Text(s.name, style = Tv.heroTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                (i?.years?.ifBlank { null } ?: s.year.ifBlank { null }),
                i?.seasons?.size?.takeIf { it > 0 }?.let { "$it season${if (it == 1) "" else "s"}" },
                (i?.rating?.takeIf { it > 0f } ?: s.rating.takeIf { it > 0f })?.let { "★ %.1f".format(it) },
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Tv.meta)
            val plot = i?.plot?.ifBlank { null } ?: s.plot
            if (plot.isNotBlank()) Text(plot, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (i == null) {
            Text("Getting the episodes…", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 250.dp))
        } else if (i.episodes.isEmpty()) {
            Text("The provider has no episodes for this series", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 250.dp))
        } else {
        LazyRow(
            Modifier.padding(top = 228.dp),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(i.seasons, key = { it }) { n ->
                SeasonPill("Season $n", selected = season == n, onSelect = { season = n }, focusRequester = m.requester("season:$n"))
            }
        }
        val episodes = i.episodes.filter { it.season == season }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember { PinStart(with(density) { 48.dp.toPx() }) }) {
            LazyRow(
                Modifier.padding(top = 268.dp),
                state = m.row("season:$season"),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                items(episodes, key = { it.id }) { e ->
                    val key = "e:${e.id}"
                    val r = remember(e.id, resumes) { app.iptv.resume(key) }
                    val done = remember(e.id, resumes) { app.iptv.isWatched(key) }
                    Column(Modifier.width(165.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        FocusCard(
                            165.dp, 92.dp, 10.dp,
                            onClick = { actions.watch(Watch.Episodes(s, i.episodes, i.episodes.indexOf(e), fromStart = false)) },
                            onLongClick = { menu = e },
                            onFocus = { m.focusedKey = "ep:${e.id}" },
                            focusRequester = m.requester("ep:${e.id}"),
                        ) {
                            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0D1119))))) {
                                AsyncImage(
                                    Pictures.allowed(e.image) ?: Pictures.allowed(i.backdrop) ?: Pictures.allowed(s.backdrop), null,
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                )
                                val fraction = when {
                                    done -> 1f
                                    r != null && r.duration > 0 -> r.position.toFloat() / r.duration
                                    else -> 0f
                                }
                                if (fraction > 0f) Progress(fraction, Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 3.dp, track = Color(0x66000000))
                            }
                        }
                        Text("${e.number}. ${e.title}", style = Tv.text(10.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            listOfNotNull(
                                minutes(e.seconds).ifBlank { null },
                                when {
                                    done -> "watched"
                                    r != null && r.duration > 0 -> "${((r.duration - r.position) / 60_000).coerceAtLeast(1)} min left"
                                    else -> null
                                },
                            ).joinToString(" · "),
                            style = Tv.text(8.5f, color = Tv.muted),
                        )
                    }
                }
            }
        }
        Text(
            "OK plays from where you left off · hold OK to start it again · played here in the launcher; where you stopped is kept on the Shield",
            style = Tv.hint, modifier = Modifier.padding(start = 48.dp, top = 455.dp),
        )
        }
    }
    menu?.let { e ->
        val key = "e:${e.id}"
        ChoicePanel(
            "${e.number}. ${e.title}",
            listOfNotNull(
                MenuItem(Ic.restart, "Play from the beginning") {
                    menu = null
                    i?.let { actions.watch(Watch.Episodes(s, it.episodes, it.episodes.indexOf(e), fromStart = true)) }
                },
                if (app.iptv.resume(key) != null || app.iptv.isWatched(key)) MenuItem(Ic.trash, "Mark as not watched") {
                    menu = null
                    app.iptv.clearResume(key)
                    scope.launch {
                        delay(60)
                        runCatching { m.requester("ep:${e.id}").requestFocus() }
                    }
                } else null,
            ),
            onClose = { menu = null },
        )
    }
}

@Composable
private fun SeasonPill(text: String, selected: Boolean, onSelect: () -> Unit, focusRequester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.height(23.dp).clip(RoundedCornerShape(11.5.dp))
            .background(if (focused) Tv.ink else if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .tvItem(onClick = onSelect, onFocus = onSelect, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Tv.text(10f, FontWeight.SemiBold, if (focused) Tv.bg else if (selected) Tv.ink else Tv.ink2))
    }
}
