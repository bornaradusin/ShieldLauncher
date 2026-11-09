package io.github.bornaradusin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.emby.EmbyItem
import io.github.bornaradusin.launcher.seerr.Download
import io.github.bornaradusin.launcher.seerr.MediaState
import io.github.bornaradusin.launcher.seerr.SeerrDetails
import io.github.bornaradusin.launcher.seerr.SeerrException
import io.github.bornaradusin.launcher.seerr.SeerrRow
import io.github.bornaradusin.launcher.seerr.SeerrTitle
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Things Requester asks of the rest of the launcher. */
class RequesterActions(
    val openSettings: () -> Unit,
    val openTrailer: (TrailerRequest) -> Unit,
    val toast: (String) -> Unit,
)

private val AMBER = Color(0xFFF5C26B)

/**
 * A title's badge and line: on Emby, waiting for approval, approved but not downloading yet (Radarr or
 * Sonarr is still looking for it), downloading (how far, how long left), turned down, or nothing yet.
 */
private fun stateLook(s: MediaState, d: Download? = null): Triple<Color, ImageVector, String>? = when (s) {
    MediaState.AVAILABLE -> Triple(Tv.ok, Ic.check, "On Emby")
    MediaState.PARTLY -> Triple(Tv.ok, Ic.check, if (d != null) "Partly on Emby · the rest ${downloading(d)}" else "Partly on Emby")
    MediaState.PENDING -> Triple(AMBER, Ic.clock, "Requested · waiting for approval")
    MediaState.PROCESSING -> Triple(Tv.accent, Ic.search, "Approved · not downloading yet")
    MediaState.DOWNLOADING -> Triple(Tv.accent, Ic.download, downloading(d).replaceFirstChar { it.uppercase() })
    MediaState.DECLINED -> Triple(Tv.bad, Ic.close, "Request turned down")
    MediaState.NONE -> null
}

private fun downloading(d: Download?): String = when {
    d == null -> "downloading"
    d.minutesLeft != null && d.minutesLeft > 0 -> "downloading · ${(d.progress * 100).toInt()}% · ${if (d.minutesLeft >= 60) "${d.minutesLeft / 60} h ${d.minutesLeft % 60} min" else "${d.minutesLeft} min"} left"
    else -> "downloading · ${(d.progress * 100).toInt()}%"
}

private val SeerrTitle.onEmby get() = state == MediaState.AVAILABLE || state == MediaState.PARTLY

