package io.github.bornaradusin.launcher.iptv

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import io.github.bornaradusin.launcher.IptvAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One of the provider's categories (of channels, films or series). */
data class XCategory(val id: String, val name: String, val adult: Boolean)

/** A live channel. [id] is the provider's stream id. */
data class XChannel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val category: String,
    val ext: String?,
    val adult: Boolean,
)

/** A film from the provider's catalogue. */
data class XFilm(
    val id: String,
    val name: String,
    val poster: String?,
    val category: String,
    val rating: Float,
    val added: Long,
    val ext: String,
    val adult: Boolean,
)

/** A series from the provider's catalogue (its seasons and episodes come with [SeriesInfo]). */
data class XSeries(
    val id: String,
    val name: String,
    val poster: String?,
    val category: String,
    val rating: Float,
    val added: Long,
    val plot: String,
    val year: String,
    val genre: String,
    val backdrop: String?,
    val adult: Boolean,
)

/** A programme in the guide; times in milliseconds. */
data class Programme(val start: Long, val end: Long, val title: String, val description: String)

/** What get_vod_info says about a film. */
data class FilmInfo(
    val plot: String,
    val genre: String,
    val year: String,
    val seconds: Int,
    val rating: Float,
    val backdrop: String?,
    val poster: String?,
    /** The YouTube trailer's id or link. */
    val trailer: String?,
    val ext: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val seconds: Int,
    val plot: String,
    val image: String?,
)

/** What get_series_info says about a series: its seasons and their episodes. */
data class SeriesInfo(
    val plot: String,
    val genre: String,
    val years: String,
    val rating: Float,
    val backdrop: String?,
    val poster: String?,
    val seasons: List<Int>,
    val episodes: List<Episode>,
    val trailer: String?,
)

/** The account, as the provider sees it. */
data class AccountInfo(val status: String, val expires: Long?, val active: Int, val max: Int)

class XtreamException(message: String, val auth: Boolean = false, val transport: Boolean = false) : Exception(message)

/**
 * The Xtream Codes API (player_api.php): categories and their channels, films and series, a channel's
 * guide, and the addresses that play them. Big lists are read as they arrive (never held whole as text),
 * and a transfer that's still moving is never cut off: only one that stalls (OkHttp's read timeout).
 */
class Xtream(private val http: OkHttpClient) {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun api(a: IptvAccount, action: String?, extra: String = ""): String =
        "${a.server}/player_api.php?username=${enc(a.username)}&password=${enc(a.password)}" +
            (if (action != null) "&action=$action" else "") + extra

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", AGENT).build()

    private fun call(url: String): okhttp3.Response = try {
        http.newCall(request(url)).execute()
    } catch (e: IOException) {
        throw XtreamException("Can't reach the provider (${e.message ?: "no connection"})", transport = true)
    } catch (e: IllegalArgumentException) {
        throw XtreamException("That server address isn't right")
    }

    private fun check(r: okhttp3.Response) {
        if (r.code == 401 || r.code == 403) throw XtreamException("The provider turned the account away (${r.code})", auth = true)
        if (!r.isSuccessful) throw XtreamException("The provider answered ${r.code}", transport = r.code >= 500)
    }

    /** Opens [url] and hands its body to [read]; whatever goes wrong comes out as an [XtreamException]. */
    private fun <T> open(url: String, read: (JsonReader) -> T): T {
        call(url).use { r ->
            check(r)
            val body = r.body ?: throw XtreamException("The provider sent nothing back")
            return try {
                JsonReader(body.charStream()).use { read(it) }
            } catch (e: XtreamException) {
                throw e
            } catch (e: IOException) {
                throw XtreamException("The provider's answer was cut off (${e.message ?: "connection lost"})", transport = true)
            } catch (e: RuntimeException) {
                throw XtreamException("The provider's answer wasn't readable")
            }
        }
    }

