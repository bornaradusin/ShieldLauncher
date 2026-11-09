package io.github.bornaradusin.launcher.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.bornaradusin.launcher.YouTubeAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** A YouTube video for the Recommended and Subscriptions rows. */
data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val detail: String,
    val duration: String,
    val thumbnail: String,
) {
    val backdrop get() = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
}

/** The code to type at google.com/device, and what the launcher waits with. */
data class YtCode(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresAt: Long)

sealed interface YtPoll {
    data object Waiting : YtPoll
    data object SlowDown : YtPoll
    data class Done(val account: YouTubeAccount) : YtPoll
    data class Failed(val message: String) : YtPoll
}

/**
 * YouTube, signed in the way TV apps are (a code typed on the phone at google.com/device): the home
 * feed and subscriptions for the rows. Videos open in SmartTube.
 */
class YouTube(private val context: Context, private val http: OkHttpClient) {

    suspend fun startSignIn(): YtCode = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("scope", SCOPES)
            .put("device_id", UUID.randomUUID().toString().replace("-", ""))
            .put("device_model", "ytlr::")
        val o = postJson("https://www.youtube.com/o/oauth2/device/code", body)
        YtCode(
            userCode = o.getString("user_code"),
            url = o.optString("verification_url", "https://www.google.com/device"),
            deviceCode = o.getString("device_code"),
            interval = o.optInt("interval", 5),
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
        )
    }

    suspend fun poll(code: YtCode): YtPoll = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("code", code.deviceCode)
            .put("grant_type", "http://oauth.net/grant_type/device/1.0")
        val o = try {
            postJson("https://www.youtube.com/o/oauth2/token", body, allowError = true)
        } catch (e: IOException) {
            return@withContext YtPoll.Waiting
        }
        when (o.optString("error")) {
            "" -> YtPoll.Done(
                YouTubeAccount(
                    refreshToken = o.getString("refresh_token"),
                    accessToken = o.getString("access_token"),
                    expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000,
                ),
            )
            "authorization_pending" -> YtPoll.Waiting
            "slow_down" -> YtPoll.SlowDown
            "expired_token" -> YtPoll.Failed("The code ran out. Start again for a new one.")
            "access_denied" -> YtPoll.Failed("Sign-in was turned down on the phone.")
            else -> YtPoll.Failed("YouTube said: ${o.optString("error")}")
        }
    }

    /** A fresh access token, if the one kept has run out. */
    suspend fun fresh(a: YouTubeAccount): YouTubeAccount = withContext(Dispatchers.IO) {
        if (a.accessToken.isNotBlank() && a.expiresAt - System.currentTimeMillis() > 60_000) return@withContext a
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("refresh_token", a.refreshToken)
            .put("grant_type", "refresh_token")
        val o = postJson("https://www.youtube.com/o/oauth2/token", body, allowError = true)
        if (o.has("error")) throw YtSignedOut()
        a.copy(accessToken = o.getString("access_token"), expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
    }

    suspend fun recommended(a: YouTubeAccount): List<YtVideo> = browse(a, "FEwhat_to_watch")
    suspend fun subscriptions(a: YouTubeAccount): List<YtVideo> = browse(a, "FEsubscriptions")

    private suspend fun browse(a: YouTubeAccount, browseId: String): List<YtVideo> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject().put("clientName", "TVHTML5").put("clientVersion", TV_VERSION).put("hl", "en").put("gl", "IE")))
            .put("browseId", browseId)
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("Authorization", "Bearer ${a.accessToken}")
            .header("User-Agent", TV_AGENT)
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", TV_VERSION)
            .header("Origin", "https://www.youtube.com")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val o = http.newCall(req).execute().use { res ->
            if (res.code == 401) throw YtSignedOut()
            if (!res.isSuccessful) throw IOException("YouTube answered ${res.code}")
            JSONObject(res.body?.string().orEmpty())
        }
        val out = LinkedHashMap<String, YtVideo>()
        walk(o) { v -> if (v.id !in out) out[v.id] = v }
        out.values.take(40)
    }

    /** Every video in the answer, wherever it sits: the TV feed's tiles, or the web's video items. */
    private fun walk(node: Any?, found: (YtVideo) -> Unit) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("tileRenderer")?.let { tile(it)?.let(found) }
                for (k in listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer")) {
                    node.optJSONObject(k)?.let { video(it)?.let(found) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "tileRenderer" || k.endsWith("VideoRenderer") || k == "videoRenderer") continue
                    walk(node.opt(k), found)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), found)
        }
    }

    private fun tile(t: JSONObject): YtVideo? {
        val id = t.optJSONObject("onSelectCommand")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?.takeIf { it.isNotBlank() } ?: return null
        val meta = t.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val title = text(meta?.opt("title"))
        val lines = mutableListOf<String>()
        meta?.optJSONArray("lines")?.let { arr ->
            for (i in 0 until arr.length()) {
                val items = arr.optJSONObject(i)?.optJSONObject("lineRenderer")?.optJSONArray("items") ?: continue
                val parts = (0 until items.length()).mapNotNull { j -> text(items.optJSONObject(j)?.optJSONObject("lineItemRenderer")?.opt("text")).takeIf { it.isNotBlank() && it != "•" } }
                if (parts.isNotEmpty()) lines += parts.joinToString(" · ")
            }
        }
        val header = t.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        var duration = ""
        header?.optJSONArray("thumbnailOverlays")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.opt("text")
                if (s != null) duration = text(s)
            }
        }
        val thumb = biggest(header?.optJSONObject("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        if (title.isBlank()) return null
        return YtVideo(id, title, lines.getOrElse(0) { "" }, lines.getOrElse(1) { "" }, duration, thumb)
    }

    private fun video(v: JSONObject): YtVideo? {
        val id = v.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(v.opt("title"))
        val channel = text(v.opt("ownerText") ?: v.opt("shortBylineText") ?: v.opt("longBylineText"))
        val detail = listOf(text(v.opt("viewCountText")), text(v.opt("publishedTimeText"))).filter { it.isNotBlank() }.joinToString(" · ")
        val duration = text(v.opt("lengthText"))
        val thumb = biggest(v.optJSONObject("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        if (title.isBlank()) return null
        return YtVideo(id, title, channel, detail, duration, thumb)
    }

    private fun biggest(thumb: JSONObject?): String? {
        val arr = thumb?.optJSONArray("thumbnails") ?: return null
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (best == null || t.optInt("width") > best.optInt("width")) best = t
        }
        val url = best?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun text(o: Any?): String = when (o) {
        is String -> o
        is JSONObject -> o.optString("simpleText").takeIf { it.isNotBlank() }
            ?: o.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }
            ?: o.optString("content")
        else -> ""
    }

    private fun postJson(url: String, body: JSONObject, allowError: Boolean = false): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", TV_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful && !(allowError && text.trim().startsWith("{"))) throw IOException("YouTube answered ${res.code}")
            JSONObject(text)
        }
    }

    /** SmartTube if it's installed (the stable one, else the beta), else whatever opens YouTube links. */
    fun player(): String? = SMARTTUBE.firstOrNull { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false) }

    fun watch(videoId: String): Boolean = open("https://www.youtube.com/watch?v=$videoId")

    /** A YouTube link (a trailer, say) in SmartTube. */
    fun open(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        player()?.let { intent.setPackage(it) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    class YtSignedOut : IOException("YouTube signed the launcher out")

    companion object {
        private val JSON = "application/json".toMediaType()

        // The YouTube TV app's own sign-in (what SmartTube and other TV players use)
        private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
        private const val SCOPES = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube"
        private const val TV_VERSION = "7.20250312.16.00"
        private const val TV_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

        val SMARTTUBE = listOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
    }
}