/**
 * Requester: Seerr's trending, popular and coming-soon films and shows, your requests, and your films
 * and shows on Emby. OK opens a title to request it (picking seasons for a show), play it or delete
 * it; holding OK deletes one that's on Emby, or plays the trailer of one that isn't.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RequesterScreen(app: LauncherApp, memory: HomeMemory, actions: RequesterActions, onTab: (Tab) -> Unit, startOnTabs: Boolean) {
    val config by app.store.config.collectAsState()
    val weather by app.weather.now.collectAsState()
    val update by app.updater.state.collectAsState()
    val gone by app.requester.gone.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        app.requester.opened()
        // Downloads move along while Requester is showing
        while (true) {
            delay(90_000)
            app.requester.refresh()
        }
    }

    var hero by remember { mutableStateOf<Pair<SeerrTitle, SeerrRow>?>(null) }
    var shown by remember { mutableStateOf<Pair<SeerrTitle, SeerrRow>?>(null) }
    LaunchedEffect(hero) {
        delay(140)
        shown = hero
    }
    var open by remember { mutableStateOf<SeerrTitle?>(null) }
    var deleting by remember { mutableStateOf<SeerrTitle?>(null) }
    var searching by remember { mutableStateOf(false) }
    val overlay = open != null || deleting != null || searching

    val tabFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        val target = if (startOnTabs) tabFocus else memory.focusedKey?.let { memory.requesters[it] } ?: searchFocus
        if (runCatching { target.requestFocus() }.isFailure) runCatching { searchFocus.requestFocus() }
    }

    fun focusKey(key: String?) {
        scope.launch {
            delay(60)
            runCatching { (key?.let { memory.requesters[it] } ?: searchFocus).requestFocus() }
        }
    }

    // Holding OK: delete one that's on Emby, else its trailer
    fun hold(t: SeerrTitle) {
        if (t.onEmby) {
            if (config.seerr == null) actions.toast("Connect Seerr from your phone to delete from the server")
            else deleting = t
            return
        }
        val a = config.seerr ?: return
        scope.launch {
            val link = runCatching { app.seerr.details(a, t.tmdbId, t.movie).trailer }.getOrNull()
            if (link == null) actions.toast("There's no trailer for ${t.title}")
            else actions.openTrailer(TrailerRequest(t.title, null, null, link) { app.trailers.fromYouTube(link) })
        }
    }

    Box(Modifier.fillMaxSize().background(Tv.bg)) {
        BackdropImage(shown?.first?.backdrop, null, false, {}, {})

        CompositionLocalProvider(LocalTvEnabled provides !overlay) {
            TopBar(
                tab = Tab.REQUESTER,
                onTab = onTab,
                tabFocus = tabFocus,
                small = true,
                weather = weather,
                update = (update as? UpdateState.Available)?.update?.version,
                // The update's note and progress are on Home
                onUpdate = { onTab(Tab.HOME) },
                onSearch = { searching = true },
                onSettings = actions.openSettings,
            )
            TitleText(shown)

            val pivot = with(density) { 128.dp.toPx() }
            val bottom = with(density) { 16.dp.toPx() }
            val start = with(density) { 48.dp.toPx() }
            val rowSpec = remember(start) { PinStart(start) }
            CompositionLocalProvider(LocalBringIntoViewSpec provides remember(pivot) { PinRow(pivot, bottom) }) {
                LazyColumn(
                    state = memory.column,
                    contentPadding = PaddingValues(bottom = 160.dp),
                    modifier = Modifier
                        .padding(top = 232.dp)
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black), blendMode = BlendMode.DstIn)
                        },
                ) {
                    item(key = "search") {
                        SearchPill(searchFocus, onClick = { searching = true }, onFocus = { memory.focusedKey = null })
                    }
                    SeerrRow.entries.forEach { row ->
                        item(key = row.name) {
                            val state by app.requester.rows.getValue(row).collectAsState()
                            val list = state.items.filter { it.key !in gone }
                            val hint = if (row == SeerrRow.FILMS || row == SeerrRow.SHOWS) {
                                if (state.total > 0) "On Emby · ${state.total} · hold OK to delete one" else "On Emby"
                            } else if (row == SeerrRow.REQUESTS && list.isNotEmpty()) {
                                val waiting = list.count { it.state == MediaState.PENDING }
                                "${list.size}" + if (waiting > 0) " · $waiting waiting" else ""
                            } else row.hint
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                CardRow(
                                    row.name, row.title, hint, list, state.loaded, state.error, "Nothing here just now", memory,
                                    tall = true, keyOf = { it.key },
                                ) { t, key ->
                                    val look = stateLook(t.state, t.download)
                                    PosterCard(
                                        title = t.title,
                                        image = t.poster,
                                        onClick = { open = t },
                                        onLongClick = { hold(t) },
                                        onFocus = {
                                            hero = t to row
                                            memory.focusedKey = key
                                        },
                                        focusRequester = memory.requester(key),
                                        badgeColor = look?.first,
                                        badgeIcon = look?.second,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (searching) {
            BackHandler(enabled = open == null && deleting == null) {
                searching = false
                focusKey("search-pill")
            }
            CompositionLocalProvider(LocalTvEnabled provides (open == null && deleting == null)) {
                SearchPanel(app, onOpen = { open = it }, onHold = { hold(it) })
            }
        }

        open?.let { t ->
            BackHandler { open = null; if (!searching) focusKey(memory.focusedKey) }
            CompositionLocalProvider(LocalTvEnabled provides (deleting == null)) {
                TitleDialog(
                    app, t,
                    onClose = { open = null; if (!searching) focusKey(memory.focusedKey) },
                    onDelete = { deleting = it },
                    onTrailer = { link, d ->
                        actions.openTrailer(TrailerRequest(d.title.title, null, null, link) { app.trailers.fromYouTube(link) })
                    },
                    toast = actions.toast,
                )
            }
        }

        deleting?.let { t ->
            BackHandler { deleting = null }
            DeleteDialog(
                t,
                onKeep = { deleting = null },
                onDelete = {
                    deleting = null
                    open = null
                    val a = config.seerr ?: return@DeleteDialog
                    scope.launch {
                        try {
                            app.seerr.delete(a, t)
                            app.requester.deleted(t)
                            actions.toast("${t.title} is deleted from the server: it leaves Emby at its next library scan")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: SeerrException) {
                            actions.toast(e.message ?: "Seerr couldn't delete it")
                        } catch (e: Exception) {
                            actions.toast("Couldn't reach Seerr to delete it")
                        }
                        focusKey(memory.focusedKey)
                    }
                },
            )
        }
    }
}

/** "Search films and shows": the first thing on Requester. */
@Composable
private fun SearchPill(focus: FocusRequester, onClick: () -> Unit, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 48.dp, top = 6.dp, bottom = 14.dp).width(320.dp).height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Tv.ink else Color(0x14FFFFFF))
            .border(1.dp, if (focused) Tv.ink else Tv.line, RoundedCornerShape(16.dp))
            .tvItem(onClick = onClick, focusRequester = focus, onFocus = onFocus, onFocusChange = { focused = it })
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ic.search, null, Modifier.size(13.dp), tint = if (focused) Tv.bg else Tv.muted)
        Text("Search films and shows", style = Tv.text(11f, color = if (focused) Tv.bg else Tv.muted), modifier = Modifier.weight(1f))
    }
}

