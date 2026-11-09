package io.github.bornaradusin.launcher.emby

import io.github.bornaradusin.launcher.EmbyAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A film, episode or show from Emby, with what Home shows of it. */
data class EmbyItem(
    val id: String,
    val type: String,
    val name: String,
    val seriesName: String?,
    val seriesId: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val rating: String?,
    val overview: String?,
    val runTicks: Long,
    val positionTicks: Long,
    val played: Boolean,
    val genres: List<String>,
    /** Wide picture for the cards (an episode's still, a film's backdrop). */
    val wide: String?,
    /** The poster. */
    val poster: String?,
    /** The big picture behind the top of Home. */
    val backdrop: String?,
    val remoteTrailers: List<String>,
    val localTrailers: Int,
    /** Next up rather than started: the next episode of a show being watched. */
    val nextUp: Boolean = false,
) {
    val isEpisode get() = type == "Episode"
    val isSeries get() = type == "Series"
    val progress get() = if (runTicks > 0) (positionTicks.toFloat() / runTicks).coerceIn(0f, 1f) else 0f
    val minutesLeft get() = ((runTicks - positionTicks) / 600_000_000L).toInt()
    val minutes get() = (runTicks / 600_000_000L).toInt()
    val hasTrailer get() = localTrailers > 0 || remoteTrailers.isNotEmpty()
}

/** A row's items and how many there are in all. */
data class EmbyPage(val items: List<EmbyItem>, val total: Int)

/** A device that's using Emby right now. */
data class EmbySession(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val client: String,
    val remoteEndPoint: String,
    val supportsRemoteControl: Boolean,
    val lastActivity: String,
)

class EmbyException(message: String) : IOException(message)

/** Emby's server API: signing in, the rows, trailers and remote control. */
class Emby(private val http: OkHttpClient, private val deviceId: String, private val version: String) {

    private fun authHeader(userId: String = "") =
        "Emby UserId=\"$userId\", Client=\"Shield Launcher\", Device=\"SHIELD\", DeviceId=\"$deviceId\", Version=\"$version\""

    /** Signs in; the server address can be typed with or without http:// and the port. */
    suspend fun signIn(server: String, user: String, password: String): EmbyAccount = withContext(Dispatchers.IO) {
        val base = normalise(server)
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        val req = Request.Builder().url("$base/emby/Users/AuthenticateByName")
            .header("X-Emby-Authorization", authHeader())
            .post(body.toRequestBody(JSON))
            .build()
        val o = try {
            http.newCall(req).execute().use { res ->
                when {
                    res.code == 401 -> throw EmbyException("The user name or password isn't right")
                    !res.isSuccessful -> throw EmbyException("The server answered ${res.code}")
                    else -> JSONObject(res.body?.string().orEmpty())
                }
            }
        } catch (e: EmbyException) {
            throw e
        } catch (e: Exception) {
            throw EmbyException("Couldn't reach $base (${e.message ?: e.javaClass.simpleName})")
        }
        val u = o.getJSONObject("User")
        val serverName = runCatching { getJson(base, o.getString("AccessToken"), "/emby/System/Info").optString("ServerName") }.getOrNull()
        EmbyAccount(
            server = base,
            userId = u.getString("Id"),
            userName = u.optString("Name", user),
            token = o.getString("AccessToken"),
            serverId = o.optString("ServerId", u.optString("ServerId")),
            serverName = serverName ?: "Emby",
        )
    }

    suspend fun signOut(a: EmbyAccount) = withContext(Dispatchers.IO) {
        runCatching { post(a, "/emby/Sessions/Logout") }
    }

    /** Started films and episodes, then the next episode of shows being watched. */
    suspend fun continueWatching(a: EmbyAccount): EmbyPage = withContext(Dispatchers.IO) {
        val resume = items(a, "/emby/Users/${a.userId}/Items/Resume?Limit=24&MediaTypes=Video&Recursive=true&$FIELDS")
        val next = runCatching {
            items(a, "/emby/Shows/NextUp?UserId=${a.userId}&Limit=24&$FIELDS").items.map { it.copy(nextUp = true) }
        }.getOrDefault(emptyList())
        val started = resume.items.mapNotNull { it.seriesId }.toSet()
        val all = resume.items + next.filter { it.seriesId !in started }
        EmbyPage(all, all.size)
    }

