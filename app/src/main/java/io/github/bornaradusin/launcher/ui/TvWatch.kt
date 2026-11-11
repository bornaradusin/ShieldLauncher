package io.github.bornaradusin.launcher.ui

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.iptv.Episode
import io.github.bornaradusin.launcher.iptv.blockedHost
import io.github.bornaradusin.launcher.iptv.TvRow
import io.github.bornaradusin.launcher.iptv.XFilm
import io.github.bornaradusin.launcher.iptv.XSeries
import io.github.bornaradusin.launcher.iptv.Xtream
import kotlinx.coroutines.delay

/** What plays full screen. */
sealed interface Watch {
    /** A channel, from one of the TV tab's rows (▲ ▼ go along that row). */
    class Live(val rows: List<TvRow>, val row: Int, val index: Int) : Watch
    class Film(val film: XFilm, val fromStart: Boolean) : Watch
    /** A series' episodes, from [index] (the next one starts when it ends). */
    class Episodes(val series: XSeries, val episodes: List<Episode>, val index: Int, val fromStart: Boolean) : Watch
}

/** Buffer sizes (Settings › TV (IPTV) › Buffer), smallest to largest: what a channel loads before it starts. */
val BUFFER_NAMES = listOf("Smallest", "Small", "Medium", "Large", "Largest")
private val LIVE_START = intArrayOf(1_000, 3_000, 6_000, 12_000, 25_000)
/** How far ahead films and episodes are read. */
private val VOD_AHEAD = intArrayOf(30_000, 60_000, 120_000, 180_000, 300_000)
private val VOD_BYTES = intArrayOf(48, 80, 128, 176, 224)

fun bufferText(level: Int): String {
    val l = level.coerceIn(0, 4)
    val ahead = VOD_AHEAD[l] / 1000
    return "${BUFFER_NAMES[l]}: channels start after ${LIVE_START[l] / 1000} s loaded, films keep ${if (ahead >= 60) "${ahead / 60} min" else "$ahead s"} ahead"
}

/**
 * A player for IPTV: the provider's own address (redirects followed), MPEG-TS that starts on any key
 * frame, and the buffer from Settings: a channel waits until that much is in before it starts, and
 * keeps that much in hand, so a shaky connection doesn't stop the picture.
 */
@OptIn(UnstableApi::class)
fun iptvPlayer(context: Context, http: OkHttpClient, buffer: Int, live: Boolean, muted: Boolean): ExoPlayer {
    // IPTV's own client: every connection (each step of a redirect too) past the guard, which stops other servers
    val source = OkHttpDataSource.Factory(http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()).setUserAgent(Xtream.AGENT)
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)
    val level = buffer.coerceIn(0, 4)
    val start = LIVE_START[level]
    val load = if (live) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(maxOf(15_000, start * 2), maxOf(50_000, start * 3), start, start)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    } else {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minOf(30_000, VOD_AHEAD[level]), VOD_AHEAD[level], 2_000, maxOf(3_000, start))
            .setTargetBufferBytes(VOD_BYTES[level] * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(source, extractors))
        .setLoadControl(load)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), !muted)
        .build()
        .apply { volume = if (muted) 0f else 1f }
}

fun iptvItem(url: String): MediaItem =
    MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()

