package io.github.bornaradusin.launcher.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import fi.iki.elonen.NanoHTTPD
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.RowKind
import io.github.bornaradusin.launcher.RowSetting
import io.github.bornaradusin.launcher.CHANNEL
import io.github.bornaradusin.launcher.seerr.SeerrException
import io.github.bornaradusin.launcher.DiscordChat
import io.github.bornaradusin.launcher.discord.DiscordException
import io.github.bornaradusin.launcher.emby.EmbyException
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The launcher on the home network, on port 7676 (and 7677 for the phone):
 *  - the page a phone opens to set it up (rows, apps, Emby, Seerr, games, updates), and
 *  - notifications, sent the way the "Notifications for Android TV" app takes them, so Home
 *    Assistant's own integration for it works with the launcher as it is.
 */
class LocalServer(
    private val app: LauncherApp,
    private val overlay: Overlay,
    port: Int,
    /** Serves the phone page and its API; off: notifications only. */
    private val phonePage: Boolean,
) : NanoHTTPD(port) {

    /**
     * Never gzipped: NanoHTTPD then sends no length and keeps the connection open, so the phone's
     * browser (and Home Assistant) wait for the rest of an answer that has already all arrived.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(JSONObject().put("ok", false).put("error", e.message ?: "Something went wrong"), Response.Status.INTERNAL_ERROR)
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        if (path == "/" && s.method == Method.POST) return notification(s)
        // Notifications only: Home Assistant's check that the TV is there still gets an answer
        if (!phonePage) {
            return if (path == "/") newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Shield Launcher")
            else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        if (path == "/" || path == "/index.html") return page()
        if (!path.startsWith("/api/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        val agent = s.headers["user-agent"]
        if (path == "/api/pair" && s.method == Method.POST) {
            val key = app.phoneLink.pair(body(s).optString("code"), agent)
                ?: return json(JSONObject().put("ok", false).put("error", "That code isn't the one on the TV"), Response.Status.FORBIDDEN)
            return json(JSONObject().put("ok", true).put("key", key))
        }
        if (!app.phoneLink.valid(s.headers["x-key"] ?: s.parameters["auth"]?.firstOrNull())) {
            return json(JSONObject().put("ok", false).put("error", "pair"), Response.Status.UNAUTHORIZED)
        }
        if (s.method == Method.POST) app.phoneLink.seen(agent)

        return when {
            path == "/api/state" -> json(state())
            path == "/api/icon" -> icon(s.parameters["app"]?.firstOrNull())
            path.startsWith("/api/iptv/") && s.method == Method.GET -> iptvGet(path, s.parameters)
            s.method != Method.POST -> newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "POST")
            else -> action(path, body(s))
        }
    }

    private fun action(path: String, b: JSONObject): Response {
        val store = app.store
        when (path) {
            "/api/rows" -> {
                val arr = b.getJSONArray("rows")
                val rows = (0 until arr.length()).mapNotNull { i ->
                    val r = arr.getJSONObject(i)
                    val id = r.getString("id")
                    if (RowKind.of(id) != null || id.startsWith(CHANNEL)) RowSetting(id, r.optBoolean("on", true)) else null
                }
                store.setRows(rows)
            }
            "/api/apps" -> {
                val arr = b.getJSONArray("row")
                store.setApps((0 until arr.length()).map { arr.getString(it) })
            }
            "/api/trailers" -> store.change { it.copy(trailers = b.optBoolean("on", true)) }
            "/api/pixelshift" -> store.change { it.copy(pixelShift = b.optBoolean("on", true)) }
            "/api/seasonal" -> store.change { it.copy(seasonal = b.optBoolean("on", true)) }
            "/api/warmlights" -> store.change { it.copy(warmLights = b.optBoolean("on", false)) }
            "/api/glass" -> store.change { it.copy(glassEffects = b.optBoolean("on", true)) }
            "/api/skyevents" -> store.change { it.copy(skyEvents = b.optBoolean("on", true)) }
            "/api/clockstyle" -> store.change { it.copy(clockStyle = b.optString("style", "classic").takeIf { s -> s in listOf("classic", "flip", "minimal") } ?: "classic") }
            "/api/trailersound" -> store.change { it.copy(trailerSound = b.optBoolean("on", false)) }
            "/api/emby" -> {
                val account = try {
                    runBlocking { app.emby.signIn(b.optString("server"), b.optString("user"), b.optString("password")) }
                } catch (e: EmbyException) {
                    return json(JSONObject().put("ok", false).put("error", e.message))
                }
                store.change { it.copy(emby = account) }
            }
            "/api/emby/signout" -> {
                store.config.value.emby?.let { a -> runBlocking { app.emby.signOut(a) } }
                store.change { it.copy(emby = null) }
            }
            "/api/emby/app" -> store.change { it.copy(embyApp = b.optString("pkg").takeIf { p -> p.isNotBlank() }) }
            "/api/seerr" -> {
                val account = try {
                    runBlocking { app.seerr.connect(b.optString("server"), b.optString("apiKey")) }
                } catch (e: SeerrException) {
                    return json(JSONObject().put("ok", false).put("error", e.message))
                }
                store.change { it.copy(seerr = account) }
            }
            "/api/seerr/signout" -> store.change { it.copy(seerr = null) }
            "/api/channels/refresh" -> app.scope.launch { app.channels.refresh() }
            "/api/games/remove" -> {
                val id = b.optString("id")
                store.config.value.games.firstOrNull { "${it.pkg}|${it.shortcutId}" == id }?.let { app.games.remove(it) }
            }
            "/api/update/check" -> app.scope.launch { app.updater.check(manual = true) }
            "/api/update/install" -> app.scope.launch {
                app.updater.open()
                app.updater.update()
            }
            "/api/refresh" -> app.scope.launch { app.feed.refresh() }
            "/api/weather" -> {
                val town = b.optString("town").trim()
                val region = b.optString("region").trim()
                if (town.isBlank()) {
                    store.change { it.copy(weather = null) }
                } else {
                    val same = store.config.value.weather?.takeIf { it.name.equals(town, true) }
                    val place = same ?: runBlocking { runCatching { app.weather.find(town) }.getOrNull() }
                        ?: return json(JSONObject().put("ok", false).put("error", "Couldn't find “$town” on the map"))
                    store.change { it.copy(weather = place.copy(region = region)) }
                }
                app.weather.refreshSoon()
            }
            "/api/warnings" -> store.change { it.copy(warnings = b.optBoolean("on", true)) }
            "/api/discord" -> {
                val account = try {
                    runBlocking { app.discord.connect(b.optString("token")) }
                } catch (e: DiscordException) {
                    return json(JSONObject().put("ok", false).put("error", e.message))
                }
                val old = store.config.value.discord
                if (old?.botId == account.botId) {
                    // The same bot (a new token after Reset Token): its pins stay
                    store.change { it.copy(discord = old.copy(token = account.token, botName = account.botName)) }
                } else {
                    app.discordChats.forget()
                    store.change { it.copy(discord = account) }
                }
            }
            "/api/discord/signout" -> {
                app.discordChats.forget()
                store.change { it.copy(discord = null) }
            }
            "/api/discord/refresh" -> app.discordChats.refreshDirectory()
            "/api/discord/pin" -> {
                val id = b.optString("id")
                val on = b.optBoolean("on", true)
                val channel = app.discordChats.directory.value.firstOrNull { it.id == id }
                store.change { c ->
                    val d = c.discord ?: return@change c
                    val pinned = if (on) {
                        if (channel == null || d.pinned.any { it.channelId == id }) d.pinned
                        else d.pinned + DiscordChat(channel.id, channel.name, channel.guildId, channel.guildName)
                    } else d.pinned.filter { it.channelId != id }
                    c.copy(discord = d.copy(pinned = pinned))
                }
            }
            "/api/discord/me" -> {
                val id = b.optString("id")
                app.discordChats.chooseMe(app.discordChats.people.value.firstOrNull { it.id == id })
            }
            "/api/discord/popups" -> store.change { c -> c.copy(discord = c.discord?.copy(popups = b.optBoolean("on", true))) }
            "/api/iptv" -> {
                val message = try {
                    runBlocking { io.github.bornaradusin.launcher.ui.connectIptv(app, b.optString("server"), b.optString("username"), b.optString("password")) }
                } catch (e: io.github.bornaradusin.launcher.iptv.XtreamException) {
                    return json(JSONObject().put("ok", false).put("error", e.message))
                }
                return json(JSONObject().put("ok", true).put("message", message).put("state", state()))
            }
            "/api/iptv/signout" -> {
                store.config.value.iptv?.let { app.iptv.forget(it) }
                store.change { it.copy(iptv = null) }
            }
            "/api/iptv/options" -> store.change { c ->
                val t = c.iptv ?: return@change c
                c.copy(
                    iptv = t.copy(
                        films = if (b.has("films")) b.optBoolean("films") else t.films,
                        series = if (b.has("series")) b.optBoolean("series") else t.series,
                        hideAdult = if (b.has("hideAdult")) b.optBoolean("hideAdult") else t.hideAdult,
                        providerGroups = if (b.has("providerGroups")) b.optBoolean("providerGroups") else t.providerGroups,
                        preview = if (b.has("preview")) b.optBoolean("preview") else t.preview,
                        vodHours = if (b.has("vodHours")) b.optInt("vodHours", t.vodHours).coerceIn(0, 24 * 30) else t.vodHours,
                        buffer = if (b.has("buffer")) b.optInt("buffer", t.buffer).coerceIn(0, 4) else t.buffer,
                    ),
                )
            }
            "/api/iptv/refresh" -> app.iptv.refreshAll()
            "/api/iptv/groups" -> {
                val arr = b.optJSONArray("groups") ?: JSONArray()
                val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                val on = (0 until arr.length()).associate { arr.getJSONObject(it).let { g -> g.getString("id") to g.optBoolean("on", true) } }
                app.iptv.orderGroups(ids, on)
            }
            "/api/iptv/group/save" -> {
                val id = b.optString("id")
                val name = b.optString("name").trim()
                val channels = b.optJSONArray("channels")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                if (id.isBlank()) app.iptv.newGroup(name.ifBlank { "My channels" }, channels.orEmpty())
                else app.iptv.saveGroup(id, name, channels)
            }
            "/api/iptv/group/delete" -> app.iptv.deleteGroup(b.optString("id"))
            "/api/iptv/import" -> {
                if (!app.iptv.importCategory(b.optString("category"))) {
                    return json(JSONObject().put("ok", false).put("error", "That group is on the TV tab already").put("state", state()))
                }
            }
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return json(JSONObject().put("ok", true).put("state", state()))
    }

    private fun state(): JSONObject {
        val c = app.store.config.value
        val all = app.apps.all.value
        val row = c.apps ?: emptyList()
        val o = JSONObject()
        o.put("version", app.version)
        o.put("device", android.os.Build.MODEL)
        val channels = app.channels.channels.value
        o.put("rows", JSONArray().apply {
            c.rows.forEach { r ->
                val kind = r.kind
                val channel = channels.firstOrNull { it.rowId == r.id }
                when {
                    kind != null -> put(JSONObject().put("id", r.id).put("title", kind.title).put("source", kind.source).put("on", r.on))
                    // A channel shows once its app has it (an app that's gone keeps its place, hidden)
                    channel != null -> put(JSONObject().put("id", r.id).put("title", channel.name).put("source", channel.appName).put("on", r.on))
                }
            }
        })
        o.put("channelsAllowed", app.channels.allowed())
        o.put("row", JSONArray(row.filter { k -> all.any { it.key == k } }))
        o.put("apps", JSONArray().apply { all.forEach { a -> put(JSONObject().put("key", a.key).put("label", a.label)) } })
        o.put("trailers", c.trailers)
        o.put("trailerSound", c.trailerSound)
        o.put("pixelShift", c.pixelShift)
        o.put("seasonal", c.seasonal)
        o.put("warmLights", c.warmLights)
        o.put("glassEffects", c.glassEffects)
        o.put("skyEvents", c.skyEvents)
        o.put("clockStyle", c.clockStyle)
        val emby = JSONObject().put("signedIn", c.emby != null)
        c.emby?.let { emby.put("server", it.server).put("user", it.userName).put("serverName", it.serverName) }
        emby.put("app", app.embyLauncher.app(c.embyApp) ?: "")
        emby.put("apps", JSONArray().apply { app.embyLauncher.installedApps().forEach { (pkg, name) -> put(JSONObject().put("pkg", pkg).put("name", name)) } })
        o.put("emby", emby)
        val seerr = JSONObject().put("signedIn", c.seerr != null)
        c.seerr?.let { seerr.put("server", it.server).put("user", it.userName) }
        o.put("seerr", seerr)
        val discord = JSONObject().put("signedIn", c.discord != null)
        c.discord?.let { d ->
            discord.put("bot", d.botName).put("invite", app.discord.invite(d.botId)).put("popups", d.popups)
                .put("me", d.me?.id ?: "")
                .put("live", app.discordChats.link.value == io.github.bornaradusin.launcher.discord.Link.LIVE)
                .put("problem", app.discordChats.problem.value ?: "")
            val pinned = d.pinned.map { it.channelId }
            val channels = app.discordChats.directory.value
            discord.put("channels", JSONArray().apply {
                channels.forEach { ch -> put(JSONObject().put("id", ch.id).put("name", ch.name).put("guild", ch.guildName).put("pinned", ch.id in pinned)) }
                // Pinned ones the bot can't see any more still show, to unpin
                d.pinned.filter { p -> channels.none { it.id == p.channelId } }.forEach { p ->
                    put(JSONObject().put("id", p.channelId).put("name", p.name).put("guild", p.guildName).put("pinned", true).put("gone", true))
                }
            })
            discord.put("people", JSONArray().apply {
                (listOfNotNull(d.me) + app.discordChats.people.value).distinctBy { it.id }.forEach { u -> put(JSONObject().put("id", u.id).put("name", u.name)) }
            })
        }
        o.put("discord", discord)
        o.put("games", JSONArray().apply { c.games.forEach { g -> put(JSONObject().put("id", "${g.pkg}|${g.shortcutId}").put("name", g.name)) } })
        val update = JSONObject()
        when (val u = app.updater.state.value) {
            is UpdateState.Available -> update.put("available", u.update.version)
            is UpdateState.Downloading -> update.put("available", u.update.version).put("progress", u.progress)
            is UpdateState.NeedsPermission -> update.put("available", u.update.version).put("message", "Allow the launcher to install apps on the TV")
            is UpdateState.Failed -> { u.update?.let { update.put("available", it.version) }; update.put("message", u.message) }
            UpdateState.Checking -> update.put("checking", true)
            UpdateState.UpToDate -> update.put("upToDate", true)
            UpdateState.Idle -> Unit
        }
        o.put("update", update)
        val w = JSONObject()
        val now = app.weather.now.value
        w.put("auto", c.weather == null)
        w.put("town", c.weather?.name ?: now?.place ?: "")
        w.put("region", c.weather?.region ?: "")
        w.put("areas", JSONArray(c.weather?.areas ?: emptyList<String>()))
        w.put("regions", JSONArray(app.weather.regions.value))
        w.put("warnings", c.warnings)
        now?.let { n ->
            w.put("now", "${n.temperature}°")
            w.put("active", JSONArray().apply { n.warnings.forEach { put("${it.event} · ${it.area}") } })
        }
        o.put("weather", w)
        o.put("iptv", iptvState())
        return o
    }

    private fun iptvState(): JSONObject {
        val t = app.store.config.value.iptv ?: return JSONObject().put("signedIn", false)
        val st = app.iptv.state.value
        val live = app.iptv.live.value
        val o = JSONObject().put("signedIn", true).put("server", t.server).put("user", t.username)
        st.account?.let { a ->
            a.expires?.let { o.put("until", java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))) }
            if (a.max > 0) o.put("screens", "${a.active} of ${a.max} screens in use")
        }
        o.put("problem", st.problem ?: "").put("vodProblem", st.vodProblem ?: "")
        o.put("channels", st.channels).put("films", st.films).put("series", st.series)
        o.put("loading", st.liveLoading || st.filmsLoading || st.seriesLoading)
        if (st.liveLoading && st.liveTotal > 0) o.put("progress", "${st.liveDone} of ${st.liveTotal} groups")
        o.put("categories", live.categories.size)
        o.put("filmsOn", t.films).put("seriesOn", t.series).put("hideAdult", t.hideAdult).put("providerGroups", t.providerGroups)
        o.put("preview", t.preview).put("vodHours", t.vodHours).put("buffer", t.buffer)
        o.put("groups", JSONArray().apply {
            t.groups.forEach { g ->
                put(
                    JSONObject().put("id", g.id).put("name", g.name).put("on", g.on).put("own", g.category == null)
                        .put("count", app.iptv.groupChannels(g, live).size),
                )
            }
        })
        return o
    }

    /** The phone's group editor: the provider's groups, channels found by name, and a group's channels. */
    private fun iptvGet(path: String, p: Map<String, List<String>>): Response {
        val t = app.store.config.value.iptv ?: return json(JSONObject().put("ok", false).put("error", "IPTV isn't set up"))
        val live = app.iptv.live.value
        val adult = live.categories.filter { it.adult }.map { it.id }.toSet()
        fun hidden(c: io.github.bornaradusin.launcher.iptv.XChannel) = t.hideAdult && (c.adult || c.category in adult)
        fun channel(c: io.github.bornaradusin.launcher.iptv.XChannel) = JSONObject().put("id", c.id).put("name", c.name).put("num", c.num)
            .put("cat", c.category).put("catName", live.categoryNames[c.category] ?: "").put("logo", c.logo ?: "")
        return when (path) {
            "/api/iptv/categories" -> json(JSONObject().put("ok", true).put("categories", JSONArray().apply {
                live.categories.filter { !t.hideAdult || !it.adult }.forEach { c ->
                    put(
                        JSONObject().put("id", c.id).put("name", c.name).put("count", live.byCategory[c.id]?.size ?: 0)
                            .put("imported", t.groups.any { it.category == c.id }),
                    )
                }
            }))
            "/api/iptv/channels" -> {
                val q = p["q"]?.firstOrNull().orEmpty().trim()
                val cat = p["cat"]?.firstOrNull().orEmpty()
                val found = live.channels.asSequence()
                    .filter { !hidden(it) && (cat.isBlank() || it.category == cat) && (q.isBlank() || it.name.contains(q, true)) }
                    .distinctBy { it.id }.take(300).toList()
                json(JSONObject().put("ok", true).put("channels", JSONArray().apply { found.forEach { put(channel(it)) } }))
            }
            "/api/iptv/group" -> {
                val id = p["id"]?.firstOrNull().orEmpty()
                val g = t.groups.firstOrNull { it.id == id } ?: return json(JSONObject().put("ok", false).put("error", "That group is gone"))
                json(
                    JSONObject().put("ok", true).put("group", JSONObject().put("id", g.id).put("name", g.name).put("own", g.category == null)
                        .put("channels", JSONArray().apply { app.iptv.groupChannels(g, live).forEach { put(channel(it)) } })),
                )
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    /** An app's banner (or icon), for the phone's list. */
    private fun icon(key: String?): Response {
        val png = key?.let { app.apps.find(it)?.png } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=86400")
        }
    }

    private fun page(): Response {
        val html = app.assets.open("setup/index.html").use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(html), html.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Connection", "close")
        }
    }

    /**
     * A notification, as "Notifications for Android TV" takes them: a form with msg, title,
     * duration, position, fontsize, color (or bkgcolor), transparency and interrupt, with the icon
     * as the file "filename" and a picture as "filename2". JSON and url-encoded bodies work too.
     * The body is read here, not by NanoHTTPD's form parser (see [NoticeBody]).
     */
    private fun notification(s: IHTTPSession): Response {
        val from = s.remoteIpAddress ?: "?"
        val b = try {
            NoticeBody.read(s.headers, s.inputStream)
        } catch (e: Exception) {
            ListenService.received(from, "couldn't read it: ${e.message ?: e.javaClass.simpleName}", ok = false)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Couldn't read the notification")
        }
        fun field(name: String) = b.field(name)
        fun bitmap(bytes: ByteArray?): Bitmap? = bytes?.takeIf { it.isNotEmpty() }?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }

        val color = (field("color").ifBlank { field("bkgcolor") }.ifBlank { field("background_color") }).let { c ->
            if (c.isBlank() || c.equals(DEFAULT_GREY, true) || c.equals("grey", true)) PANEL
            else runCatching { Color.parseColor(c) }.getOrDefault(PANEL)
        }
        val transparency = when (field("transparency")) {
            "2", "25%" -> 0.25f
            "3", "50%" -> 0.5f
            "4", "75%" -> 0.75f
            "5", "100%" -> 1f
            else -> 0f
        }
        // A live camera (a doorbell): stays 30 seconds unless told otherwise
        val stream = field("stream").ifBlank { field("video") }.ifBlank { field("camera") }.takeIf { it.isNotBlank() }
        val seconds = field("duration").toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: if (stream != null) 30 else 5
        val notice = Notice(
            // The Home Assistant library's stand-in title says nothing, so it's left off
            title = field("title").let { if (it == "Notification" || it == "None") "" else it },
            message = field("msg").ifBlank { field("message") },
            seconds = seconds,
            position = when (val pos = field("position").lowercase()) {
                "bottom-right", "bottom_right" -> 0
                "bottom-left", "bottom_left" -> 1
                "top-right", "top_right" -> 2
                "top-left", "top_left" -> 3
                "center", "centre" -> 4
                else -> pos.toIntOrNull() ?: 0
            },
            fontSize = when (val f = field("fontsize").lowercase()) {
                "small" -> 1
                "medium" -> 0
                "large" -> 2
                "max", "maximum" -> 3
                else -> f.toIntOrNull() ?: 0
            },
            color = color,
            transparency = transparency,
            interrupt = (field("interrupt").ifBlank { field("interactive") }).let { it == "1" || it.equals("true", true) },
            icon = bitmap(b.icon),
            image = bitmap(b.image),
            stream = stream,
        )
        if (notice.title.isBlank() && notice.message.isBlank() && notice.image == null && notice.stream == null) {
            ListenService.received(from, "nothing to show (${b.about})", ok = false)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Nothing to show: send msg (and title)")
        }
        overlay.show(notice) { problem ->
            val what = notice.title.ifBlank { notice.message }.take(40)
            if (problem == null) ListenService.received(from, "shown: “$what”", ok = true)
            else ListenService.received(from, "not shown: $problem", ok = false)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK").apply { addHeader("Connection", "close") }
    }

    private fun body(s: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        s.parseBody(files)
        val text = files["postData"] ?: return JSONObject()
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString()).apply { addHeader("Cache-Control", "no-store") }

    companion object {
        /** Home Assistant's integration always sends notifications here. */
        const val PORT = 7676
        /** The phone's set-up page has a port of its own, so it works even if another app holds 7676. */
        const val SETUP_PORT = 7677
        /** The app the launcher's notifications replace; it takes port 7676 if it's still installed. */
        const val OTHER_APP = "de.cyberdream.androidtv.notifications.google"
        private const val DEFAULT_GREY = "#607d8b"
        private val PANEL = Color.rgb(17, 22, 33)
    }
}
