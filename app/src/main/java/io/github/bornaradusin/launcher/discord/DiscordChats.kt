package io.github.bornaradusin.launcher.discord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import io.github.bornaradusin.launcher.DiscordAccount
import io.github.bornaradusin.launcher.DiscordChat
import io.github.bornaradusin.launcher.DiscordUser
import io.github.bornaradusin.launcher.Store
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.server.Notice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A pinned chat as the TV has it: its messages (oldest first), or why there are none. */
data class ChatState(val messages: List<DiscordMessage> = emptyList(), val loaded: Boolean = false, val error: String? = null)

/** The live line to Discord: new messages arrive the moment they're sent. */
enum class Link { OFF, CONNECTING, LIVE, FAILED }

/**
 * The Discord tab's chats: their messages, kept up to date over Discord's gateway (a live connection,
 * as Discord's own apps have), what's unread, sending (as you, through a webhook, or as the bot), and new
 * messages popping up over whatever is on.
 */
class DiscordChats(
    context: Context,
    private val store: Store,
    private val discord: Discord,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("discord", Context.MODE_PRIVATE)
    private val http = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val _chats = MutableStateFlow<Map<String, ChatState>>(emptyMap())
    val chats: StateFlow<Map<String, ChatState>> = _chats

    /** The last message read in each chat. */
    private val read = MutableStateFlow(readMarks())

    /** How many new messages from others each chat has. */
    val unread: StateFlow<Map<String, Int>> = combine(_chats, read, store.config) { chats, marks, config ->
        val a = config.discord ?: return@combine emptyMap<String, Int>()
        chats.mapValues { (id, state) ->
            val mark = marks[id]?.toLongOrNull() ?: Long.MAX_VALUE
            state.messages.count { (it.id.toLongOrNull() ?: 0L) > mark && !fromYou(it, a) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _link = MutableStateFlow(Link.OFF)
    val link: StateFlow<Link> = _link
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the live line is down, when it's something to fix (the token, or Message Content Intent). */
    val problem: StateFlow<String?> = _problem

    private val _directory = MutableStateFlow<List<DiscordChannel>>(emptyList())
    /** Every text channel the bot can see, to pin from the phone. */
    val directory: StateFlow<List<DiscordChannel>> = _directory

    private val _people = MutableStateFlow<List<DiscordUser>>(emptyList())
    /** People the TV's messages can show as: the servers' owners and whoever writes in the pinned chats. */
    val people: StateFlow<List<DiscordUser>> = _people

    /** Webhooks the TV can't make (the bot may not), so it sends as the bot there. */
    private val _noWebhook = MutableStateFlow<Set<String>>(emptySet())
    val noWebhook: StateFlow<Set<String>> = _noWebhook

    /** The chat on screen right now: read as messages come, and no pop-ups for it. */
    @Volatile var viewing: String? = null
        set(value) {
            field = value
            value?.let { markRead(it) }
        }

    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    @Volatile private var seq: Long? = null
    @Volatile private var acked = true
    @Volatile private var generation = 0
    private var backoff = 2_000L
    private val owners = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start() {
        scope.launch {
            store.config.map { it.discord?.token }.distinctUntilChanged().collect { token ->
                close()
                _chats.value = emptyMap()
                _directory.value = emptyList()
                _people.value = emptyList()
                _noWebhook.value = emptySet()
                _problem.value = null
                if (token == null) {
                    _link.value = Link.OFF
                    return@collect
                }
                open(token)
                refreshDirectory()
            }
        }
        scope.launch {
            store.config.map { c -> c.discord?.pinned?.map { it.channelId } }.distinctUntilChanged().collect { ids ->
                if (ids == null) return@collect
                _chats.update { it.filterKeys { k -> k in ids } }
                ids.filter { it !in _chats.value }.forEach { load(it) }
            }
        }
    }

    /** Fetches every pinned chat again (after the live line was down, say). */
    fun refresh() {
        store.config.value.discord?.pinned?.forEach { load(it.channelId) }
    }

    private fun load(channelId: String) {
        val a = store.config.value.discord ?: return
        scope.launch {
            try {
                val list = discord.messages(a.token, channelId)
                _chats.update { it + (channelId to ChatState(list, loaded = true)) }
                // A chat seen for the first time starts read
                if (read.value[channelId] == null) list.lastOrNull()?.let { mark(channelId, it.id) }
                if (viewing == channelId) markRead(channelId)
                list.forEach { noticePerson(it, a) }
            } catch (e: DiscordException) {
                _chats.update { it + (channelId to (it[channelId] ?: ChatState()).copy(loaded = true, error = e.message)) }
            }
        }
    }

    /** Sends [text] to a chat: as you (your name and picture) when that's set, else as the bot. */
    suspend fun send(channelId: String, text: String) {
        val a = store.config.value.discord ?: throw DiscordException("Connect Discord from your phone first")
        val body = text.trim()
        if (body.isEmpty()) return
        val me = a.me
        val sent = if (me != null && channelId !in _noWebhook.value) {
            val hook = webhookFor(a, channelId)
            if (hook == null) discord.sendAsBot(a.token, channelId, body)
            else try {
                discord.sendAs(hook, me, body)
            } catch (e: DiscordException) {
                if (e.code != 404 && e.code != 401) throw e
                // Someone deleted the webhook in Discord: make another
                prefs.edit().remove("webhook:$channelId").apply()
                val again = webhookFor(a, channelId)
                if (again == null) discord.sendAsBot(a.token, channelId, body) else discord.sendAs(again, me, body)
            }
        } else {
            discord.sendAsBot(a.token, channelId, body)
        }
        add(sent.copy(channelId = channelId))
        markRead(channelId)
    }

    private suspend fun webhookFor(a: DiscordAccount, channelId: String): String? {
        prefs.getString("webhook:$channelId", null)?.let { return it }
        val made = discord.webhook(a, channelId)
        if (made == null) {
            _noWebhook.update { it + channelId }
            return null
        }
        prefs.edit().putString("webhook:$channelId", made).apply()
        return made
    }

    /** Sent from the TV: by the bot, or through one of the TV's webhooks. */
    fun fromTv(m: DiscordMessage, a: DiscordAccount): Boolean =
        m.authorId == a.botId || (m.webhookId != null && prefs.getString("webhook:${m.channelId}", null)?.substringBefore('/') == m.webhookId)

    /** Yours: from the TV, or from you on Discord itself. */
    fun fromYou(m: DiscordMessage, a: DiscordAccount): Boolean = fromTv(m, a) || (a.me != null && m.authorId == a.me.id)

    fun markRead(channelId: String) {
        _chats.value[channelId]?.messages?.lastOrNull()?.let { mark(channelId, it.id) }
    }

    private fun mark(channelId: String, id: String) {
        read.update { it + (channelId to id) }
        prefs.edit().putString("read:$channelId", id).apply()
    }

    private fun readMarks(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith("read:") }.mapKeys { it.key.removePrefix("read:") }.mapValues { it.value.toString() }

    private fun add(m: DiscordMessage) {
        _chats.update { all ->
            val state = all[m.channelId] ?: return@update all
            if (state.messages.any { it.id == m.id }) return@update all
            all + (m.channelId to state.copy(messages = (state.messages + m).takeLast(MAX)))
        }
        if (viewing == m.channelId) markRead(m.channelId)
    }

    private fun noticePerson(m: DiscordMessage, a: DiscordAccount) {
        if (m.bot || m.webhookId != null || m.authorId == a.botId) return
        if (_people.value.any { it.id == m.authorId }) return
        _people.update { it + DiscordUser(m.authorId, m.authorName, m.avatar) }
    }

    // ---- What the bot can see, for pinning ----

    fun refreshDirectory() {
        val a = store.config.value.discord ?: return
        scope.launch {
            val guilds = runCatching { discord.guilds(a.token) }.getOrNull() ?: return@launch
            guilds.forEach { (id, _, owner) -> if (owner != null) owners[id] = owner }
            _directory.value = discord.channels(a.token, guilds)
            lookUpOwners(a)
        }
    }

    private suspend fun lookUpOwners(a: DiscordAccount) {
        val ids = owners.values.distinct().filter { id -> id != a.botId && _people.value.none { it.id == id } }
        for (id in ids) {
            val u = runCatching { discord.user(a.token, id) }.getOrNull() ?: continue
            _people.update { list -> if (list.any { it.id == u.id }) list else listOf(u) + list }
        }
        // Out of the box the TV's messages show as whoever owns the first pinned chat's server (you, usually)
        val now = store.config.value.discord ?: return
        if (now.me == null && !prefs.getBoolean("meChosen", false)) {
            val owner = (now.pinned.firstOrNull()?.guildId ?: _directory.value.firstOrNull()?.guildId)?.let { owners[it] }
            _people.value.firstOrNull { it.id == owner }?.let { u -> store.change { c -> c.copy(discord = c.discord?.copy(me = u)) } }
        }
    }

    /** You picked who the TV's messages show as (null: the bot): the TV doesn't guess again. */
    fun chooseMe(user: DiscordUser?) {
        prefs.edit().putBoolean("meChosen", true).apply()
        store.change { c -> c.copy(discord = c.discord?.copy(me = user)) }
    }

    /** Forgets the account's webhooks and marks (on disconnecting). */
    fun forget() {
        prefs.edit().clear().apply()
        read.value = emptyMap()
    }

    // ---- The gateway: Discord's live connection ----

    private fun open(token: String) {
        val gen = ++generation
        _link.value = Link.CONNECTING
        seq = null
        val req = Request.Builder().url(GATEWAY).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                runCatching { event(webSocket, token, JSONObject(text), gen) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) lost(token, gen, code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) lost(token, gen, 0)
            }
        })
    }

    private fun close() {
        generation++
        heartbeat?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    private fun lost(token: String, gen: Int, code: Int) {
        heartbeat?.cancel()
        when (code) {
            4004 -> fail("Discord doesn't accept the bot's token any more: connect Discord again from your phone")
            4013, 4014 -> fail("Turn on Message Content Intent for the bot: discord.com/developers › your app › Bot")
            else -> {
                _link.value = Link.CONNECTING
                val wait = backoff
                backoff = (backoff * 2).coerceAtMost(60_000)
                scope.launch {
                    delay(wait)
                    if (gen == generation && store.config.value.discord?.token == token) {
                        open(token)
                        refresh()
                    }
                }
            }
        }
    }

    private fun fail(why: String) {
        _link.value = Link.FAILED
        _problem.value = why
    }

    private fun event(ws: WebSocket, token: String, o: JSONObject, gen: Int) {
        if (!o.isNull("s")) seq = o.optLong("s")
        when (o.optInt("op")) {
            HELLO -> {
                val interval = o.getJSONObject("d").getLong("heartbeat_interval")
                beatEvery(ws, interval, gen)
                val identify = JSONObject()
                    .put("token", token)
                    .put("intents", INTENTS)
                    .put("properties", JSONObject().put("os", "android").put("browser", "ShieldLauncher").put("device", "ShieldLauncher"))
                ws.send(JSONObject().put("op", IDENTIFY).put("d", identify).toString())
            }
            ACK -> acked = true
            BEAT -> beat(ws)
            // Discord asks for a fresh connection
            RECONNECT, INVALID_SESSION -> ws.close(4000, "again")
            DISPATCH -> dispatch(o.optString("t"), o.optJSONObject("d") ?: return)
        }
    }

    private fun beatEvery(ws: WebSocket, interval: Long, gen: Int) {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            delay((interval * Math.random()).toLong())
            acked = true
            while (isActive && gen == generation) {
                // No answer to the last beat: the connection is dead even if it doesn't know it
                if (!acked) {
                    ws.cancel()
                    break
                }
                acked = false
                beat(ws)
                delay(interval)
            }
        }
    }

    private fun beat(ws: WebSocket) {
        ws.send(JSONObject().put("op", BEAT).put("d", seq ?: JSONObject.NULL).toString())
    }

    private fun dispatch(type: String, d: JSONObject) {
        val a = store.config.value.discord ?: return
        when (type) {
            "READY" -> {
                _link.value = Link.LIVE
                _problem.value = null
                backoff = 2_000L
            }
            "GUILD_CREATE" -> {
                val id = d.getString("id")
                val name = d.optString("name")
                d.optString("owner_id").takeIf { it.isNotBlank() }?.let { owners[id] = it }
                val list = d.optJSONArray("channels") ?: JSONArray()
                val channels = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optInt("type") == 0 || it.optInt("type") == 5 }
                    .map { DiscordChannel(it.getString("id"), it.optString("name"), id, name, it.optInt("position")) }
                    .sortedBy { it.position }
                _directory.update { all -> all.filter { it.guildId != id } + channels }
                renamePinned(channels)
                scope.launch { lookUpOwners(a) }
            }
            "GUILD_DELETE" -> if (!d.optBoolean("unavailable")) {
                val id = d.getString("id")
                _directory.update { all -> all.filter { it.guildId != id } }
            }
            "CHANNEL_CREATE", "CHANNEL_UPDATE" -> {
                val type = d.optInt("type")
                val guild = d.optString("guild_id").takeIf { it.isNotBlank() } ?: return
                if (type != 0 && type != 5) return
                val guildName = _directory.value.firstOrNull { it.guildId == guild }?.guildName.orEmpty()
                val c = DiscordChannel(d.getString("id"), d.optString("name"), guild, guildName, d.optInt("position"))
                _directory.update { all -> (all.filter { it.id != c.id } + c).sortedWith(compareBy({ it.guildName }, { it.position })) }
                renamePinned(listOf(c))
            }
            "CHANNEL_DELETE" -> {
                val id = d.optString("id")
                _directory.update { all -> all.filter { it.id != id } }
            }
            "MESSAGE_CREATE" -> {
                val m = Discord.message(d)
                val chat = a.pinned.firstOrNull { it.channelId == m.channelId } ?: return
                add(m)
                noticePerson(m, a)
                popUp(m, chat, a)
            }
            "MESSAGE_UPDATE" -> {
                val id = d.optString("id")
                val channel = d.optString("channel_id")
                _chats.update { all ->
                    val state = all[channel] ?: return@update all
                    all + (channel to state.copy(messages = state.messages.map { m ->
                        if (m.id != id) m
                        else if (d.has("author") && d.has("content")) Discord.message(d)
                        else if (d.has("embeds")) m.copy(embeds = Discord.embeds(d.optJSONArray("embeds")))
                        else m
                    }))
                }
            }
            "MESSAGE_DELETE" -> {
                val id = d.optString("id")
                val channel = d.optString("channel_id")
                _chats.update { all ->
                    val state = all[channel] ?: return@update all
                    all + (channel to state.copy(messages = state.messages.filter { it.id != id }))
                }
            }
        }
    }

    /** A pinned channel renamed in Discord keeps its pin, with the new name. */
    private fun renamePinned(channels: List<DiscordChannel>) {
        val a = store.config.value.discord ?: return
        val changed = a.pinned.map { p ->
            channels.firstOrNull { it.id == p.channelId }?.let { c -> if (c.name != p.name || c.guildName != p.guildName) p.copy(name = c.name, guildName = c.guildName) else p } ?: p
        }
        if (changed != a.pinned) store.change { c -> c.copy(discord = c.discord?.copy(pinned = changed)) }
    }

    // ---- Pop-ups ----

    private fun popUp(m: DiscordMessage, chat: DiscordChat, a: DiscordAccount) {
        if (!a.popups || fromYou(m, a) || viewing == m.channelId) return
        val overlay = ListenService.running?.overlay ?: return
        scope.launch {
            val icon = m.avatar?.let { picture(it, 128) }?.let { round(it) }
            val image = (m.images.firstOrNull() ?: m.embeds.firstOrNull()?.image)?.let { picture(it, 720) }
            overlay.show(
                Notice(
                    title = "${m.authorName} · #${chat.name}",
                    message = m.preview,
                    seconds = 8, position = 2, fontSize = 0, color = PANEL, transparency = 0f,
                    interrupt = false, icon = icon, image = image,
                ),
            )
        }
    }

    private suspend fun picture(url: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun round(b: Bitmap): Bitmap {
        val s = minOf(b.width, b.height)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, paint)
        return out
    }

    companion object {
        private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
        /** Servers, their messages, and what the messages say (Message Content Intent). */
        private const val INTENTS = 33281
        private const val DISPATCH = 0
        private const val BEAT = 1
        private const val IDENTIFY = 2
        private const val RECONNECT = 7
        private const val INVALID_SESSION = 9
        private const val HELLO = 10
        private const val ACK = 11
        private const val MAX = 200
        private val PANEL = android.graphics.Color.rgb(17, 22, 33)
    }
}