/** The picture: [fill] crops to fill; otherwise all of it, on black. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer, fill: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(player) {
        PlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = false
            setShutterBackgroundColor(if (fill) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)
            resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Zapping keeps the last picture until the next one comes
            setKeepContentOnPlayerReset(true)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Full screen: the Shield's screensaver stays away while it plays
            keepScreenOn = !fill
            this.player = player
        }
    }
    AndroidView({ view }, modifier)
}

@Composable
fun WatchScreen(app: LauncherApp, w: Watch, onClose: () -> Unit, toast: (String) -> Unit) {
    when (w) {
        is Watch.Live -> LiveWatch(app, w, onClose, toast)
        is Watch.Film, is Watch.Episodes -> VodWatch(app, w, onClose, toast)
    }
}

// ---- live ----------------------------------------------------------------------------------------------

@Composable
private fun LiveWatch(app: LauncherApp, start: Watch.Live, onClose: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val config by app.store.config.collectAsState()
    val rows = start.rows
    var row by remember { mutableIntStateOf(start.row) }
    var index by remember { mutableIntStateOf(start.index) }
    val channels = rows.getOrNull(row)?.channels.orEmpty()
    val channel = channels.getOrNull(index)
    if (channel == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val buffer = remember { config.iptv?.buffer ?: 2 }
    val player = remember { iptvPlayer(context, app.iptv.http, buffer, live = true, muted = false) }
    DisposableEffect(Unit) {
        app.iptv.watching.value = true
        onDispose {
            player.release()
            app.iptv.watching.value = false
        }
    }
    var info by remember { mutableStateOf(true) }
    var infoAt by remember { mutableLongStateOf(0L) }
    var reconnecting by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    // The other server the provider sent the channel to, stopped (Settings › TV (IPTV) blocks them)
    var blocked by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    val version by app.iptv.guideVersion.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    fun showInfo() {
        info = true
        infoAt = System.currentTimeMillis()
    }

    // The channel plays; a stream that drops or stalls is opened again by itself
    LaunchedEffect(channel.id, attempt) {
        val url = app.iptv.liveUrl(channel) ?: return@LaunchedEffect
        reconnecting = false
        blocked = null
        showInfo()
        app.iptv.want(channel.id)
        player.setMediaItem(iptvItem(url))
        player.prepare()
        player.playWhenReady = true
        var tries = 0
        var stalled = 0
        val patience = 15 + LIVE_START[buffer.coerceIn(0, 4)] / 1000
        while (true) {
            delay(1_000)
            val state = player.playbackState
            if (state == Player.STATE_READY && player.isPlaying) {
                tries = 0
                reconnecting = false
            }
            stalled = if (state == Player.STATE_BUFFERING) stalled + 1 else 0
            val stopped = blockedHost(player.playerError)
            if (stopped != null) {
                // Not tried again: it would only be stopped again
                reconnecting = false
                blocked = stopped
                player.stop()
                break
            }
            if (player.playerError != null || state == Player.STATE_ENDED || stalled >= patience) {
                reconnecting = true
                tries++
                delay(minOf(10_000L, 1_000L shl minOf(tries - 1, 3)))
                player.stop()
                player.setMediaItem(iptvItem(url))
                player.prepare()
                player.playWhenReady = true
                stalled = 0
            }
        }
    }
    // Watched for 5 seconds: first in Recently watched
    LaunchedEffect(channel.id) {
        delay(5_000)
        app.iptv.watched(channel)
    }
    LaunchedEffect(info, infoAt, list, adding) {
        if (info && !list && !adding) {
            delay(5_000)
            info = false
        }
    }
    LaunchedEffect(list, adding, naming, blocked) {
        if (!list && !adding && !naming && blocked == null) {
            delay(50)
            runCatching { root.requestFocus() }
        }
    }

    fun zap(by: Int) {
        if (channels.isEmpty()) return
        index = (index + by + channels.size) % channels.size
    }

    BackHandler(enabled = !list && !adding && !naming && blocked == null) { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(player, fill = false, modifier = Modifier.fillMaxSize())
        // The remote, while nothing is open over the picture
        androidx.compose.runtime.CompositionLocalProvider(LocalTvEnabled provides (!list && !adding && !naming && blocked == null)) {
        Box(
            Modifier.fillMaxSize()
            .focusRequester(root)
            .tvItem(
                onClick = { if (info) info = false else showInfo() },
                onLongClick = { adding = true },
                onKey = { e ->
                    val first = e.nativeKeyEvent.repeatCount == 0
                    when (e.key) {
                        // One press is one switch: holding doesn't run through the channels
                        // ▼ goes down the list (the next number up), as Channel + does
                        Key.DirectionDown, Key.ChannelUp -> {
                            if (e.isDown() && first) zap(1)
                            true
                        }
                        Key.DirectionUp, Key.ChannelDown -> {
                            if (e.isDown() && first) zap(-1)
                            true
                        }
                        Key.DirectionLeft -> {
                            if (e.isDown() && first) list = true
                            true
                        }
                        Key.DirectionRight -> {
                            if (e.isDown() && first) showInfo()
                            true
                        }
                        else -> false
                    }
                },
            ),
        )
        }

        val (on, next) = remember(channel.id, version, now) { app.iptv.nowNext(channel.id, now) }
        AnimatedVisibility(info || reconnecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(
                Modifier.fillMaxWidth().height(170.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE606080C), Color(0xFA06080C)))),
            ) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, bottom = 34.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (channel.num > 0) Text("${channel.num}", style = Tv.text(24f, FontWeight.Light), modifier = Modifier.width(52.dp))
                    ChannelLogo(channel, 48.dp, 10.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(channel.name, style = Tv.text(11f, FontWeight.SemiBold, Tv.ink2), maxLines = 1)
                            Box(Modifier.size(5.dp).clip(CircleShape).background(LIVE_RED))
                            Text("LIVE", style = Tv.text(9.5f, FontWeight.Bold, LIVE_RED, spacing = 1.2f))
                            if (reconnecting) Text("· Reconnecting…", style = Tv.text(10f, FontWeight.SemiBold, Tv.accent))
                        }
                        Text(on?.title?.ifBlank { null } ?: channel.name, style = Tv.text(21f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (on != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(hm(context, on.start), style = Tv.text(10f, color = Tv.ink2))
                                Progress(on.fraction(now), Modifier.weight(1f), height = 3.dp, colour = Tv.ink)
                                Text(hm(context, on.end), style = Tv.text(10f, color = Tv.ink2))
                            }
                        } else {
                            Text(if (app.iptv.guide(channel.id) == null) "Looking for what's on…" else "No guide for this channel", style = Tv.text(10f, color = Tv.muted))
                        }
                        if (next != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("NEXT ${hm(context, next.start)}", style = Tv.text(9f, FontWeight.Bold, Tv.muted))
                                Text(next.title, style = Tv.text(9f, color = Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(info && !list, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Row(Modifier.padding(top = 32.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Hint("▲▼", "change channel")
                Hint("◀", "channel list")
                Hint("OK", "what's on")
                Hint("Hold OK", "add to a group")
            }
        }

        if (list) {
            ZapList(
                app, rows, row, index, now,
                onPick = { r, i ->
                    row = r
                    index = i
                    list = false
                },
                onClose = { list = false },
            )
        }
        if (adding) {
            AddToGroupPanel(app, channel, rows.getOrNull(row), onNewGroup = { adding = false; naming = true }, onMove = null, onClose = { adding = false })
        }
        blocked?.let { host ->
            BlockedPanel(
                app, host, channel.name,
                onAllow = {
                    app.iptv.allowHost(host, true)
                    attempt++
                },
                onClose = {
                    blocked = null
                    zap(0)
                },
                onLeave = onClose,
            )
        }
        if (naming) {
            TextEntry("New group with ${channel.name}", "", "Its name: the keyboard's Done key makes it · Back to leave it") { name ->
                naming = false
                if (!name.isNullOrBlank()) {
                    app.iptv.newGroup(name, listOf(channel.id))
                    toast("“${name.trim()}” is a new group, with ${channel.name} in it")
                }
            }
        }
    }
}

val LIVE_RED = Color(0xFFFF5A5F)

@Composable
private fun Hint(key: String, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(11.dp)).background(Color(0xB30B0F17)).padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = Tv.text(9f, FontWeight.Bold))
        Text(text, style = Tv.text(9f, color = Tv.ink2))
    }
}

/** ◀ while watching: the row's channels down the left (◀ ▶ for the other groups); the channel keeps playing. */
@Composable
private fun ZapList(app: LauncherApp, rows: List<TvRow>, row: Int, index: Int, now: Long, onPick: (Int, Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var r by remember { mutableIntStateOf(row) }
    var i by remember { mutableIntStateOf(index) }
    val channels = rows.getOrNull(r)?.channels.orEmpty()
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val version by app.iptv.guideVersion.collectAsState()
    LaunchedEffect(Unit) {
        state.scrollToItem((i - 3).coerceAtLeast(0))
        delay(30)
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(r, i) {
        val seen = state.layoutInfo.visibleItemsInfo
        val top = seen.firstOrNull()?.index ?: 0
        val bottom = seen.lastOrNull()?.index ?: 0
        if (i < top + 1 || i > bottom - 1) state.animateScrollToItem((i - 3).coerceAtLeast(0))
    }
    BackHandler(onBack = onClose)

    fun otherRow(by: Int) {
        if (rows.isEmpty()) return
        var n = r
        repeat(rows.size) {
            n = (n + by + rows.size) % rows.size
            if (rows[n].channels.isNotEmpty()) {
                r = n
                i = if (n == row) index else 0
                return
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF206080C), 0.3f to Color(0xD906080C), 0.45f to Color.Transparent))) {
        Column(
            Modifier.padding(start = 34.dp, top = 30.dp, bottom = 20.dp).width(305.dp).fillMaxHeight()
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (!e.isDown()) return@onPreviewKeyEvent e.isOk() && run {
                        onPick(r, i)
                        true
                    }
                    when (e.key) {
                        Key.DirectionUp -> {
                            if (channels.isNotEmpty()) i = (i - 1 + channels.size) % channels.size
                            true
                        }
                        Key.DirectionDown -> {
                            if (channels.isNotEmpty()) i = (i + 1) % channels.size
                            true
                        }
                        Key.DirectionLeft -> {
                            if (e.nativeKeyEvent.repeatCount == 0) otherRow(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (e.nativeKeyEvent.repeatCount == 0) otherRow(1)
                            true
                        }
                        else -> e.isOk()
                    }
                }
                .focusable(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(Ic.left, null, Modifier.size(12.dp), tint = Tv.muted)
                Text(rows.getOrNull(r)?.title.orEmpty(), style = Tv.text(15f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Ic.right, null, Modifier.size(12.dp), tint = Tv.muted)
                Text("◀ ▶ other groups", style = Tv.text(9f, color = Tv.muted))
            }
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { n, c ->
                    LaunchedEffect(c.id) { app.iptv.want(c.id) }
                    val on = remember(c.id, version, now) { app.iptv.nowNext(c.id, now).first }
                    val lit = n == i
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (lit) Color(0x26FFFFFF) else Color.Transparent)
                            .border(if (lit) 1.5.dp else 0.dp, if (lit) Tv.ink else Color.Transparent, RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (c.num > 0) "${c.num}" else "", style = Tv.text(10f, color = Tv.muted), modifier = Modifier.width(26.dp))
                        ChannelLogo(c, 24.dp)
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = Tv.text(11f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(on?.title ?: "", style = Tv.text(8.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (on != null) Progress(on.fraction(now), Modifier.width(110.dp))
                            }
                        }
                    }
                }
            }
        }
        val playing = rows.getOrNull(row)?.channels?.getOrNull(index)
        if (playing != null) {
            Text(
                "${playing.name} keeps playing behind the list; OK switches to the one you're on · ${hm(context, now)}",
                style = Tv.text(9.5f, color = Tv.ink2),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC0B0F17)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

// ---- films and episodes --------------------------------------------------------------------------------

private class VodItem(
    val key: String,
    val title: String,
    val sub: String,
    val url: String?,
    val poster: String?,
    val seriesId: String?,
    val seconds: Int,
    /** How it's named in Continue watching. */
    val label: String,
)

@Composable
private fun VodWatch(app: LauncherApp, w: Watch, onClose: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val config by app.store.config.collectAsState()
    var index by remember { mutableIntStateOf((w as? Watch.Episodes)?.index ?: 0) }
    var fromStart by remember { mutableStateOf(if (w is Watch.Film) w.fromStart else (w as Watch.Episodes).fromStart) }
    val item: VodItem? = when (w) {
        is Watch.Film -> {
            val info = app.iptv.cachedFilmInfo(w.film.id)
            VodItem(
                "f:${w.film.id}", w.film.name, listOfNotNull(info?.year?.ifBlank { null }, minutes(info?.seconds ?: 0).ifBlank { null }).joinToString(" · "),
                app.iptv.filmUrl(w.film, info?.ext), w.film.poster, null, info?.seconds ?: 0, w.film.name,
            )
        }
        is Watch.Episodes -> w.episodes.getOrNull(index)?.let { e ->
            VodItem(
                "e:${e.id}", w.series.name, "S${e.season} · E${e.number} · ${e.title}", app.iptv.episodeUrl(e), w.series.poster, w.series.id, e.seconds,
                "${w.series.name} · S${e.season} E${e.number}",
            )
        }
        is Watch.Live -> null
    }
    val url = item?.url
    if (item == null || url == null) {
        LaunchedEffect(Unit) {
            toast("That can't be played")
            onClose()
        }
        return
    }
    val player = remember { iptvPlayer(context, app.iptv.http, config.iptv?.buffer ?: 2, live = false, muted = false) }
    var last by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    // What's playing now, for saving where it got to (even as the screen goes)
    val current = remember { arrayOfNulls<VodItem>(1) }
    current[0] = item
    fun save() {
        val it = current[0] ?: return
        val pos = if (player.playbackState == Player.STATE_IDLE) last else player.currentPosition
        val dur = player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: (it.seconds * 1000L)
        if (dur > 0 && pos > 0) app.iptv.saveResume(it.key, pos, dur, it.label, it.poster, it.seriesId)
    }
    DisposableEffect(Unit) {
        app.iptv.watching.value = true
        onDispose {
            save()
            player.release()
            app.iptv.watching.value = false
        }
    }
    var controls by remember { mutableStateOf(true) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var problem by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    fun show() {
        controls = true
        shownAt = System.currentTimeMillis()
    }

    LaunchedEffect(item.key, attempt) {
        problem = null
        blocked = null
        show()
        val resume = if (attempt > 0) last else if (fromStart) 0L else app.iptv.resume(item.key)?.position ?: 0L
        fromStart = false
        player.setMediaItem(iptvItem(url), resume)
        player.prepare()
        player.playWhenReady = true
        var tries = 0
        var saved = 0
        while (true) {
            delay(1_000)
            if (player.playbackState == Player.STATE_READY) {
                last = player.currentPosition
                player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { length = it }
                if (player.isPlaying) tries = 0
                if (++saved >= 15) {
                    saved = 0
                    save()
                }
            }
            if (player.playbackState == Player.STATE_ENDED) {
                save()
                val eps = (w as? Watch.Episodes)?.episodes
                if (eps != null && index + 1 < eps.size) {
                    toast("Next: ${eps[index + 1].title}")
                    index++
                } else onClose()
                break
            }
            val stopped = blockedHost(player.playerError)
            if (stopped != null) {
                blocked = stopped
                player.stop()
                break
            }
            if (player.playerError != null) {
                // Dropped: picked up again where it was, a few times, a little later each time
                tries++
                if (tries > 6) {
                    problem = "It won't play just now: Back, and try again later"
                    break
                }
                problem = "Reconnecting…"
                delay(minOf(8_000L, 1_000L shl minOf(tries - 1, 3)))
                player.setMediaItem(iptvItem(url), last)
                player.prepare()
                player.playWhenReady = true
            } else if (problem == "Reconnecting…" && player.isPlaying) problem = null
        }
    }
    LaunchedEffect(controls, shownAt) {
        if (controls) {
            delay(4_000)
            if (player.isPlaying) controls = false
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { root.requestFocus() }
    }
    BackHandler(enabled = blocked == null) { onClose() }

    // Where it is, while the controls show
    val position by produceState(0L, controls) {
        while (controls) {
            value = player.currentPosition
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTvEnabled provides (blocked == null)) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .tvItem(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    show()
                },
                onKey = { e ->
                    val n = e.nativeKeyEvent.repeatCount
                    // Held: 10 seconds a step, then 30, then a minute
                    val step = when {
                        n < 5 -> 10_000L
                        n < 15 -> 30_000L
                        else -> 60_000L
                    }
                    when (e.key) {
                        Key.DirectionLeft, Key.MediaRewind -> {
                            if (e.isDown()) {
                                player.seekTo((player.currentPosition - step).coerceAtLeast(0))
                                show()
                            }
                            true
                        }
                        Key.DirectionRight, Key.MediaFastForward -> {
                            if (e.isDown()) {
                                val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + step).coerceAtMost(end - 1_000))
                                show()
                            }
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (e.isDown()) show()
                            true
                        }
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (e.isDown() && n == 0) {
                                if (player.isPlaying) player.pause() else player.play()
                                show()
                            }
                            true
                        }
                        else -> false
                    }
                },
            ),
    ) {
        VideoSurface(player, fill = false, modifier = Modifier.fillMaxSize())
        problem?.let {
            Text(
                it, style = Tv.text(12f, FontWeight.SemiBold, Tv.ink2),
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xCC0B0F17)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        AnimatedVisibility(controls || !player.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF206080C))))) {
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, bottom = 30.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, style = Tv.text(20f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // (the blocked panel, below, sits over this)
                    if (item.sub.isNotBlank()) Text(item.sub, style = Tv.text(10.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val total = length.takeIf { it > 0 } ?: item.seconds * 1000L
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(clock(position), style = Tv.text(10f, color = Tv.ink2))
                        Progress(if (total > 0) position.toFloat() / total else 0f, Modifier.weight(1f), height = 3.dp, colour = Tv.ink)
                        Text(if (total > 0) "-" + clock((total - position).coerceAtLeast(0)) else "", style = Tv.text(10f, color = Tv.ink2))
                    }
                    Text(
                        (if (!player.isPlaying) "Paused · " else "") + "OK ${if (player.isPlaying) "pauses" else "plays"} · ◀ ▶ 10 seconds (hold for faster) · Back keeps your place",
                        style = Tv.hint,
                    )
                }
            }
        }
    }
    }
    blocked?.let { host ->
        BlockedPanel(
            app, host, item.title,
            onAllow = {
                app.iptv.allowHost(host, true)
                attempt++
            },
            onClose = onClose,
            onLeave = onClose,
        )
    }
    }
}

