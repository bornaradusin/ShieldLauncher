package io.github.bornaradusin.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The rows Home can show. */
enum class RowKind(val id: String, val title: String, val source: String) {
    APPS("apps", "Apps", "Your apps, in your order"),
    CONTINUE("continue", "Continue watching", "Emby"),
    MOVIES("movies", "Movies", "Emby"),
    SHOWS("shows", "TV shows", "Emby"),
    GAMES("games", "Games", "Artemis"),
    RECOMMENDED("recommended", "Recommended", "YouTube"),
    SUBSCRIPTIONS("subscriptions", "Subscriptions", "YouTube");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class RowSetting(val kind: RowKind, val on: Boolean)

/** Signed in to an Emby server. */
data class EmbyAccount(
    val server: String,
    val userId: String,
    val userName: String,
    val token: String,
    val serverId: String,
    val serverName: String,
)

/** Signed in to YouTube (the TV sign-in, with a code). */
data class YouTubeAccount(val refreshToken: String, val accessToken: String, val expiresAt: Long)

/** A game Artemis pinned to the launcher. */
data class Game(val pkg: String, val shortcutId: String, val name: String, val longName: String, val art: String)

data class Config(
    val rows: List<RowSetting>,
    /** The apps row, as "package/activity", in order. Null until it's first filled. */
    val apps: List<String>?,
    val emby: EmbyAccount?,
    /** The Emby app films and episodes play in; null: whichever is installed. */
    val embyApp: String?,
    val youtube: YouTubeAccount?,
    val games: List<Game>,
    /** A film's trailer plays, muted, behind the top of Home. */
    val trailers: Boolean,
)

/**
 * Everything the launcher keeps: the rows, the apps row, the accounts and the games. Kept as one JSON
 * text, and shown live (the phone's changes show on the TV straight away).
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config

    /** This Shield, to Emby: one id that stays, so Emby doesn't list a new device every start. */
    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }

    fun change(block: (Config) -> Config) {
        _config.update(block)
        prefs.edit().putString("config", write(_config.value).toString()).apply()
    }

    fun setRows(rows: List<RowSetting>) = change { c ->
        // Rows not mentioned keep their place at the end, so none can go missing
        val missing = c.rows.filter { r -> rows.none { it.kind == r.kind } }
        c.copy(rows = rows + missing)
    }

    fun setApps(apps: List<String>) = change { it.copy(apps = apps.distinct()) }

    private fun read(): Config {
        val text = prefs.getString("config", null) ?: return DEFAULT
        return runCatching { parse(JSONObject(text)) }.getOrDefault(DEFAULT)
    }

    companion object {
        val DEFAULT_ROWS = listOf(
            RowSetting(RowKind.APPS, true),
            RowSetting(RowKind.CONTINUE, true),
            RowSetting(RowKind.MOVIES, true),
            RowSetting(RowKind.SHOWS, true),
            RowSetting(RowKind.GAMES, true),
            RowSetting(RowKind.RECOMMENDED, true),
            RowSetting(RowKind.SUBSCRIPTIONS, false),
        )

        val DEFAULT = Config(DEFAULT_ROWS, null, null, null, null, emptyList(), true)

        private fun parse(o: JSONObject): Config {
            val rows = mutableListOf<RowSetting>()
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    RowKind.of(r.getString("id"))?.let { rows += RowSetting(it, r.optBoolean("on", true)) }
                }
            }
            // A row added in a newer version shows up at the end
            DEFAULT_ROWS.forEach { d -> if (rows.none { it.kind == d.kind }) rows += d }
            val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            val emby = o.optJSONObject("emby")?.let {
                EmbyAccount(
                    it.getString("server"), it.getString("userId"), it.optString("userName"), it.getString("token"),
                    it.optString("serverId"), it.optString("serverName"),
                )
            }
            val yt = o.optJSONObject("youtube")?.let {
                YouTubeAccount(it.getString("refresh"), it.optString("access"), it.optLong("expires"))
            }
            val games = o.optJSONArray("games")?.let { a ->
                (0 until a.length()).map { i ->
                    val g = a.getJSONObject(i)
                    Game(g.getString("pkg"), g.getString("id"), g.getString("name"), g.optString("long"), g.optString("art"))
                }
            } ?: emptyList()
            return Config(
                rows = rows,
                apps = apps,
                emby = emby,
                embyApp = o.optString("embyApp").takeIf { it.isNotBlank() },
                youtube = yt,
                games = games,
                trailers = o.optBoolean("trailers", true),
            )
        }

        private fun write(c: Config) = JSONObject().apply {
            put("rows", JSONArray().apply { c.rows.forEach { put(JSONObject().put("id", it.kind.id).put("on", it.on)) } })
            c.apps?.let { put("apps", JSONArray(it)) }
            c.emby?.let {
                put(
                    "emby", JSONObject().put("server", it.server).put("userId", it.userId).put("userName", it.userName)
                        .put("token", it.token).put("serverId", it.serverId).put("serverName", it.serverName),
                )
            }
            c.embyApp?.let { put("embyApp", it) }
            c.youtube?.let { put("youtube", JSONObject().put("refresh", it.refreshToken).put("access", it.accessToken).put("expires", it.expiresAt)) }
            put(
                "games",
                JSONArray().apply {
                    c.games.forEach {
                        put(JSONObject().put("pkg", it.pkg).put("id", it.shortcutId).put("name", it.name).put("long", it.longName).put("art", it.art))
                    }
                },
            )
            put("trailers", c.trailers)
        }
    }
}
