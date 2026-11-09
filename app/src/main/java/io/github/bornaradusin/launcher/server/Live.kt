package io.github.bornaradusin.launcher.server

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A live camera in a notification: a doorbell, say. RTSP, HLS and video files play in ExoPlayer;
 * MJPEG (what Home Assistant's camera_proxy_stream gives) is read frame by frame here.
 */
object Live {

    /** Starts [url] playing inside [frame]; the function returned stops it. */
    fun attach(context: Context, frame: FrameLayout, url: String): () -> Unit =
        if (mjpeg(url)) mjpegInto(context, frame, url) else playerInto(context, frame, url)

    private fun mjpeg(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http") && (u.contains("camera_proxy_stream") || u.contains("mjpg") || u.contains("mjpeg") || u.contains("action=stream"))
    }

    @OptIn(UnstableApi::class)
    private fun playerInto(context: Context, frame: FrameLayout, url: String): () -> Unit {
        val player = ExoPlayer.Builder(context).build()
        player.volume = 0f
        if (url.startsWith("rtsp", true)) {
            player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
        } else {
            player.setMediaItem(MediaItem.fromUri(url))
        }
        val view = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            this.player = player
        }
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                problem(context, frame, "The camera didn't play: ${error.errorCodeName}")
            }
        })
        player.prepare()
        player.playWhenReady = true
        return { player.release() }
    }

    private fun mjpegInto(context: Context, frame: FrameLayout, url: String): () -> Unit {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val main = Handler(Looper.getMainLooper())
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        var conn: HttpURLConnection? = null
        Thread {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                }
                conn = c
                BufferedInputStream(c.inputStream).use { input ->
                    while (live.get()) {
                        val jpeg = nextFrame(input) ?: break
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        main.post { if (live.get()) image.setImageBitmap(bmp) }
                    }
                }
            } catch (e: Exception) {
                if (live.get()) main.post { problem(context, frame, "The camera didn't play: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.apply { isDaemon = true }.start()
        return {
            live.set(false)
            runCatching { conn?.disconnect() }
        }
    }

    /** The next JPEG in a multipart MJPEG stream: from its FFD8 start to its FFD9 end. */
    private fun nextFrame(input: InputStream): ByteArray? {
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (prev == 0xFF && b == 0xD8) break
            prev = b
        }
        val out = ByteArrayOutputStream(64 * 1024)
        out.write(0xFF); out.write(0xD8)
        prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            prev = b
            if (out.size() > 8 * 1024 * 1024) return null
        }
    }

    private fun problem(context: Context, frame: FrameLayout, text: String) {
        frame.addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}
