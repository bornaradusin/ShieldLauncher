package io.github.bornaradusin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.trailer.TrailerMedia
import kotlinx.coroutines.delay

/** A trailer to show: what it's for, where its video comes from, and what the main button does. */
class TrailerRequest(
    val title: String,
    /** "Play the film", "Open the show", "Request it"…; null for no main button. */
    val playLabel: String?,
    val onPlay: (() -> Unit)?,
    /** The YouTube link, for SmartTube when the trailer can't play here. */
    val youtubeLink: String?,
    val load: suspend () -> TrailerMedia?,
)

/**
 * Holding OK on a film: its trailer, full screen with sound. Play the film from here, or Back to
 * Home. When the trailer can't play in the launcher (some YouTube trailers), it opens in SmartTube.
 */
@Composable
fun TrailerScreen(app: LauncherApp, request: TrailerRequest, onClose: () -> Unit, toast: (String) -> Unit) {
    var media by remember { mutableStateOf<TrailerMedia?>(null) }
    var loading by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)

    LaunchedEffect(request) {
        media = runCatching { request.load() }.getOrNull()
        loading = false
        if (media == null) {
            val link = request.youtubeLink
            if (link != null && app.smartTube.open(link)) onClose()
            else {
                toast("The trailer for ${request.title} can't be played")
                onClose()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val m = media
        if (m == null) {
            if (loading) Text("Loading the trailer…", style = Tv.text(13f, color = Tv.muted), modifier = Modifier.align(Alignment.Center))
        } else {
            TrailerPlaying(app, request, m, onClose, toast)
        }
    }
}

@Composable
private fun TrailerPlaying(app: LauncherApp, request: TrailerRequest, media: TrailerMedia, onClose: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(media) { trailerPlayer(context, media, muted = false) }
    DisposableEffect(player) { onDispose { player.release() } }

    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var bar by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            length = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }
    // The bar hides 3 seconds after the last press
    LaunchedEffect(touched) {
        bar = true
        delay(3000)
        bar = false
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { playFocus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key == Key.Back) return@onPreviewKeyEvent false
            val hidden = !bar
            if (e.isDown()) touched++
            // With the bar hidden, a press only brings it back
            hidden
        },
    ) {
        var up by remember { mutableStateOf(false) }
        PlayerSurface(
            player, fill = false, modifier = Modifier.fillMaxSize(),
            onFirstFrame = { up = true },
            onEnded = onClose,
            onError = {
                val link = request.youtubeLink
                if (link != null && app.smartTube.open(link)) onClose()
                else {
                    toast("The trailer stopped: it couldn't be played")
                    onClose()
                }
            },
        )
        // Until the picture's up, say it's coming
        if (!up) Text("Loading the trailer…", style = Tv.text(13f, color = Tv.muted), modifier = Modifier.align(Alignment.Center))
        AnimatedVisibility(bar, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xBF06080C), 0.22f to Color.Transparent, 0.58f to Color.Transparent, 1f to Color(0xEB06080C))))
        }
        AnimatedVisibility(bar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.padding(start = 48.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("TRAILER", style = Tv.kicker)
                Text(request.title, style = Tv.text(26f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(bar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Row(
                Modifier.padding(top = 38.dp, end = 48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x9906080C))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Ic.sound, null, Modifier.size(12.dp), tint = Tv.ink2)
                Text("Sound on", style = Tv.text(10f, color = Tv.ink2))
            }
        }
        // The buttons stay (see-through with the bar hidden), so focus stays where it was
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 22.dp)
                .alpha(if (bar) 1f else 0f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val play = request.onPlay
                if (request.playLabel != null && play != null) {
                    PillButton(request.playLabel, play, icon = Ic.play, primary = true, focusRequester = playFocus, height = 36.dp, textSize = 12f)
                }
                PillButton("Back", onClose, icon = Ic.back, height = 36.dp, textSize = 12f, focusRequester = if (request.onPlay == null) playFocus else null)
            }
            SkipBar(position, length, onSkip = { by ->
                player.seekTo((player.currentPosition + by).coerceIn(0, (length - 500).coerceAtLeast(0)))
            })
            Text("Back goes back  ·  ▼ then ◀ ▶ to skip 10 s  ·  this bar hides after 3 s", style = Tv.text(9f, color = Tv.muted))
        }
    }
}

/** The trailer's progress; focused, ◀ ▶ skip 10 seconds. */
@Composable
private fun SkipBar(position: Long, length: Long, onSkip: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(22.dp).tvItem(
            onClick = {},
            onFocusChange = { focused = it },
            onKey = { e ->
                when (e.key) {
                    Key.DirectionLeft -> { if (e.isDown()) onSkip(-10_000); true }
                    Key.DirectionRight -> { if (e.isDown()) onSkip(10_000); true }
                    else -> false
                }
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(time(position), style = Tv.text(10f, color = Tv.ink2))
        Box(Modifier.weight(1f).height(if (focused) 6.dp else 3.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
            val f = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(if (focused) Tv.ink else Tv.accent))
        }
        Text(time(length), style = Tv.text(10f, color = Tv.ink2))
    }
}

private fun time(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
