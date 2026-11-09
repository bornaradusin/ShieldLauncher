package io.github.bornaradusin.launcher.seerr

import io.github.bornaradusin.launcher.SeerrAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Where a title stands: on Emby, asked for, being fetched, turned down, or nothing yet. */
enum class MediaState { NONE, PENDING, PROCESSING, PARTLY, AVAILABLE, DECLINED }

/** A film or show as Seerr (TMDB) knows it. */
data class SeerrTitle(
    val tmdbId: Int,
    val movie: Boolean,
    val title: String,
    val year: String?,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val state: MediaState,
    /** The item on Emby, when it's there. */
    val embyId: String? = null,
) {
    /** One id per title: TMDB's, or Emby's for a title Emby has without a TMDB id. */
    val key get() = if (tmdbId > 0) (if (movie) "m" else "t") + tmdbId else "e$embyId"
}

/** A season of a show: how many episodes, where it stands, and whether it can be asked for. */
data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val state: MediaState) {
    val requestable get() = state == MediaState.NONE || state == MediaState.DECLINED
}

/** Everything the details need: runtime, genres, seasons, the trailer and Seerr's own media id. */
data class SeerrDetails(
    val title: SeerrTitle,
    val runtime: Int,
    val genres: List<String>,
    val seasons: List<SeerrSeason>,
    val trailer: String?,
    val mediaId: Int?,
)

class SeerrException(message: String) : IOException(message)

/**
 * Seerr's API, with its API key (Seerr › Settings › General): what's trending and popular, search,
 * requests, and deleting from the server (through Radarr and Sonarr, the way Seerr's own Manage does).
 */
class Seerr(private val http: OkHttpClient) {
    private val details = ConcurrentHashMap<String, Pair<Long, SeerrDetails>>()

