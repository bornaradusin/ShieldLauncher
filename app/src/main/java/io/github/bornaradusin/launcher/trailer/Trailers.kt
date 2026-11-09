package io.github.bornaradusin.launcher.trailer

import android.net.Uri
import io.github.bornaradusin.launcher.EmbyAccount
import io.github.bornaradusin.launcher.emby.Emby
import io.github.bornaradusin.launcher.emby.EmbyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/** A trailer's video, with its sound when that comes separately (YouTube), and how to ask for them. */
data class TrailerMedia(val video: String, val audio: String?, val userAgent: String?)

/**
 * Where a film's trailer comes from: one kept on the Emby server with the film, else the trailer
 * link Emby has for it (YouTube, read with NewPipe's extractor so it plays in the launcher).
 */
class Trailers(private val http: OkHttpClient, private val emby: Emby) {
    private val found = ConcurrentHashMap<String, Result<TrailerMedia?>>()
    @Volatile private var ready = false

    /** The trailer to play, or null if the film has none (or it can't be played here). */
    suspend fun media(a: EmbyAccount, item: EmbyItem): TrailerMedia? {
        found[item.id]?.let { return it.getOrNull() }
        val result = runCatching { resolve(a, item) }
        found[item.id] = result
        return result.getOrNull()
    }

    /** The trailer as a YouTube link, for SmartTube when it can't play here. */
    fun youtubeLink(item: EmbyItem): String? = item.remoteTrailers.firstOrNull { it.contains("youtu") }

    private suspend fun resolve(a: EmbyAccount, item: EmbyItem): TrailerMedia? = withContext(Dispatchers.IO) {
        if (item.localTrailers > 0) {
            emby.localTrailerUrl(a, item)?.let { return@withContext TrailerMedia(it, null, null) }
        }
        val link = item.remoteTrailers.firstOrNull() ?: return@withContext null
        if (!link.contains("youtu")) return@withContext TrailerMedia(link, null, null)
        youtube(link)
    }

    private fun youtube(link: String): TrailerMedia? {
        if (!ready) {
            synchronized(this) {
                if (!ready) {
                    NewPipe.init(OkDownloader(http))
                    ready = true
                }
            }
        }
        val info = StreamInfo.getInfo(link)
        val progressive = { m: DeliveryMethod -> m == DeliveryMethod.PROGRESSIVE_HTTP }
        val video = info.videoOnlyStreams
            .filter { it.isUrl && progressive(it.deliveryMethod) && it.height in 1..1080 }
            .maxByOrNull { it.height }
        val audio = info.audioStreams
            .filter { it.isUrl && progressive(it.deliveryMethod) }
            .maxByOrNull { it.averageBitrate }
        if (video != null && audio != null) {
            return TrailerMedia(video.content, audio.content, agentFor(video.content))
        }
        info.videoStreams
            .filter { it.isUrl && progressive(it.deliveryMethod) }
            .maxByOrNull { it.height }
            ?.let { return TrailerMedia(it.content, null, agentFor(it.content)) }
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return TrailerMedia(it, null, null) }
        return null
    }

    /** YouTube's video servers want to be asked the way the app that got the link would. */
    private fun agentFor(url: String): String {
        val uri = Uri.parse(url)
        val client = uri.getQueryParameter("c").orEmpty()
        val version = uri.getQueryParameter("cver").orEmpty()
        return when {
            client.startsWith("ANDROID_VR") -> "com.google.android.apps.youtube.vr.oculus/$version (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            client.startsWith("ANDROID") -> "com.google.android.youtube/$version (Linux; U; Android 14) gzip"
            client.startsWith("IOS") -> "com.google.ios.youtube/$version (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
            else -> DESKTOP
        }
    }

    /** NewPipe's extractor asks for pages through the launcher's own HTTP client. */
    private class OkDownloader(private val http: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
                ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null
            val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", DESKTOP)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            http.newCall(builder.build()).execute().use { res ->
                return Response(res.code, res.message, res.headers.toMultimap(), res.body?.string(), res.request.url.toString())
            }
        }
    }

    companion object {
        private const val DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
