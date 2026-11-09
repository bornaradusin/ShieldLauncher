package io.github.bornaradusin.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The launcher's own rows. Apps' channels (SmartTube's and others) are rows too, by [CHANNEL] id. */
enum class RowKind(val id: String, val title: String, val source: String) {
    APPS("apps", "Apps", "Your apps, in your order"),
    CONTINUE("continue", "Continue watching", "Emby"),
    MOVIES("movies", "Movies", "Emby"),
    SHOWS("shows", "TV shows", "Emby"),
    GAMES("games", "Games", "Artemis");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** A channel row's id: "ch:" + the app's package + "|" + the channel's name. */
const val CHANNEL = "ch:"

fun channelRowId(pkg: String, name: String) = "$CHANNEL$pkg|$name"

/** A row on Home: one of the launcher's own, or an app's channel; on or off. */
data class RowSetting(val id: String, val on: Boolean) {
    val kind: RowKind? get() = RowKind.of(id)
}

/** Signed in to an Emby server. */
data class EmbyAccount(
    val server: String,
    val userId: String,
    val userName: String,
    val token: String,
    val serverId: String,
    val serverName: String,
)

/** Connected to Seerr, with its API key; requests go in as [userName]. */
data class SeerrAccount(val server: String, val apiKey: String, val userName: String, val userId: Int)

/** A game Artemis pinned to the launcher. */
data class Game(val pkg: String, val shortcutId: String, val name: String, val longName: String, val art: String)

data class Config(
    val rows: List<RowSetting>,
    /** The apps row, as "package/activity", in order. Null until it's first filled. */
    val apps: List<String>?,
    val emby: EmbyAccount?,
    /** The Emby app films and episodes play in; null: whichever is installed. */
    val embyApp: String?,
    val seerr: SeerrAccount?,
    val games: List<Game>,
    /** A film's trailer plays, muted, behind the top of Home. */
    val trailers: Boolean,
    /** The phone's set-up page is reachable on the network (off when not in use). */
    val phonePage: Boolean = false,
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
        val given = rows.distinctBy { it.id }
        val missing = c.rows.filter { r -> given.none { it.id == r.id } }
        c.copy(rows = given + missing)
    }

    /** Channels seen for the first time go at the end: on if [on] says so. */
    fun addChannelRows(ids: List<String>, on: (String) -> Boolean) {
        val fresh = ids.filter { id -> config.value.rows.none { it.id == id } }
        if (fresh.isEmpty()) return
        change { c -> c.copy(rows = c.rows + fresh.map { RowSetting(it, on(it)) }) }
    }

    fun setApps(apps: List<String>) = change { it.copy(apps = apps.distinct()) }

    private fun read(): Config {
        val text = prefs.getString("config", null) ?: return DEFAULT
        return runCatching { parse(JSONObject(text)) }.getOrDefault(DEFAULT)
    }

    companion object {
        val DEFAULT_ROWS = RowKind.entries.map { RowSetting(it.id, true) }

        val DEFAULT = Config(DEFAULT_ROWS, null, null, null, null, emptyList(), true)

        private fun parse(o: JSONObject): Config {
            val rows = mutableListOf<RowSetting>()
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    val id = r.getString("id")
                    // Rows of older versions that are gone (the YouTube sign-in's) are dropped
                    if (RowKind.of(id) != null || id.startsWith(CHANNEL)) rows += RowSetting(id, r.optBoolean("on", true))
                }
            }
            // A row added in a newer version shows up at the end
            DEFAULT_ROWS.forEach { d -> if (rows.none { it.id == d.id }) rows += d }
            val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            val emby = o.optJSONObject("emby")?.let {
                EmbyAccount(
                    it.getString("server"), it.getString("userId"), it.optString("userName"), it.getString("token"),
                    it.optString("serverId"), it.optString("serverName"),
                )
            }
            val seerr = o.optJSONObject("seerr")?.let {
                SeerrAccount(it.getString("server"), it.getString("apiKey"), it.optString("userName"), it.optInt("userId"))
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
                seerr = seerr,
                games = games,
                trailers = o.optBoolean("trailers", true),
                phonePage = o.optBoolean("phonePage", false),
            )
        }

        private fun write(c: Config) = JSONObject().apply {
            put("rows", JSONArray().apply { c.rows.forEach { put(JSONObject().put("id", it.id).put("on", it.on)) } })
            c.apps?.let { put("apps", JSONArray(it)) }
            c.emby?.let {
                put(
                    "emby", JSONObject().put("server", it.server).put("userId", it.userId).put("userName", it.userName)
                        .put("token", it.token).put("serverId", it.serverId).put("serverName", it.serverName),
                )
            }
            c.embyApp?.let { put("embyApp", it) }
            c.seerr?.let { put("seerr", JSONObject().put("server", it.server).put("apiKey", it.apiKey).put("userName", it.userName).put("userId", it.userId)) }
            put(
                "games",
                JSONArray().apply {
                    c.games.forEach {
                        put(JSONObject().put("pkg", it.pkg).put("id", it.shortcutId).put("name", it.name).put("long", it.longName).put("art", it.art))
                    }
                },
            )
            put("trailers", c.trailers)
            put("phonePage", c.phonePage)
        }
    }
}
