package io.github.bornaradusin.launcher.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bornaradusin.launcher.Game
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.RowKind
import io.github.bornaradusin.launcher.apps.AppEntry
import io.github.bornaradusin.launcher.emby.EmbyItem
import io.github.bornaradusin.launcher.trailer.TrailerMedia
import io.github.bornaradusin.launcher.update.UpdateState
import io.github.bornaradusin.launcher.weather.Sky
import io.github.bornaradusin.launcher.weather.WeatherNow
import io.github.bornaradusin.launcher.channels.AppChannel
import io.github.bornaradusin.launcher.channels.ChannelProgram
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the top of Home is about: the focused card. */
sealed interface Hero {
    data object Clock : Hero
    data class Media(val item: EmbyItem, val kind: RowKind) : Hero
    data class Play(val game: Game, val source: String) : Hero
    data class Program(val program: ChannelProgram, val channel: AppChannel) : Hero
}

/** Things Home asks of the rest of the launcher. */
class HomeActions(
    val openSettings: () -> Unit,
    val openTrailer: (EmbyItem) -> Unit,
    val toast: (String) -> Unit,
)

/** Kept while Home isn't showing (Settings, a trailer), so it comes back as it was left. */
class HomeMemory {
    val column = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun row(id: String) = rows.getOrPut(id) { LazyListState() }
    var focusedKey: String? = null
    /** Home presses already acted on, so coming back from Settings doesn't count an old one again. */
    var handledPresses = 0
    val requesters = HashMap<String, FocusRequester>()
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(app: LauncherApp, memory: HomeMemory, actions: HomeActions, homePresses: Int, onTab: (Tab) -> Unit, startOnTabs: Boolean) {
    val config by app.store.config.collectAsState()
    val allApps by app.apps.all.collectAsState()
    val appsLoaded by app.apps.loaded.collectAsState()
    val cw by app.feed.continueWatching.collectAsState()
    val movies by app.feed.movies.collectAsState()
    val shows by app.feed.shows.collectAsState()
    val channels by app.channels.channels.collectAsState()
    val weather by app.weather.now.collectAsState()
    val update by app.updater.state.collectAsState()
    val prompt by app.updater.prompt.collectAsState()
    val updated by app.updater.updated.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // The apps row the first time: the usual TV apps that are installed
    LaunchedEffect(appsLoaded, config.apps == null) {
        if (appsLoaded && config.apps == null) app.store.setApps(app.apps.firstRow())
    }

    var hero by remember { mutableStateOf<Hero>(Hero.Clock) }
    // The colour behind the apps row: the focused app's
    var appGlow by remember { mutableStateOf(Tv.accent) }
    var shown by remember { mutableStateOf<Hero>(Hero.Clock) }
    // The picture follows focus once it stops for a moment, so scrolling past doesn't load them all
    LaunchedEffect(hero) {
        delay(140)
        shown = hero
    }

    // Menus over Home
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    var picker by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<EmbyItem?>(null) }
    var gameMenu by remember { mutableStateOf<Game?>(null) }
    var updateOpen by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var movingRow by remember { mutableStateOf<List<String>>(emptyList()) }
    var movingBefore by remember { mutableStateOf<List<String>>(emptyList()) }
    val noteUp = prompt && update is UpdateState.Available && !updateOpen
    val dialogUp = updateOpen && (update is UpdateState.Downloading || update is UpdateState.NeedsPermission || update is UpdateState.Failed || update is UpdateState.Checking)
    val overlay = appMenu != null || picker || itemMenu != null || gameMenu != null || noteUp || dialogUp

    // The app being moved stays in sight as it goes along the row
    fun keepVisible(state: LazyListState, index: Int) {
        val info = state.layoutInfo
        val seen = info.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        if (!seen) scope.launch { state.animateScrollToItem((index - 3).coerceAtLeast(0)) }
    }

    fun focusKey(key: String?) {
        val k = key ?: return
        scope.launch {
            delay(60)
            runCatching { memory.requesters[k]?.requestFocus() }
        }
    }