/** The words at the top: about the focused title. */
@Composable
private fun TitleText(shown: Pair<SeerrTitle, SeerrRow>?) {
    Box(Modifier.padding(start = 48.dp, top = 84.dp).width(430.dp).height(146.dp)) {
        Crossfade(targetState = shown, animationSpec = tween(250), label = "title") { s ->
            if (s == null) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("REQUESTER · SEERR", style = Tv.kicker)
                    Text("Find something to watch", style = Tv.heroTitle, maxLines = 1)
                    Text("Ask for a film or a show and it turns up on Emby. Your films and shows are here too, to tidy up.", style = Tv.body, maxLines = 2)
                }
            } else {
                val (t, row) = s
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kind = if (t.movie) "MOVIE" else "TV SHOW"
                    val where = when (row) {
                        SeerrRow.TRENDING -> "TRENDING ON SEERR"
                        SeerrRow.REQUESTS -> "YOUR REQUEST"
                        SeerrRow.FILMS, SeerrRow.SHOWS -> "ON EMBY"
                        SeerrRow.POPULAR_FILMS, SeerrRow.POPULAR_SHOWS -> "POPULAR"
                        SeerrRow.UPCOMING_FILMS, SeerrRow.UPCOMING_SHOWS -> "COMING SOON"
                    }
                    Text("$kind · $where", style = Tv.kicker)
                    Text(t.title, style = Tv.heroTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(t.year, if (t.rating > 0) "★ %.1f".format(t.rating) else null).joinToString("  ·  "), style = Tv.meta)
                    StateChip(t.state, t.download)
                    if (t.overview.isNotBlank()) Text(t.overview, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (t.onEmby) "OK for details and Play on Emby  ·  hold OK to delete it from the server"
                        else "OK to request it  ·  hold OK for the trailer",
                        style = Tv.hint,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateChip(s: MediaState, download: Download? = null, big: Boolean = false) {
    val look = stateLook(s, download)
    val color = look?.first ?: Tv.muted
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x8C06080C)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = if (big) 10.dp else 8.dp, vertical = if (big) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(look?.second ?: Ic.plus, null, Modifier.size(if (big) 12.dp else 10.dp), tint = color)
        Text(look?.third ?: "Not requested", style = Tv.text(if (big) 11f else 9.5f, FontWeight.SemiBold, color))
    }
}

/**
 * A title's details: request it (a show: pick the seasons), play it on Emby, its trailer, or delete
 * it from the server.
 */
@Composable
private fun TitleDialog(
    app: LauncherApp,
    t: SeerrTitle,
    onClose: () -> Unit,
    onDelete: (SeerrTitle) -> Unit,
    onTrailer: (String, SeerrDetails) -> Unit,
    toast: (String) -> Unit,
) {
    val config by app.store.config.collectAsState()
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<SeerrDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val first = remember { FocusRequester() }

    LaunchedEffect(t.key) {
        val a = config.seerr
        if (a == null) {
            error = "Connect Seerr from your phone: Settings › Set up from your phone"
        } else if (t.tmdbId <= 0) {
            error = "Emby has no TMDB id for this one, so Seerr can't find it"
        } else {
            try {
                details = app.seerr.details(a, t.tmdbId, t.movie, fresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? SeerrException)?.message ?: "Couldn't reach Seerr"
            }
        }
        delay(60)
        runCatching { first.requestFocus() }
    }

    val d = details
    val title = d?.title?.copy(embyId = d.title.embyId ?: t.embyId) ?: t
    val state = title.state

    fun ask(seasons: List<Int>) {
        val a = config.seerr ?: return
        busy = true
        scope.launch {
            try {
                app.seerr.request(a, title, seasons)
                app.requester.requested()
                toast(if (title.movie) "Asked Seerr for ${title.title}" else "Asked Seerr for ${seasons.size} season${if (seasons.size == 1) "" else "s"} of ${title.title}")
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast((e as? SeerrException)?.message ?: "Couldn't reach Seerr")
                busy = false
            }
        }
    }

    fun playOnEmby() {
        val id = title.embyId
        val emby = config.emby
        if (id == null || emby == null) return toast("Sign in to Emby from your phone to play it")
        if (app.embyLauncher.app(config.embyApp) == null) return toast("Install the Emby app to play")
        scope.launch { app.embyLauncher.play(emby, EmbyItem.stub(id, !title.movie, title.title), config.embyApp) }
        onClose()
    }

    Box(Modifier.fillMaxSize().background(Color(0xC704060A))) {
        Row(
            Modifier.align(Alignment.Center).width(if (title.movie) 700.dp else 800.dp).heightIn(max = 470.dp)
                .shadow(60.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Tv.panel).border(1.dp, Tv.line, RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(Modifier.width(if (title.movie) 200.dp else 170.dp).height(if (title.movie) 300.dp else 255.dp).clip(RoundedCornerShape(11.dp)).background(Tv.off)) {
                AsyncImage(title.poster, title.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (title.movie) "MOVIE" else "TV SHOW") + (title.year?.let { " · $it" } ?: ""), style = Tv.kicker)
                Text(title.title, style = Tv.text(28f, FontWeight.Bold, lineHeight = 31f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d != null) {
                    val meta = listOfNotNull(
                        d.runtime.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                        d.seasons.size.takeIf { !title.movie && it > 0 }?.let { "$it season${if (it == 1) "" else "s"}" },
                        d.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
                        if (title.rating > 0) "★ %.1f".format(title.rating) else null,
                    ).joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, style = Tv.text(11f, color = Tv.ink2))
                }
                StateChip(state, title.download, big = true)
                when {
                    error != null -> Text(error.orEmpty(), style = Tv.text(11f, color = Tv.bad))
                    d == null -> Text("Asking Seerr…", style = Tv.text(11f, color = Tv.muted))
                    else -> {
                        if (title.overview.isNotBlank()) {
                            Text(title.overview, style = Tv.text(10.5f, color = Tv.muted, lineHeight = 15f), maxLines = if (title.movie) 5 else 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!title.movie && d.seasons.isNotEmpty()) {
                            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 170.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(d.seasons, key = { it.number }) { s ->
                                    SeasonRow(
                                        name = s.name, episodes = s.episodes, state = s.state, download = s.download, requestable = s.requestable,
                                        on = s.number in picked,
                                        onClick = { if (s.requestable) picked = if (s.number in picked) picked - s.number else picked + s.number },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var firstUsed = false
                    fun firstOnce(): FocusRequester? = if (firstUsed) null else first.also { firstUsed = true }
                    if (d != null && !busy) {
                        if (title.movie && (state == MediaState.NONE || state == MediaState.DECLINED)) {
                            PillButton("Request", { ask(emptyList()) }, icon = Ic.download, primary = true, focusRequester = firstOnce())
                        }
                        if (!title.movie) {
                            val missing = d.seasons.filter { it.requestable }.map { it.number }
                            if (missing.isNotEmpty()) {
                                if (picked.isNotEmpty()) {
                                    PillButton(
                                        "Request ${picked.size} season${if (picked.size == 1) "" else "s"}", { ask(picked.sorted()) },
                                        icon = Ic.download, primary = true,
                                    )
                                }
                                PillButton(if (missing.size == d.seasons.size) "All seasons" else "All missing seasons", { ask(missing) }, icon = Ic.plus, primary = picked.isEmpty())
                            }
                        }
                        if (title.embyId != null && config.emby != null) {
                            PillButton(if (title.movie) "Play on Emby" else "Open on Emby", { playOnEmby() }, icon = Ic.play, primary = title.onEmby, focusRequester = firstOnce())
                        }
                        d.trailer?.let { link -> PillButton("Trailer", { onTrailer(link, d) }, icon = Ic.film) }
                        if (title.onEmby) DangerButton("Delete", { onDelete(title) })
                    }
                    PillButton("Back", onClose, icon = Ic.back, focusRequester = firstOnce())
                }
                if (d != null && (state == MediaState.NONE || state == MediaState.DECLINED || (!title.movie && d.seasons.any { it.requestable }))) {
                    Text(
                        "Goes to Seerr as ${config.seerr?.userName ?: "you"}'s request · it shows on Home once it's on Emby" +
                            if (!title.movie) "  ·  OK on a season turns it on or off" else "",
                        style = Tv.text(9f, color = Tv.muted),
                    )
                }
            }
        }
    }
}

/** A season: where it stands, or (if it can be asked for) a switch. */
@Composable
private fun SeasonRow(name: String, episodes: Int, state: MediaState, download: Download?, requestable: Boolean, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x0AFFFFFF))
            .border(if (focused) 1.5.dp else 0.dp, if (focused) Tv.ink else Color.Transparent, RoundedCornerShape(8.dp))
            .tvItem(onClick = onClick, onFocusChange = { focused = it })
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = Tv.text(11f, FontWeight.SemiBold, if (requestable) Tv.ink else Tv.ink2))
            if (episodes > 0) Text("$episodes episodes", style = Tv.text(8f, color = Tv.muted))
        }
        if (requestable) Switch(on) else stateLook(state, download)?.let { (c, icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(10.dp), tint = c)
                Text(text, style = Tv.text(9f, FontWeight.SemiBold, c))
            }
        }
    }
}