    /** Films, the newest added first. */
    suspend fun movies(a: EmbyAccount): EmbyPage = withContext(Dispatchers.IO) {
        items(a, "/emby/Users/${a.userId}/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=40&$FIELDS")
    }

    /** Shows, the one with the newest episode first. */
    suspend fun shows(a: EmbyAccount): EmbyPage = withContext(Dispatchers.IO) {
        items(a, "/emby/Users/${a.userId}/Items?IncludeItemTypes=Series&Recursive=true&SortBy=DateLastContentAdded,DateCreated&SortOrder=Descending&Limit=40&$FIELDS")
    }

    /** A trailer kept on the server with the film: its video, to stream. */
    suspend fun localTrailerUrl(a: EmbyAccount, item: EmbyItem): String? = withContext(Dispatchers.IO) {
        val arr = getArray(a, "/emby/Users/${a.userId}/Items/${item.id}/LocalTrailers")
        val first = arr.optJSONObject(0) ?: return@withContext null
        "${a.server}/emby/Videos/${first.getString("Id")}/stream?static=true&api_key=${a.token}"
    }

    suspend fun markPlayed(a: EmbyAccount, item: EmbyItem) = withContext(Dispatchers.IO) {
        post(a, "/emby/Users/${a.userId}/PlayedItems/${item.id}")
    }

    /** Takes it off Continue watching without marking it watched. */
    suspend fun hideFromResume(a: EmbyAccount, item: EmbyItem) = withContext(Dispatchers.IO) {
        post(a, "/emby/Users/${a.userId}/Items/${item.id}/HideFromResume?Hide=true")
    }