    // Home pressed while on Home: back to the start of the top row
    LaunchedEffect(homePresses) {
        if (homePresses <= memory.handledPresses) return@LaunchedEffect
        memory.handledPresses = homePresses
        appMenu = null; picker = false; itemMenu = null; gameMenu = null; moving = null
        memory.column.animateScrollToItem(0)
        val first = config.apps?.firstOrNull()
        if (first != null) {
            memory.row(RowKind.APPS.id).animateScrollToItem(0)
            focusKey("apps:$first")
        }
    }

    // Focus where it was left (or the first app) when Home shows; on the tab when switched to from Requester
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(appsLoaded) {
        if (startOnTabs) {
            delay(60)
            if (runCatching { tabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        if (!appsLoaded) return@LaunchedEffect
        delay(120)
        val first = config.apps?.firstOrNull()?.let { "apps:$it" }
        for (target in listOfNotNull(memory.focusedKey, first, "add")) {
            val r = memory.requesters[target] ?: continue
            if (runCatching { r.requestFocus() }.isSuccess) break
        }
    }

    // Holding OK on an app: move it, take it off the row, its info, or add another
    BackHandler(enabled = moving != null) {
        app.store.setApps(movingBefore)
        val k = moving
        moving = null
        focusKey("apps:$k")
    }
    BackHandler(enabled = moving == null && !overlay) {
        // Back on Home: to the top row's first app
        memory.column.let { s -> scope.launch { s.animateScrollToItem(0) } }
        config.apps?.firstOrNull()?.let { focusKey("apps:$it") }
    }

    val rowKeys = if (moving != null) movingRow else config.apps.orEmpty()
    val appRow = rowKeys.mapNotNull { k -> allApps.firstOrNull { it.key == k } }

    // The film's trailer, muted, behind the top (after 2 seconds on it)
    var trailer by remember { mutableStateOf<TrailerMedia?>(null) }
    var trailerUp by remember { mutableStateOf(false) }
    LaunchedEffect(shown, config.trailers, overlay) {
        trailer = null
        trailerUp = false
        val h = shown as? Hero.Media ?: return@LaunchedEffect
        if (!config.trailers || overlay || h.kind == RowKind.CONTINUE || !h.item.hasTrailer) return@LaunchedEffect
        val account = config.emby ?: return@LaunchedEffect
        delay(2000)
        trailer = app.trailers.media(account, h.item)
    }

    Box(Modifier.fillMaxSize().background(Tv.bg)) {
        // On the apps row: the app's colours and the weather outside, over the whole screen
        val clockUp by animateFloatAsState(if (shown is Hero.Clock) 1f else 0f, tween(500), label = "apps")
        val glow by androidx.compose.animation.animateColorAsState(appGlow, tween(700), label = "glow")
        if (clockUp > 0.01f) AppsBackdrop(glow, weather, Modifier.alpha(clockUp))
        Backdrop(shown, trailer, trailerUp, config.trailerSound, onTrailerFrame = { trailerUp = true }, onTrailerGone = { trailer = null; trailerUp = false })

        CompositionLocalProvider(LocalTvEnabled provides !overlay) {
            TopBar(
                tab = Tab.HOME,
                onTab = onTab,
                tabFocus = tabFocus,
                // The big clock on the apps row has the time, date and weather already
                small = shown !is Hero.Clock,
                weather = weather,
                update = (update as? UpdateState.Available)?.update?.version
                    ?: (update as? UpdateState.Downloading)?.update?.version
                    ?: (update as? UpdateState.Failed)?.update?.version
                    ?: (update as? UpdateState.NeedsPermission)?.update?.version,
                onUpdate = {
                    updateOpen = true
                    app.updater.update()
                },
                onSearch = { openSearch(app) },
                onSettings = actions.openSettings,
            )

            // The weather strip sits beside the big clock: after however wide the time is (12 or 24 hour,
            // any font size), and only once the words about a card have faded out completely, so the two
            // never overlap, even when the Shield is slow to draw again after the screensaver.
            val heroAnim = updateTransition(shown, label = "hero")
            var clockWidth by remember { mutableStateOf(0.dp) }
            HeroText(heroAnim, onClockWidth = { clockWidth = it })
            val settled = heroAnim.currentState is Hero.Clock && heroAnim.targetState is Hero.Clock && !heroAnim.isRunning && clockWidth > 0.dp
            val stripUp by animateFloatAsState(if (settled) 1f else 0f, if (settled) tween(300) else tween(90), label = "weather strip")
            val stripStart by animateDpAsState(maxOf(270.dp, 48.dp + clockWidth + 32.dp), tween(300), label = "weather strip place")
            if (stripUp > 0.01f) weather?.let {
                WeatherStrip(it, Modifier.padding(start = stripStart, top = 84.dp, end = 40.dp).fillMaxWidth().alpha(stripUp))
            }

            if (trailerUp && shown is Hero.Media) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 196.dp, end = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0x9906080C)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Ic.mute, null, Modifier.size(12.dp), tint = Tv.ink2)
                    Text("Trailer playing, muted", style = Tv.text(10f, color = Tv.ink2))
                }
            }

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
                            // The row above the focused one fades out under the top
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black), blendMode = BlendMode.DstIn)
                        },
                ) {
                    config.rows.filter { it.on }.forEach { setting ->
                        val kind = setting.kind
                        if (kind == null) {
                            // An app's channel (SmartTube's Subscriptions, say), when the app has it
                            val channel = channels.firstOrNull { it.rowId == setting.id } ?: return@forEach
                            item(key = setting.id) {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                    ChannelRow(app, channel, memory, actions, onFocus = { p, key ->
                                        hero = Hero.Program(p, channel)
                                        memory.focusedKey = key
                                    })
                                }
                            }
                            return@forEach
                        }
                        item(key = kind.id) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                when (kind) {
                                    RowKind.APPS -> RowFrame(
                                        "Apps",
                                        moving?.let { k -> "Moving ${allApps.firstOrNull { it.key == k }?.label ?: "the app"}: ◀ ▶ to move it, OK to drop it there, Back to put it back" } ?: "",
                                    ) {
                                        LazyRow(
                                            state = memory.row(kind.id),
                                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            items(appRow, key = { a -> a.key }) { a ->
                                                val key = "apps:${a.key}"
                                                val isMoving = moving == a.key
                                                // While moving: ◀ ▶ shift it along the row, up and down stay put
                                                val moveKeys: (KeyEvent) -> Boolean = { e ->
                                                    val index = movingRow.indexOf(a.key)
                                                    if (!e.isDown()) {
                                                        e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionUp || e.key == Key.DirectionDown
                                                    } else when (e.key) {
                                                        Key.DirectionLeft -> {
                                                            if (index > 0) {
                                                                movingRow = movingRow.toMutableList().apply { add(index - 1, removeAt(index)) }
                                                                keepVisible(memory.row(RowKind.APPS.id), index - 1)
                                                            }
                                                            true
                                                        }
                                                        Key.DirectionRight -> {
                                                            if (index in 0 until movingRow.size - 1) {
                                                                movingRow = movingRow.toMutableList().apply { add(index + 1, removeAt(index)) }
                                                                keepVisible(memory.row(RowKind.APPS.id), index + 1)
                                                            }
                                                            true
                                                        }
                                                        Key.DirectionUp, Key.DirectionDown -> true
                                                        else -> false
                                                    }
                                                }
                                                AppTile(
                                                    label = a.label,
                                                    banner = a.banner,
                                                    icon = a.icon,
                                                    onClick = {
                                                        if (moving != null) {
                                                            app.store.setApps(movingRow)
                                                            moving = null
                                                        } else if (!app.apps.open(a)) actions.toast("${a.label} couldn't be opened")
                                                    },
                                                    onLongClick = { if (moving == null) appMenu = a },
                                                    onFocus = {
                                                        hero = Hero.Clock
                                                        memory.focusedKey = key
                                                        appGlow = appColour(a.key, a.banner ?: a.icon)
                                                    },
                                                    moving = isMoving,
                                                    onKey = if (isMoving) moveKeys else null,
                                                    focusRequester = memory.requester(key),
                                                )
                                            }
                                            // Adding is in the hold-OK menu; the tile shows only when the row is empty
                                            if (moving == null && appRow.isEmpty()) {
                                                item(key = "add") {
                                                    AddTile(
                                                        onClick = { picker = true },
                                                        onFocus = {
                                                            hero = Hero.Clock
                                                            memory.focusedKey = "add"
                                                        },
                                                        focusRequester = memory.requester("add"),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    RowKind.CONTINUE -> MediaRow(kind, "Continue watching", "From Emby", cw.items, cw.loaded, cw.error, "Nothing to carry on with just now", memory) { item, key ->
                                        WideCard(
                                            title = item.seriesName ?: item.name,
                                            sub = cardLine(item),
                                            image = item.wide,
                                            progress = if (item.nextUp) 0f else item.progress,
                                            onClick = { play(app, item, actions) },
                                            onLongClick = { itemMenu = item },
                                            onFocus = {
                                                hero = Hero.Media(item, kind)
                                                memory.focusedKey = key
                                            },
                                            focusRequester = memory.requester(key),
                                        )
                                    }

                                    RowKind.MOVIES, RowKind.SHOWS -> {
                                        val state = if (kind == RowKind.MOVIES) movies else shows
                                        MediaRow(
                                            kind, kind.title, if (state.total > 0) "From Emby · ${state.total}" else "From Emby",
                                            state.items, state.loaded, state.error,
                                            if (kind == RowKind.MOVIES) "No films on the Emby server" else "No shows on the Emby server", memory,
                                        ) { item, key ->
                                            PosterCard(
                                                title = item.name,
                                                image = item.poster,
                                                onClick = { play(app, item, actions) },
                                                onLongClick = {
                                                    if (item.hasTrailer) actions.openTrailer(item)
                                                    else actions.toast("There's no trailer for ${item.name}")
                                                },
                                                onFocus = {
                                                    hero = Hero.Media(item, kind)
                                                    memory.focusedKey = key
                                                },
                                                focusRequester = memory.requester(key),
                                                watched = item.played,
                                            )
                                        }
                                    }

                                    RowKind.GAMES -> {
                                        val source = remember(config.games) { app.games.sourceName() }
                                        MediaRow(
                                            kind, "Games", "From $source", config.games, true, null,
                                            "In Artemis, hold a game and choose Create shortcut: it shows up here", memory,
                                            keyOf = { "${it.pkg}|${it.shortcutId}" },
                                        ) { game, key ->
                                            PosterCard(
                                                title = game.name,
                                                image = null,
                                                imageModel = game.art.takeIf { it.isNotBlank() }?.let { File(it) },
                                                onClick = {
                                                    if (!app.games.start(game)) actions.toast("Android only lets the Shield's home screen start games: make Shield Launcher the home screen (Settings › Home button)")
                                                },
                                                onLongClick = { gameMenu = game },
                                                onFocus = {
                                                    hero = Hero.Play(game, source)
                                                    memory.focusedKey = key
                                                },
                                                focusRequester = memory.requester(key),
                                                tag = "PC",
                                            )
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }

        // Holding OK on an app
        appMenu?.let { a ->
            BackHandler { appMenu = null; focusKey("apps:${a.key}") }
            MenuPanel(
                title = "Hold OK on an app",
                items = listOf(
                    MenuItem(Ic.left, "Move") {
                        appMenu = null
                        movingBefore = config.apps.orEmpty()
                        movingRow = config.apps.orEmpty()
                        moving = a.key
                        focusKey("apps:${a.key}")
                    },
                    MenuItem(Ic.close, "Remove from the row") {
                        appMenu = null
                        val row = config.apps.orEmpty()
                        val i = row.indexOf(a.key)
                        app.store.setApps(row - a.key)
                        actions.toast("${a.label} is off the row: Add an app puts it back")
                        (row.getOrNull(i + 1) ?: row.getOrNull(i - 1))?.let { focusKey("apps:$it") } ?: focusKey("add")
                    },
                    MenuItem(Ic.info, "App info") {
                        appMenu = null
                        app.apps.info(a)
                    },
                    MenuItem(Ic.plus, "Add an app") {
                        appMenu = null
                        picker = true
                    },
                ),
            )
        }

        if (picker) {
            BackHandler { picker = false; focusKey(memory.focusedKey ?: "add") }
            AppPicker(
                apps = allApps.filter { it.key !in config.apps.orEmpty() },
                onPick = { a ->
                    picker = false
                    app.store.setApps(config.apps.orEmpty() + a.key)
                    focusKey("apps:${a.key}")
                },
            )
        }

        itemMenu?.let { item ->
            val key = "${RowKind.CONTINUE.id}:${item.id}"
            BackHandler { itemMenu = null; focusKey(key) }
            val account = config.emby
            MenuPanel(
                title = item.seriesName ?: item.name,
                items = buildList {
                    if (!item.nextUp) add(MenuItem(Ic.restart, "From the start") {
                        itemMenu = null
                        play(app, item, actions, fromStart = true)
                        focusKey(key)
                    })
                    add(MenuItem(Ic.list, if (item.isEpisode) "The episodes" else "Open in Emby") {
                        itemMenu = null
                        val pkg = app.embyLauncher.app(config.embyApp)
                        if (account != null && pkg != null) {
                            val target = if (item.isEpisode && item.seriesId != null) item.copy(id = item.seriesId, type = "Series") else item
                            app.embyLauncher.open(account, target, pkg)
                        } else actions.toast("Install the Emby app to open it")
                    })
                    if (item.hasTrailer) add(MenuItem(Ic.film, "Trailer") {
                        itemMenu = null
                        actions.openTrailer(item)
                    })
                    add(MenuItem(Ic.eye, "Mark as watched") {
                        itemMenu = null
                        if (account != null) scope.launch {
                            runCatching { app.emby.markPlayed(account, item) }
                                .onSuccess { app.feed.refresh() }
                                .onFailure { actions.toast("Emby didn't take it: try again") }
                        }
                    })
                    if (!item.nextUp) add(MenuItem(Ic.close, "Remove from Continue watching") {
                        itemMenu = null
                        if (account != null) scope.launch {
                            runCatching { app.emby.hideFromResume(account, item) }
                                .onSuccess { app.feed.refresh() }
                                .onFailure { actions.toast("Emby didn't take it: try again") }
                        }
                    })
                },
            )
        }

        gameMenu?.let { game ->
            val key = "${RowKind.GAMES.id}:${game.pkg}|${game.shortcutId}"
            BackHandler { gameMenu = null; focusKey(key) }
            MenuPanel(
                title = game.name,
                items = listOf(
                    MenuItem(Ic.play, "Play") {
                        gameMenu = null
                        if (!app.games.start(game)) actions.toast("Make Shield Launcher the home screen to start games")
                    },
                    MenuItem(Ic.trash, "Remove from Games") {
                        gameMenu = null
                        app.games.remove(game)
                        actions.toast("${game.name} is off Home")
                    },
                ),
            )
        }

        // A new version on GitHub
        if (noteUp) {
            val u = (update as UpdateState.Available).update
            BackHandler { app.updater.later() }
            UpdateNote(
                version = u.version,
                installed = app.updater.installedVersion,
                notes = u.notes,
                onUpdate = {
                    updateOpen = true
                    app.updater.update()
                },
                onLater = { app.updater.later() },
            )
        }
        if (dialogUp) {
            BackHandler {
                app.updater.cancel()
                updateOpen = false
            }
            UpdateDialog(
                state = update,
                onCancel = {
                    app.updater.cancel()
                    updateOpen = false
                },
                onRetry = { app.updater.update() },
                onAllow = { app.updater.allowInstalls() },
            )
        }
        // The installer is up (or was closed): the dialog's done
        LaunchedEffect(update) { if (update is UpdateState.Available && updateOpen) updateOpen = false }

        updated?.let { v ->
            LaunchedEffect(v) {
                delay(7000)
                app.updater.dismissUpdated()
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF0111621)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Ic.check, null, Modifier.size(14.dp), tint = Tv.ok)
                Text("Updated to $v · the download was deleted", style = Tv.text(11f, color = Tv.ink2))
            }
        }
    }
}

/** A row's name with what it's from, then its cards. */
@Composable
fun RowFrame(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(start = 48.dp, end = 48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = Tv.rowTitle)
            if (hint.isNotBlank()) Text(hint, style = Tv.rowHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/** A row of Emby or Artemis cards, or a line saying why there are none. */
@Composable
private fun <T> MediaRow(
    kind: RowKind,
    title: String,
    hint: String,
    list: List<T>,
    loaded: Boolean,
    error: String?,
    empty: String,
    memory: HomeMemory,
    keyOf: (T) -> String = { (it as EmbyItem).id },
    card: @Composable (T, String) -> Unit,
) = CardRow(
    kind.id, title, hint, list, loaded, error, empty, memory,
    tall = kind == RowKind.MOVIES || kind == RowKind.SHOWS || kind == RowKind.GAMES, keyOf = keyOf, card = card,
)

/** A row of cards with its name, or a line saying why there are none. */
@Composable
fun <T> CardRow(
    rowId: String,
    title: String,
    hint: String,
    list: List<T>,
    loaded: Boolean,
    error: String?,
    empty: String,
    memory: HomeMemory,
    tall: Boolean,
    keyOf: (T) -> String,
    card: @Composable (T, String) -> Unit,
) {
    RowFrame(title, hint) {
        if (list.isEmpty()) {
            Text(
                if (!loaded) "Loading…" else error ?: empty,
                style = Tv.text(11f, color = Tv.muted),
                modifier = Modifier.padding(start = 48.dp, top = 10.dp, bottom = 18.dp),
            )
        } else {
            LazyRow(
                state = memory.row(rowId),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tall) 12.dp else 16.dp),
            ) {
                items(list, key = { keyOf(it) }) { item -> card(item, "$rowId:${keyOf(item)}") }
            }
        }
    }
}

/** The picture behind the top: the focused film's (or its muted trailer), or the clock's wallpaper. */
@Composable
private fun Backdrop(hero: Hero, trailer: TrailerMedia?, trailerUp: Boolean, sound: Boolean, onTrailerFrame: () -> Unit, onTrailerGone: () -> Unit) {
    val image: String? = when (hero) {
        is Hero.Media -> hero.item.backdrop
        is Hero.Program -> hero.program.poster ?: hero.program.thumbnail
        is Hero.Play -> hero.game.art.takeIf { it.isNotBlank() }
        // The apps row has a backdrop of its own (AppsBackdrop)
        Hero.Clock -> return
    }
    BackdropImage(image, trailer, trailerUp, onTrailerFrame, onTrailerGone, sound)
}

/** A picture filling the top of the screen, fading into the page (with the film's trailer over it, if there is one). */
@Composable
fun BackdropImage(image: String?, trailer: TrailerMedia?, trailerUp: Boolean, onTrailerFrame: () -> Unit, onTrailerGone: () -> Unit, sound: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        Crossfade(targetState = image, animationSpec = tween(450), label = "backdrop") { url ->
            if (url == null) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF0C1220), Tv.bg)))
                        .background(Brush.radialGradient(listOf(Color(0x597CC4FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1440f, 280f), radius = 700f))
                        .background(Brush.radialGradient(listOf(Color(0x40A06EFF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1820f, 680f), radius = 700f)),
                )
            } else {
                AsyncImage(
                    model = if (url.startsWith("/")) File(url) else url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (trailer != null) {
            val context = LocalContext.current
            val player = remember(trailer) { trailerPlayer(context, trailer, muted = !sound) }
            DisposableEffect(player) { onDispose { player.release() } }
            // Settings' Trailer sound, changed while one plays
            LaunchedEffect(player, sound) { player.volume = if (sound) 1f else 0f }
            val alpha by animateFloatAsState(if (trailerUp) 1f else 0f, tween(700), label = "trailer")
            PlayerSurface(
                player, fill = true, modifier = Modifier.fillMaxSize().alpha(alpha),
                onFirstFrame = onTrailerFrame, onEnded = onTrailerGone, onError = onTrailerGone,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tv.bg, 0.22f to Color(0xEB06080C), 0.55f to Color(0x5906080C), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C06080C), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 1f to Tv.bg)))
    }
}

