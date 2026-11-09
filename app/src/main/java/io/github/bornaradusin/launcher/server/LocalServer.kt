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
        val emby = JSONObject().put("signedIn", c.emby != null)
        c.emby?.let { emby.put("server", it.server).put("user", it.userName).put("serverName", it.serverName) }
        emby.put("app", app.embyLauncher.app(c.embyApp) ?: "")
        emby.put("apps", JSONArray().apply { app.embyLauncher.installedApps().forEach { (pkg, name) -> put(JSONObject().put("pkg", pkg).put("name", name)) } })
        o.put("emby", emby)
        val seerr = JSONObject().put("signedIn", c.seerr != null)
        c.seerr?.let { seerr.put("server", it.server).put("user", it.userName) }
        o.put("seerr", seerr)
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
        return o
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
        val seconds = field("duration").toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: 5
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
        )
        if (notice.title.isBlank() && notice.message.isBlank() && notice.image == null) {
            ListenService.received(from, "nothing to show (fields: ${b.fields.keys.joinToString().ifBlank { "none" }})", ok = false)
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