/** "Delete … ?": Keep it is focused, so a stray OK doesn't delete anything. */
@Composable
private fun DeleteDialog(t: SeerrTitle, onKeep: () -> Unit, onDelete: () -> Unit) {
    val keep = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { keep.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xD904060A))) {
        Row(
            Modifier.align(Alignment.Center).width(500.dp).shadow(60.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Tv.panel).border(1.dp, Tv.line, RoundedCornerShape(16.dp)).padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(9.dp)).background(Tv.off)) {
                AsyncImage(t.poster, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Ic.warn, null, Modifier.size(11.dp), tint = Tv.bad)
                    Text("DELETE FROM THE SERVER", style = Tv.text(10f, FontWeight.SemiBold, Tv.bad, spacing = 1.5f))
                }
                Text("Delete ${t.title}?", style = Tv.text(22f, FontWeight.Bold, lineHeight = 25f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Seerr has ${if (t.movie) "Radarr delete the film" else "Sonarr delete the show"} and its files. It leaves Emby at Emby's next library scan, and can be requested again later. This can't be undone.",
                    style = Tv.text(10.5f, color = Tv.muted, lineHeight = 15f),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Keep it", onKeep, icon = Ic.back, focusRequester = keep)
                    DangerButton("Delete", onDelete)
                }
            }
        }
    }
}