/** The words at the top: about the focused card, or the time when on the apps row ([onClockWidth]: how wide it is). */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun HeroText(hero: Transition<Hero>, onClockWidth: (Dp) -> Unit) {
    val now = rememberNow()
    val context = LocalContext.current
    val density = LocalDensity.current
    Box(Modifier.padding(start = 48.dp, top = 84.dp).width(420.dp).height(146.dp)) {
        hero.Crossfade(animationSpec = tween(250)) { h ->
            when (h) {
                Hero.Clock -> Column(
                    Modifier.padding(top = 10.dp).onSizeChanged { with(density) { onClockWidth(it.width.toDp()) } },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(greeting(now), style = Tv.text(13f, color = Tv.ink2))
                    Text(DateFormat.getTimeFormat(context).format(Date(now)), style = Tv.text(66f, FontWeight.Light, lineHeight = 66f, spacing = -1f))
                    val day = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now))
                    // The weather has a strip of its own beside the clock
                    Text(day, style = Tv.text(13f, color = Tv.ink2))
                }
                is Hero.Media -> MediaText(h)
                is Hero.Play -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("GAME · ${h.source.uppercase()}", style = Tv.kicker)
                    Text(h.game.name, style = Tv.heroTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.game.longName.isNotBlank() && h.game.longName != h.game.name) Text(h.game.longName, style = Tv.meta, maxLines = 1)
                    Text("Opens straight into the game on your PC through ${h.source}.", style = Tv.body, maxLines = 2)
                    Text("OK to play  ·  hold OK to remove it", style = Tv.hint)
                }
                is Hero.Program -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${h.channel.name.uppercase()} · ${h.channel.appName.uppercase()}", style = Tv.kicker, maxLines = 1)
                    Text(h.program.title, style = Tv.text(26f, FontWeight.Bold, lineHeight = 29f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(h.program.description.lines().firstOrNull()?.takeIf { it.isNotBlank() }, clock(h.program.durationMs)).joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, style = Tv.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("OK to open in ${h.channel.appName}", style = Tv.hint)
                }
            }
        }
    }
}