/**
 * The provider sent what you picked on to another server, and Settings › TV (IPTV) blocks other servers: allow
 * that one (it plays), or don't.
 */
@Composable
private fun BlockedPanel(app: LauncherApp, host: String, what: String, onAllow: () -> Unit, onClose: () -> Unit, onLeave: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(host) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    BackHandler(onBack = onLeave)
    CentrePanel(width = 380.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.warn, null, Modifier.size(16.dp), tint = Tv.bad)
                Text("Another server: blocked", style = Tv.text(14f, FontWeight.Bold))
            }
            Text(
                "Your provider sends $what on to $host, not ${app.store.config.value.iptv?.host() ?: "its own server"}. " +
                    "Settings › TV (IPTV) blocks other servers, so it was stopped there: nothing reached $host.",
                style = Tv.text(10.5f, color = Tv.ink2),
            )
            Gap(4)
            PillButton("Allow $host", onClick = onAllow, primary = true, focusRequester = first)
            PillButton("Keep it blocked", onClick = onClose)
            Text("Allowed servers can be blocked again in Settings › TV (IPTV).", style = Tv.hint)
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A key in the hints at the top of a page. */
@Composable
fun KeyHint(key: String, text: String) = Hint(key, text)

/** Spacer of [h] dp: shorthand for the TV pages. */
@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

/** A tiny round marker. */
@Composable
fun Dot(colour: Color) = Box(Modifier.size(5.dp).clip(CircleShape).background(colour))