    /** Checks the address and key, and finds whose they are. */
    suspend fun connect(server: String, apiKey: String): SeerrAccount = withContext(Dispatchers.IO) {
        val base = normalise(server)
        val key = apiKey.trim()
        if (key.isBlank()) throw SeerrException("Paste Seerr's API key (Seerr › Settings › General › API Key)")
        val me = try {
            get(base, key, "/api/v1/auth/me") as JSONObject
        } catch (e: SeerrException) {
            throw e
        } catch (e: Exception) {
            throw SeerrException("Couldn't reach $base (${e.message ?: e.javaClass.simpleName})")
        }
        val name = listOf("displayName", "jellyfinUsername", "username", "plexUsername", "email")
            .map { me.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: "Seerr"
        SeerrAccount(base, key, name, me.optInt("id"))
    }

    suspend fun trending(a: SeerrAccount) = discover(a, "/api/v1/discover/trending")
    suspend fun popularFilms(a: SeerrAccount) = discover(a, "/api/v1/discover/movies")
    suspend fun popularShows(a: SeerrAccount) = discover(a, "/api/v1/discover/tv")
    suspend fun upcomingFilms(a: SeerrAccount) = discover(a, "/api/v1/discover/movies/upcoming")
    suspend fun upcomingShows(a: SeerrAccount) = discover(a, "/api/v1/discover/tv/upcoming")

    suspend fun search(a: SeerrAccount, query: String): List<SeerrTitle> = withContext(Dispatchers.IO) {
        // Seerr wants spaces as %20, not +
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        results(get(a, "/api/v1/search?query=$q&page=1") as JSONObject)
    }

    private suspend fun discover(a: SeerrAccount, path: String): List<SeerrTitle> = withContext(Dispatchers.IO) {
        val first = results(get(a, "$path?page=1") as JSONObject)
        val second = runCatching { results(get(a, "$path?page=2") as JSONObject) }.getOrDefault(emptyList())
        (first + second).distinctBy { it.key }
    }

    /** Your requests, newest first, with each title's name and poster. */
    suspend fun requests(a: SeerrAccount): List<SeerrTitle> = withContext(Dispatchers.IO) {
        val o = get(a, "/api/v1/request?take=30&skip=0&sort=added&filter=all") as JSONObject
        val arr = o.optJSONArray("results") ?: JSONArray()
        val wanted = (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            val media = r.optJSONObject("media") ?: return@mapNotNull null
            val tmdb = media.optInt("tmdbId").takeIf { it > 0 } ?: return@mapNotNull null
            Triple(tmdb, media.optString("mediaType") == "movie", r.optInt("status"))
        }.distinctBy { it.first to it.second }
        val gate = Semaphore(6)
        coroutineScope {
            wanted.map { (tmdb, movie, status) ->
                async {
                    gate.withPermit {
                        runCatching {
                            val t = details(a, tmdb, movie).title
                            if (status == 3) t.copy(state = MediaState.DECLINED) else t
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** A title's details (kept for a few minutes, so rows and the details open at once). */
    suspend fun details(a: SeerrAccount, tmdbId: Int, movie: Boolean, fresh: Boolean = false): SeerrDetails = withContext(Dispatchers.IO) {
        val key = (if (movie) "m" else "t") + tmdbId
        if (!fresh) details[key]?.let { (at, d) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return@withContext d }
        val o = get(a, if (movie) "/api/v1/movie/$tmdbId" else "/api/v1/tv/$tmdbId") as JSONObject
        val info = o.optJSONObject("mediaInfo")
        val title = title(o, if (movie) "movie" else "tv") ?: throw SeerrException("Seerr doesn't know that one")
        val seasons = if (movie) emptyList() else seasons(o, info)
        val trailer = o.optJSONArray("relatedVideos")?.let { v ->
            (0 until v.length()).map { v.getJSONObject(it) }
                .firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
                ?.let { "https://www.youtube.com/watch?v=" + it.optString("key") }
        }
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("name") } } ?: emptyList()
        val runtime = if (movie) o.optInt("runtime") else o.optJSONArray("episodeRunTime")?.optInt(0) ?: 0
        val d = SeerrDetails(title, runtime, genres, seasons, trailer, info?.optInt("id")?.takeIf { it > 0 })
        details[key] = System.currentTimeMillis() to d
        d
    }

    /** Asks Seerr for a film, or for [seasons] of a show. */
    suspend fun request(a: SeerrAccount, t: SeerrTitle, seasons: List<Int> = emptyList()) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("mediaType", if (t.movie) "movie" else "tv").put("mediaId", t.tmdbId)
        if (!t.movie) body.put("seasons", JSONArray(seasons))
        send(a, "POST", "/api/v1/request", body)
        details.remove(t.key)
    }

    /**
     * Deletes it from the server: Seerr has Radarr (films) or Sonarr (shows) remove it with its files,
     * then forgets it, so it can be asked for again. Emby drops it at its next library scan.
     */
    suspend fun delete(a: SeerrAccount, t: SeerrTitle) = withContext(Dispatchers.IO) {
        if (t.tmdbId <= 0) throw SeerrException("Emby has no TMDB id for ${t.title}, so Seerr can't find it: refresh its metadata in Emby")
        val id = details(a, t.tmdbId, t.movie, fresh = true).mediaId
            ?: throw SeerrException("Seerr doesn't have ${t.title} yet: run Seerr's Emby library scan, then try again")
        send(a, "DELETE", "/api/v1/media/$id/file?is4k=false", null)
        runCatching { send(a, "DELETE", "/api/v1/media/$id", null) }
        details.remove(t.key)
    }

    private fun seasons(o: JSONObject, info: JSONObject?): List<SeerrSeason> {
        val states = HashMap<Int, MediaState>()
        info?.optJSONArray("seasons")?.let { s ->
            for (i in 0 until s.length()) s.getJSONObject(i).let { states[it.optInt("seasonNumber")] = state(it.optInt("status")) }
        }
        // Seasons asked for (and not turned down) count as asked for, whatever the media says yet
        info?.optJSONArray("requests")?.let { reqs ->
            for (i in 0 until reqs.length()) {
                val r = reqs.getJSONObject(i)
                val status = r.optInt("status")
                if (status == 3) continue
                val ss = r.optJSONArray("seasons") ?: continue
                for (j in 0 until ss.length()) {
                    val n = ss.getJSONObject(j).optInt("seasonNumber")
                    val now = states[n] ?: MediaState.NONE
                    if (now == MediaState.NONE) states[n] = if (status == 2) MediaState.PROCESSING else MediaState.PENDING
                }
            }
        }
        val arr = o.optJSONArray("seasons") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optInt("seasonNumber") > 0 }
            .map { s ->
                val n = s.optInt("seasonNumber")
                SeerrSeason(n, s.optString("name").ifBlank { "Season $n" }, s.optInt("episodeCount"), states[n] ?: MediaState.NONE)
            }
    }

    private fun results(o: JSONObject): List<SeerrTitle> {
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            title(r, r.optString("mediaType"))
        }
    }

    private fun title(r: JSONObject, type: String): SeerrTitle? {
        if (type != "movie" && type != "tv") return null
        val movie = type == "movie"
        val name = r.optString(if (movie) "title" else "name").ifBlank { r.optString("originalTitle", r.optString("originalName")) }
        if (name.isBlank()) return null
        val date = r.optString(if (movie) "releaseDate" else "firstAirDate")
        val info = r.optJSONObject("mediaInfo")
        return SeerrTitle(
            tmdbId = r.optInt("id"),
            movie = movie,
            title = name,
            year = date.take(4).takeIf { it.length == 4 },
            overview = r.optString("overview"),
            poster = r.optString("posterPath").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w342$it" },
            backdrop = r.optString("backdropPath").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w1280$it" },
            rating = r.optDouble("voteAverage", 0.0).let { if (it.isNaN()) 0.0 else it },
            state = state(info?.optInt("status") ?: 0),
            embyId = info?.optString("jellyfinMediaId")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun state(status: Int) = when (status) {
        2 -> MediaState.PENDING
        3 -> MediaState.PROCESSING
        4 -> MediaState.PARTLY
        5 -> MediaState.AVAILABLE
        else -> MediaState.NONE
    }

    private fun get(a: SeerrAccount, path: String): Any = get(a.server, a.apiKey, path)

    private fun get(base: String, key: String, path: String): Any {
        val req = Request.Builder().url(base + path).header("X-Api-Key", key).header("Accept", "application/json").build()
        return http.newCall(req).execute().use { res ->
            when {
                res.code == 401 || res.code == 403 -> throw SeerrException("Seerr didn't take the API key: paste it again from Seerr › Settings › General")
                !res.isSuccessful -> throw SeerrException("Seerr answered ${res.code}")
            }
            val text = res.body?.string().orEmpty().trim()
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }

    private fun send(a: SeerrAccount, method: String, path: String, body: JSONObject?) {
        val rb = body?.toString()?.toRequestBody(JSON) ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
        val req = Request.Builder().url(a.server + path).header("X-Api-Key", a.apiKey).header("Accept", "application/json").method(method, rb).build()
        http.newCall(req).execute().use { res ->
            if (res.isSuccessful) return
            val message = runCatching { JSONObject(res.body?.string().orEmpty()).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            throw SeerrException(
                when (res.code) {
                    403 -> message ?: "Seerr says the API key's user can't do that"
                    409 -> "It's been asked for already"
                    else -> message ?: "Seerr answered ${res.code}"
                },
            )
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** "192.168.1.20:5055", "seerr.example.com" or a full address, as one the app can use. */
        fun normalise(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.removeSuffix("/api/v1")
            val rest = s.substringAfter("://")
            if (s.startsWith("http://") && !rest.contains(':') && !rest.contains('/')) s = "$s:5055"
            return s
        }
    }
}