@Composable
private fun MediaText(h: Hero.Media) {
    val item = h.item
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        val kicker = when (h.kind) {
            RowKind.CONTINUE -> if (item.nextUp) "NEXT UP · EMBY" else "CONTINUE WATCHING · EMBY"
            RowKind.SHOWS -> "TV SHOW · EMBY"
            else -> "MOVIE · EMBY"
        }
        Text(kicker, style = Tv.kicker)
        Text(item.seriesName ?: item.name, style = Tv.heroTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(metaLine(item, h.kind), style = Tv.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (h.kind == RowKind.CONTINUE && !item.nextUp && item.progress > 0f) {
            Box(Modifier.width(260.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x2EFFFFFF))) {
                Box(Modifier.fillMaxWidth(item.progress).fillMaxHeight().background(Tv.accent))
            }
        }
        item.overview?.let { Text(it, style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(
            when (h.kind) {
                RowKind.CONTINUE -> if (item.nextUp) "OK to play  ·  hold OK for the episodes and more" else "OK to carry on  ·  hold OK for From the start, the episodes, Mark as watched"
                RowKind.SHOWS -> if (item.hasTrailer) "OK to open in Emby  ·  hold OK to watch the trailer with sound" else "OK to open in Emby"
                else -> if (item.hasTrailer) "OK to play  ·  hold OK to watch the trailer with sound" else "OK to play"
            },
            style = Tv.hint,
        )
    }
}

private fun cardLine(item: EmbyItem): String = when {
    item.isEpisode -> buildString {
        append("S${item.season ?: 0} · E${item.episode ?: 0}  ${item.name}")
        append(if (item.nextUp) " · Next episode" else " · ${item.minutesLeft} min left")
    }
    else -> "${item.year?.let { "$it film" } ?: "Film"} · ${duration(item.minutesLeft)} left"
}

private fun metaLine(item: EmbyItem, kind: RowKind): String {
    val parts = mutableListOf<String>()
    if (item.isEpisode) parts += "S${item.season ?: 0} · E${item.episode ?: 0}  ${item.name}"
    item.year?.let { parts += it.toString() }
    if (!item.isEpisode && !item.isSeries && item.minutes > 0) parts += duration(item.minutes)
    item.genres.firstOrNull()?.let { if (!item.isEpisode) parts += it }
    item.rating?.let { parts += it }
    if (kind == RowKind.CONTINUE && !item.nextUp && item.positionTicks > 0) parts += "${duration(item.minutesLeft)} left"
    return parts.joinToString("  ·  ")
}

private fun duration(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

private fun greeting(now: Long): String {
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun skyWords(s: Sky) = when (s) {
    Sky.CLEAR -> "clear"
    Sky.PARTLY -> "partly cloudy"
    Sky.CLOUDY -> "cloudy"
    Sky.FOG -> "foggy"
    Sky.RAIN -> "raining"
    Sky.SNOW -> "snowing"
    Sky.THUNDER -> "stormy"
}

fun skyIcon(w: WeatherNow) = when (w.sky) {
    Sky.CLEAR -> if (w.day) Ic.sun else Ic.moon
    Sky.PARTLY -> Ic.partly
    Sky.CLOUDY -> Ic.cloud
    Sky.FOG -> Ic.fog
    Sky.RAIN -> Ic.rain
    Sky.SNOW -> Ic.snowfall
    Sky.THUNDER -> Ic.thunder
}

/** The time, ticking over on the minute. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** OK on a film or episode: it plays in the Emby app (a show opens on its page). */
private fun play(app: LauncherApp, item: EmbyItem, actions: HomeActions, fromStart: Boolean = false) {
    val account = app.store.config.value.emby ?: return actions.toast("Sign in to Emby from your phone first")
    if (app.embyLauncher.app(app.store.config.value.embyApp) == null) return actions.toast("Install the Emby app to play")
    app.scope.launch {
        if (!app.embyLauncher.play(account, item, app.store.config.value.embyApp, fromStart)) actions.toast("The Emby app didn't open")
    }
}

/** The search button: the Shield's own search (Google's, with the voice button too). */
private fun openSearch(app: LauncherApp) {
    val intents = listOf(
        android.content.Intent("android.search.action.GLOBAL_SEARCH"),
        android.content.Intent(android.content.Intent.ACTION_ASSIST),
        android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH),
    )
    for (i in intents) {
        if (runCatching { app.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
    }
}


/** An app's channel as a row: wide pictures for videos, posters when the app gives tall ones. */
@Composable
private fun ChannelRow(app: LauncherApp, channel: AppChannel, memory: HomeMemory, actions: HomeActions, onFocus: (ChannelProgram, String) -> Unit) {
    val tall = channel.programs.isNotEmpty() && channel.programs.count { it.tall } > channel.programs.size / 2
    CardRow(
        channel.rowId, channel.name, "From ${channel.appName}", channel.programs, true, null, "Nothing on this channel just now", memory,
        tall = tall, keyOf = { it.id.toString() },
    ) { p, key ->
        val open = { if (!app.channels.open(p, channel.pkg)) actions.toast("${channel.appName} couldn't open it") }
        val progress = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
        if (tall) {
            PosterCard(
                title = p.title, image = p.poster ?: p.thumbnail, onClick = open, onLongClick = null,
                onFocus = { onFocus(p, key) }, focusRequester = memory.requester(key),
            )
        } else {
            VideoCard(
                title = p.title,
                channel = p.description.lines().firstOrNull().orEmpty(),
                duration = clock(p.durationMs).orEmpty(),
                thumbnail = p.thumbnail ?: p.poster ?: "",
                onClick = open,
                onFocus = { onFocus(p, key) },
                focusRequester = memory.requester(key),
                progress = progress,
            )
        }
    }
}

/** 1:02:03 or 12:34, from milliseconds; null for none. */
private fun clock(ms: Long): String? {
    if (ms <= 0) return null
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