/** A red button, for deleting. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (focused) Tv.bad else Color(0x29FF8A80))
            .tvItem(onClick = onClick, onFocusChange = { focused = it }).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Ic.trash, null, Modifier.size(13.dp), tint = if (focused) Tv.bg else Tv.bad)
        Text(text, style = Tv.text(12f, FontWeight.SemiBold, if (focused) Tv.bg else Tv.bad))
    }
}

/** Search Seerr: Android's keyboard comes up (its mic key works too); the results update as you type. */
@Composable
private fun SearchPanel(app: LauncherApp, onOpen: (SeerrTitle) -> Unit, onHold: (SeerrTitle) -> Unit) {
    val config by app.store.config.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SeerrTitle>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusedTitle by remember { mutableStateOf<SeerrTitle?>(null) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            message = null
            return@LaunchedEffect
        }
        delay(400)
        val a = config.seerr ?: run {
            message = "Connect Seerr from your phone to search"
            return@LaunchedEffect
        }
        try {
            results = app.seerr.search(a, q)
            message = if (results.isEmpty()) "Nothing found for “$q”" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = (e as? SeerrException)?.message ?: "Couldn't reach Seerr"
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B101B), Tv.bg)))
            .background(Brush.radialGradient(listOf(Color(0x297CC4FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1540f, 160f), radius = 800f)),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 70.dp)) {
            Row(
                Modifier.padding(horizontal = 48.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp))
                    .background(Color(0x1AFFFFFF))
                    .border(if (fieldFocused) 1.5.dp else 1.dp, if (fieldFocused) Tv.ink else Tv.line, RoundedCornerShape(21.dp))
                    .padding(horizontal = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Ic.search, null, Modifier.size(16.dp), tint = Tv.ink)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search films and shows", style = Tv.text(17f, color = Tv.muted))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Tv.text(17f, FontWeight.Medium),
                        cursorBrush = SolidColor(Tv.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            runCatching { firstResult.requestFocus() }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused },
                    )
                }
                Text("Android's keyboard · its mic works too", style = Tv.text(9.5f, color = Tv.muted))
                Icon(Ic.mic, null, Modifier.size(14.dp), tint = Tv.muted)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Films and shows", style = Tv.rowTitle)
                if (results.isNotEmpty()) Text("${results.size} found", style = Tv.rowHint)
            }
            if (results.isEmpty()) {
                Text(
                    message ?: if (query.trim().length < 2) "Type a name: results show as you type" else "Searching…",
                    style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(results, key = { it.key }) { t ->
                        val look = stateLook(t.state, t.download)
                        PosterCard(
                            title = t.title, image = t.poster,
                            onClick = { onOpen(t) },
                            onLongClick = { onHold(t) },
                            onFocus = { focusedTitle = t },
                            focusRequester = if (t == results.first()) firstResult else null,
                            badgeColor = look?.first, badgeIcon = look?.second,
                        )
                    }
                }
                focusedTitle?.takeIf { it in results }?.let { t ->
                    Column(Modifier.padding(start = 48.dp, top = 8.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text((if (t.movie) "MOVIE" else "TV SHOW") + (t.year?.let { " · $it" } ?: ""), style = Tv.kicker)
                        Text(t.title, style = Tv.text(26f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StateChip(t.state, t.download)
                        if (t.overview.isNotBlank()) Text(t.overview, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (t.onEmby) "OK for details and Play on Emby" else if (t.movie) "OK to request it" else "OK to pick seasons and request it", style = Tv.hint)
                    }
                }
            }
        }
    }
}
