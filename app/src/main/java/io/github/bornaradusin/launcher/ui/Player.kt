package io.github.bornaradusin.launcher.ui

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.bornaradusin.launcher.trailer.TrailerMedia
import kotlinx.coroutines.delay

/** A trailer's player: made for one trailer, let go when it's no longer shown. */
@OptIn(UnstableApi::class)
fun trailerPlayer(context: Context, media: TrailerMedia, muted: Boolean): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(20_000)
    media.userAgent?.let { http.setUserAgent(it) }
    fun source(url: String): MediaSource {
        val item = MediaItem.fromUri(url)
        return if (url.contains(".m3u8")) HlsMediaSource.Factory(http).createMediaSource(item)
        else ProgressiveMediaSource.Factory(http).createMediaSource(item)
    }
    val src = if (media.audio != null) MergingMediaSource(source(media.video), source(media.audio)) else source(media.video)
    // Starts once a second is in (not two and a half), and keeps less ahead: a trailer, not a film
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_000, 2_000).build()
    return ExoPlayer.Builder(context).setLoadControl(load).build().apply {
        volume = if (muted) 0f else 1f
        setMediaSource(src)
        prepare()
        playWhenReady = true
    }
}

/**
 * Shows [player]'s picture: filling the space (cropped) behind Home, or whole for the full-screen
 * trailer. Tells when the first picture is up, when it ends and when it can't play.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    player: ExoPlayer,
    fill: Boolean,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onEnded: () -> Unit = {},
    onError: () -> Unit = {},
) {
    val first = rememberUpdatedState(onFirstFrame)
    val ended = rememberUpdatedState(onEnded)
    val error = rememberUpdatedState(onError)
    val seen = remember(player) { booleanArrayOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                seen[0] = true
                first.value()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended.value()
            }
            override fun onPlayerError(e: PlaybackException) = error.value()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // A trailer that sits loading (YouTube sending it too slowly, or not at all) counts as one that
    // can't play, rather than leaving a black screen: 12 seconds to start, 15 to carry on after a stall
    LaunchedEffect(player) {
        var waited = 0
        while (true) {
            delay(1_000)
            val state = player.playbackState
            if (state == Player.STATE_ENDED) break
            waited = if (state == Player.STATE_BUFFERING && player.playWhenReady) waited + 1 else 0
            if (waited >= (if (seen[0]) 15 else 12)) {
                error.value()
                break
            }
        }
    }
    val context = LocalContext.current
    val view = remember(player) {
        PlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = false
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            this.player = player
        }
    }
    AndroidView({ view }, modifier)
}
