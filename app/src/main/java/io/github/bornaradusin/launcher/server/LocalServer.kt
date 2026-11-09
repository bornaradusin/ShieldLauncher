package io.github.bornaradusin.launcher.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import fi.iki.elonen.NanoHTTPD
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.RowKind
import io.github.bornaradusin.launcher.RowSetting
import io.github.bornaradusin.launcher.emby.EmbyException
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The launcher on the home network, on port 7676:
 *  - the page a phone opens to set it up (rows, apps, Emby, YouTube, games, updates), and
 *  - notifications, sent the way the "Notifications for Android TV" app takes them, so Home
 *    Assistant's own integration for it works with the launcher as it is.
 */
class LocalServer(private val app: LauncherApp, private val overlay: Overlay) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(JSONObject().put("ok", false).put("error", e.message ?: "Something went wrong"), Response.Status.INTERNAL_ERROR)
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        if (path == "/" && s.method == Method.POST) return notification(s)
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
                    RowKind.of(r.getString("id"))?.let { RowSetting(it, r.optBoolean("on", true)) }
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
            "/api/youtube/start" -> app.scope.launch { app.feed.startYouTubeSignIn() }
            "/api/youtube/cancel" -> app.scope.launch { app.feed.cancelYouTubeSignIn() }
            "/api/youtube/signout" -> app.scope.launch { app.feed.signOutYouTube() }
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
        o.put("rows", JSONArray().apply {
            c.rows.forEach { r -> put(JSONObject().put("id", r.kind.id).put("title", r.kind.title).put("source", r.kind.source).put("on", r.on)) }
        })
        o.put("row", JSONArray(row.filter { k -> all.any { it.key == k } }))
        o.put("apps", JSONArray().apply { all.forEach { a -> put(JSONObject().put("key", a.key).put("label", a.label)) } })
        o.put("trailers", c.trailers)
        val emby = JSONObject().put("signedIn", c.emby != null)
        c.emby?.let { emby.put("server", it.server).put("user", it.userName).put("serverName", it.serverName) }
        emby.put("app", app.embyLauncher.app(c.embyApp) ?: "")
        emby.put("apps", JSONArray().apply { app.embyLauncher.installedApps().forEach { (pkg, name) -> put(JSONObject().put("pkg", pkg).put("name", name)) } })
        o.put("emby", emby)
        val yt = app.feed.ytSignIn.value
        val youtube = JSONObject().put("signedIn", c.youtube != null).put("busy", yt.busy)
        yt.code?.let { youtube.put("code", it.userCode).put("url", it.url) }
        yt.message?.let { youtube.put("message", it) }
        youtube.put("player", app.youtube.player()?.let { p -> runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() } ?: "")
        o.put("youtube", youtube)
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
        }
    }

    /**
     * A notification, as "Notifications for Android TV" takes them: a form with msg, title,
     * duration, position, fontsize, color (or bkgcolor), transparency and interrupt, with the icon
     * as the file "filename" and a picture as "filename2".
     */
    private fun notification(s: IHTTPSession): Response {
        val files = HashMap<String, String>()
        s.parseBody(files)
        val p = s.parameters
        fun v(name: String) = p[name]?.firstOrNull()?.trim().orEmpty()
        fun bitmap(field: String): Bitmap? = files[field]?.let { path ->
            runCatching {
                val bytes = File(path).readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        // A JSON body works too (handy for a rest_command)
        val json = files["postData"]?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun field(name: String) = json?.optString(name)?.takeIf { it.isNotBlank() } ?: v(name)

        val color = (field("color").ifBlank { field("bkgcolor") }).let { c ->
            if (c.isBlank() || c.equals(DEFAULT_GREY, true)) PANEL else runCatching { Color.parseColor(c) }.getOrDefault(PANEL)
        }
        val transparency = when (field("transparency")) {
            "2", "25%" -> 0.25f
            "3", "50%" -> 0.5f
            "4", "75%" -> 0.75f
            "5", "100%" -> 1f
            else -> 0f
        }
        val notice = Notice(
            // The Home Assistant library's stand-in title says nothing, so it's left off
            title = field("title").let { if (it == "Notification") "" else it },
            message = field("msg").ifBlank { field("message") },
            seconds = field("duration").toIntOrNull() ?: 5,
            position = when (val pos = field("position")) {
                "bottom-right" -> 0
                "bottom-left" -> 1
                "top-right" -> 2
                "top-left" -> 3
                "center" -> 4
                else -> pos.toIntOrNull() ?: 0
            },
            fontSize = when (val f = field("fontsize")) {
                "small" -> 1
                "medium" -> 0
                "large" -> 2
                "max" -> 3
                else -> f.toIntOrNull() ?: 0
            },
            color = color,
            transparency = transparency,
            interrupt = field("interrupt").let { it == "1" || it.equals("true", true) },
            icon = bitmap("filename"),
            image = bitmap("filename2"),
        )
        if (notice.title.isBlank() && notice.message.isBlank() && notice.image == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Nothing to show: send msg (and title)")
        }
        overlay.show(notice)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
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
        const val PORT = 7676
        private const val DEFAULT_GREY = "#607d8b"
        private val PANEL = Color.rgb(17, 22, 33)
    }
}