    suspend fun sessions(a: EmbyAccount): List<EmbySession> = withContext(Dispatchers.IO) {
        val arr = getArray(a, "/emby/Sessions?ControllableByUserId=${a.userId}")
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            EmbySession(
                id = s.getString("Id"),
                deviceId = s.optString("DeviceId"),
                deviceName = s.optString("DeviceName"),
                client = s.optString("Client"),
                remoteEndPoint = s.optString("RemoteEndPoint"),
                supportsRemoteControl = s.optBoolean("SupportsRemoteControl", true),
                lastActivity = s.optString("LastActivityDate"),
            )
        }
    }

    /** Asks an Emby app to play [item] from [startTicks]. */
    suspend fun playOn(a: EmbyAccount, session: EmbySession, item: EmbyItem, startTicks: Long) = withContext(Dispatchers.IO) {
        post(a, "/emby/Sessions/${session.id}/Playing?ItemIds=${item.id}&PlayCommand=PlayNow&StartPositionTicks=$startTicks")
    }

    private fun items(a: EmbyAccount, path: String): EmbyPage {
        val o = getJson(a.server, a.token, path)
        val arr = o.optJSONArray("Items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { runCatching { item(a, arr.getJSONObject(it)) }.getOrNull() }
        return EmbyPage(list, o.optInt("TotalRecordCount", list.size))
    }

    private fun item(a: EmbyAccount, o: JSONObject): EmbyItem {
        val id = o.getString("Id")
        val type = o.optString("Type")
        val img = o.optJSONObject("ImageTags") ?: JSONObject()
        val backdrops = o.optJSONArray("BackdropImageTags") ?: JSONArray()
        val parentBackdrops = o.optJSONArray("ParentBackdropImageTags") ?: JSONArray()
        val user = o.optJSONObject("UserData") ?: JSONObject()
        fun image(owner: String, kind: String, tag: String, width: Int) =
            "${a.server}/emby/Items/$owner/Images/$kind?maxWidth=$width&tag=$tag&quality=90"

        val backdrop = when {
            backdrops.length() > 0 -> image(id, "Backdrop/0", backdrops.getString(0), 1920)
            parentBackdrops.length() > 0 -> image(o.optString("ParentBackdropItemId"), "Backdrop/0", parentBackdrops.getString(0), 1920)
            else -> null
        }
        val seriesId = o.optString("SeriesId").takeIf { it.isNotBlank() }
        val poster = when {
            type == "Episode" && seriesId != null && o.optString("SeriesPrimaryImageTag").isNotBlank() ->
                image(seriesId, "Primary", o.getString("SeriesPrimaryImageTag"), 400)
            img.has("Primary") -> image(id, "Primary", img.getString("Primary"), 400)
            else -> null
        }
        val wide = when {
            type == "Episode" && img.has("Primary") -> image(id, "Primary", img.getString("Primary"), 720)
            img.has("Thumb") -> image(id, "Thumb", img.getString("Thumb"), 720)
            o.optString("ParentThumbImageTag").isNotBlank() -> image(o.optString("ParentThumbItemId"), "Thumb", o.getString("ParentThumbImageTag"), 720)
            backdrops.length() > 0 -> image(id, "Backdrop/0", backdrops.getString(0), 720)
            parentBackdrops.length() > 0 -> image(o.optString("ParentBackdropItemId"), "Backdrop/0", parentBackdrops.getString(0), 720)
            else -> poster
        }
        val trailers = o.optJSONArray("RemoteTrailers")?.let { t -> (0 until t.length()).mapNotNull { t.optJSONObject(it)?.optString("Url") } }
            ?.filter { it.isNotBlank() } ?: emptyList()
        val genres = o.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList()
        return EmbyItem(
            id = id,
            type = type,
            name = o.optString("Name"),
            seriesName = o.optString("SeriesName").takeIf { it.isNotBlank() },
            seriesId = seriesId,
            season = o.optInt("ParentIndexNumber", -1).takeIf { it >= 0 },
            episode = o.optInt("IndexNumber", -1).takeIf { it >= 0 },
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            rating = o.optString("OfficialRating").takeIf { it.isNotBlank() },
            overview = o.optString("Overview").takeIf { it.isNotBlank() },
            runTicks = o.optLong("RunTimeTicks", 0),
            positionTicks = user.optLong("PlaybackPositionTicks", 0),
            played = user.optBoolean("Played", false),
            genres = genres,
            wide = wide,
            poster = poster,
            backdrop = backdrop ?: wide,
            remoteTrailers = trailers,
            localTrailers = o.optInt("LocalTrailerCount", 0),
        )
    }

    private fun getJson(base: String, token: String, path: String): JSONObject {
        val req = Request.Builder().url(base + path).header("X-Emby-Token", token).header("Accept", "application/json").build()
        return http.newCall(req).execute().use { res ->
            if (res.code == 401) throw EmbyException("Emby signed the launcher out: sign in again from your phone")
            if (!res.isSuccessful) throw EmbyException("Emby answered ${res.code}")
            JSONObject(res.body?.string().orEmpty())
        }
    }

    private fun getArray(a: EmbyAccount, path: String): JSONArray {
        val req = Request.Builder().url(a.server + path).header("X-Emby-Token", a.token).header("Accept", "application/json").build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw EmbyException("Emby answered ${res.code}")
            val text = res.body?.string().orEmpty().trim()
            if (text.startsWith("{")) JSONObject(text).optJSONArray("Items") ?: JSONArray() else JSONArray(text)
        }
    }

    private fun post(a: EmbyAccount, path: String) {
        val req = Request.Builder().url(a.server + path).header("X-Emby-Token", a.token)
            .header("X-Emby-Authorization", authHeader(a.userId))
            .post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(req).execute().use { res -> if (!res.isSuccessful) throw EmbyException("Emby answered ${res.code}") }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val FIELDS =
            "Fields=Overview,Genres,ProductionYear,OfficialRating,RemoteTrailers,LocalTrailerCount,ParentBackdropImageTags,PrimaryImageAspectRatio" +
                "&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1"

        /** "192.168.1.20:8096", "emby.example.com" or a full address, as one the app can use. */
        fun normalise(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.removeSuffix("/web/index.html").removeSuffix("/web").removeSuffix("/emby")
            // A bare address on plain http: Emby's usual port
            val rest = s.substringAfter("://")
            if (s.startsWith("http://") && !rest.contains(':') && !rest.contains('/')) s = "$s:8096"
            return s
        }
    }
}