    private fun text(url: String): String {
        call(url).use { r ->
            check(r)
            return try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw XtreamException("The provider's answer was cut off", transport = true)
            }
        }
    }

    /**
     * Checks the account: the server, user name and password work and the subscription is active.
     * Returns the server as it's kept ("http://host:port") and what the provider says about the account.
     */
    fun login(server: String, username: String, password: String): Pair<String, AccountInfo> {
        val root = normalise(server) ?: throw XtreamException("Enter the server's address, like http://tv.example.net:8080")
        if (username.isBlank() || password.isBlank()) throw XtreamException("Enter the user name and the password")
        val probe = IptvAccount(root, username.trim(), password)
        val body = text(api(probe, null))
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: throw XtreamException("That address doesn't answer like an Xtream server")
        val user = o.optJSONObject("user_info") ?: throw XtreamException("That address doesn't answer like an Xtream server")
        if (user.optString("auth") != "1") throw XtreamException("The user name or password isn't right", auth = true)
        val info = account(user)
        if (!info.status.equals("Active", true) && info.status.isNotBlank()) {
            throw XtreamException("The subscription isn't active (${info.status})", auth = true)
        }
        return root to info
    }

    fun account(a: IptvAccount): AccountInfo {
        val o = runCatching { JSONObject(text(api(a, null))) }.getOrNull()?.optJSONObject("user_info")
            ?: throw XtreamException("The provider didn't say how the account is")
        if (o.optString("auth") != "1") throw XtreamException("The provider turned the account away", auth = true)
        return account(o)
    }

    private fun account(user: JSONObject) = AccountInfo(
        status = user.optString("status"),
        expires = user.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 },
        active = user.optString("active_cons").toIntOrNull() ?: 0,
        max = user.optString("max_connections").toIntOrNull() ?: 0,
    )

    fun liveCategories(a: IptvAccount) = categories(a, "get_live_categories")
    fun filmCategories(a: IptvAccount) = categories(a, "get_vod_categories")
    fun seriesCategories(a: IptvAccount) = categories(a, "get_series_categories")

    private fun categories(a: IptvAccount, action: String): List<XCategory> = open(api(a, action)) { r ->
        val out = ArrayList<XCategory>()
        array(r) { o ->
            val id = o["category_id"] ?: return@array
            val name = o["category_name"].orEmpty()
            out += XCategory(id, name, isAdult(name) || o["is_adult"] == "1")
        }
        out.distinctBy { it.id }
    }

    /** The channels of [category] (null: all of them), each as it's read. */
    fun channels(a: IptvAccount, category: String?, each: (XChannel) -> Unit) =
        open(api(a, "get_live_streams", category?.let { "&category_id=${enc(it)}" } ?: "")) { r ->
            array(r) { o ->
                val id = o["stream_id"] ?: return@array
                val name = o["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@array
                each(
                    XChannel(
                        id = id,
                        num = o["num"]?.toDoubleOrNull()?.toInt() ?: 0,
                        name = name,
                        logo = o["stream_icon"]?.takeIf { it.startsWith("http") },
                        category = o["category_id"] ?: category.orEmpty(),
                        ext = o["container_extension"]?.takeIf { it.isNotBlank() },
                        adult = o["is_adult"] == "1",
                    ),
                )
            }
        }

    fun films(a: IptvAccount, category: String?, each: (XFilm) -> Unit) =
        open(api(a, "get_vod_streams", category?.let { "&category_id=${enc(it)}" } ?: "")) { r ->
            array(r) { o ->
                val id = o["stream_id"] ?: return@array
                val name = o["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@array
                each(
                    XFilm(
                        id = id,
                        name = name,
                        poster = o["stream_icon"]?.takeIf { it.startsWith("http") },
                        category = o["category_id"] ?: category.orEmpty(),
                        rating = rating(o["rating_5based"], o["rating"]),
                        added = o["added"]?.toLongOrNull()?.let { it * 1000 } ?: 0L,
                        ext = o["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4",
                        adult = o["is_adult"] == "1",
                    ),
                )
            }
        }

    fun series(a: IptvAccount, category: String?, each: (XSeries) -> Unit) =
        open(api(a, "get_series", category?.let { "&category_id=${enc(it)}" } ?: "")) { r ->
            array(r) { o ->
                val id = o["series_id"] ?: return@array
                val name = o["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@array
                each(
                    XSeries(
                        id = id,
                        name = name,
                        poster = o["cover"]?.takeIf { it.startsWith("http") },
                        category = o["category_id"] ?: category.orEmpty(),
                        rating = rating(o["rating_5based"], o["rating"]),
                        added = o["last_modified"]?.toLongOrNull()?.let { it * 1000 } ?: 0L,
                        plot = o["plot"].orEmpty(),
                        year = (o["releaseDate"] ?: o["release_date"]).orEmpty().take(4),
                        genre = o["genre"].orEmpty(),
                        backdrop = o["backdrop_path"]?.takeIf { it.startsWith("http") },
                        adult = o["is_adult"] == "1",
                    ),
                )
            }
        }

    fun filmInfo(a: IptvAccount, id: String): FilmInfo {
        val o = runCatching { JSONObject(text(api(a, "get_vod_info", "&vod_id=${enc(id)}"))) }.getOrNull()
            ?: throw XtreamException("The provider has nothing more about this film")
        val info = o.optJSONObject("info") ?: JSONObject()
        val data = o.optJSONObject("movie_data") ?: JSONObject()
        return FilmInfo(
            plot = info.str("plot").ifBlank { info.str("description") },
            genre = info.str("genre"),
            year = info.str("releasedate").ifBlank { info.str("release_date") }.take(4).ifBlank { info.str("year") },
            seconds = info.str("duration_secs").toIntOrNull() ?: seconds(info.str("duration")),
            rating = rating(null, info.str("rating")),
            backdrop = picture(info.opt("backdrop_path")),
            poster = info.str("movie_image").takeIf { it.startsWith("http") } ?: info.str("cover_big").takeIf { it.startsWith("http") },
            trailer = info.str("youtube_trailer").takeIf { it.isNotBlank() },
            ext = data.str("container_extension").takeIf { it.isNotBlank() },
        )
    }

    fun seriesInfo(a: IptvAccount, id: String): SeriesInfo {
        val o = runCatching { JSONObject(text(api(a, "get_series_info", "&series_id=${enc(id)}"))) }.getOrNull()
            ?: throw XtreamException("The provider has nothing more about this series")
        val info = o.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<Episode>()
        fun add(season: Int, arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val epId = e.str("id").takeIf { it.isNotBlank() } ?: continue
                val ei = e.optJSONObject("info") ?: JSONObject()
                episodes += Episode(
                    id = epId,
                    season = e.str("season").toIntOrNull() ?: season,
                    number = e.str("episode_num").toIntOrNull() ?: (i + 1),
                    title = e.str("title").ifBlank { "Episode ${i + 1}" },
                    ext = e.str("container_extension").ifBlank { "mp4" },
                    seconds = ei.str("duration_secs").toIntOrNull() ?: seconds(ei.str("duration")),
                    plot = ei.str("plot"),
                    image = ei.str("movie_image").takeIf { it.startsWith("http") },
                )
            }
        }
        // By season ("1": [...]), or (some panels) a list of seasons' lists
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { add(k.toIntOrNull() ?: 0, it) } }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val s = eps.opt(i)) {
                    is JSONArray -> add(i + 1, s)
                    is JSONObject -> add(s.str("season").toIntOrNull() ?: 0, JSONArray().put(s))
                }
            }
        }
        val sorted = episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        return SeriesInfo(
            plot = info.str("plot"),
            genre = info.str("genre"),
            years = info.str("releaseDate").ifBlank { info.str("release_date") }.take(4),
            rating = rating(null, info.str("rating")),
            backdrop = picture(info.opt("backdrop_path")),
            poster = info.str("cover").takeIf { it.startsWith("http") },
            seasons = sorted.map { it.season }.distinct(),
            episodes = sorted,
            trailer = info.str("youtube_trailer").takeIf { it.isNotBlank() },
        )
    }

    /** What's on [streamId] from now: up to [limit] programmes. */
    fun guide(a: IptvAccount, streamId: String, limit: Int = 40): List<Programme> {
        val o = runCatching { JSONObject(text(api(a, "get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit"))) }.getOrNull()
            ?: return emptyList()
        val list = o.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Programme>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val start = p.str("start_timestamp").toLongOrNull() ?: continue
            val end = p.str("stop_timestamp").toLongOrNull() ?: p.str("end_timestamp").toLongOrNull() ?: continue
            if (end <= start) continue
            out += Programme(start * 1000, end * 1000, unbase64(p.str("title")).trim(), unbase64(p.str("description")).trim())
        }
        return out.sortedBy { it.start }.distinctBy { it.start }
    }

    fun liveUrl(a: IptvAccount, c: XChannel): String =
        "${a.server}/live/${enc(a.username)}/${enc(a.password)}/${c.id}.${if (c.ext == "m3u8") "m3u8" else "ts"}"

    fun filmUrl(a: IptvAccount, id: String, ext: String): String =
        "${a.server}/movie/${enc(a.username)}/${enc(a.password)}/$id.$ext"

    fun episodeUrl(a: IptvAccount, e: Episode): String =
        "${a.server}/series/${enc(a.username)}/${enc(a.password)}/${e.id}.${e.ext}"

    // ---- reading -----------------------------------------------------------------------------------

    /** Each object of the top-level array as plain values (nested ones skipped); anything else: none. */
    private fun array(r: JsonReader, each: (Map<String, String?>) -> Unit) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue()
                        continue
                    }
                    val o = HashMap<String, String?>(24)
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        o[name] = value(r)
                    }
                    r.endObject()
                    each(o)
                }
                r.endArray()
            }
            // Some panels answer an object of objects, keyed by id
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue()
                        continue
                    }
                    val o = HashMap<String, String?>(24)
                    r.beginObject()
                    while (r.hasNext()) o[r.nextName()] = value(r)
                    r.endObject()
                    each(o)
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }

    private fun value(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> if (r.nextBoolean()) "1" else "0"
        JsonToken.NULL -> {
            r.nextNull()
            null
        }
        else -> {
            r.skipValue()
            null
        }
    }

    private fun JSONObject.str(name: String): String = when (val v = opt(name)) {
        null, JSONObject.NULL -> ""
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> ""
    }

    private fun picture(v: Any?): String? = when (v) {
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
        is String -> v.takeIf { it.startsWith("http") }
        else -> null
    }

    private fun rating(five: String?, ten: String?): Float {
        ten?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f }?.let { return it.coerceAtMost(10f) }
        five?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f }?.let { return (it * 2).coerceAtMost(10f) }
        return 0f
    }

    private fun seconds(hms: String): Int {
        val parts = hms.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> hms.toIntOrNull()?.times(60) ?: 0
        }
    }

    companion object {
        /** IPTV panels let players in; some turn away what doesn't look like one. */
        const val AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        private val ADULT = Regex("(?i)(\\bxxx\\b|adult|\\b18\\+|for adults|porn|erotic)")

        fun isAdult(name: String) = ADULT.containsMatchIn(name)

        /** "tv.example.net:8080/" → "http://tv.example.net:8080"; null when it isn't an address. */
        fun normalise(server: String): String? {
            var s = server.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            // Pasted with the page or a playlist on the end
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/xmltv.php").trimEnd('/')
            val host = s.substringAfter("://").substringBefore('/')
            if (host.isBlank() || host.contains(' ')) return null
            return s
        }

        /** Programme titles and descriptions come base64-encoded (from most panels). */
        fun unbase64(s: String): String {
            val t = s.trim()
            if (t.isEmpty() || t.length % 4 != 0 || !t.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return s
            return runCatching {
                val bytes = Base64.decode(t, Base64.DEFAULT)
                val decoded = String(bytes, Charsets.UTF_8)
                if (decoded.contains('�')) s else decoded
            }.getOrDefault(s)
        }
    }
}
