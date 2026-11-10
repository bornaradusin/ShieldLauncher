package io.github.bornaradusin.launcher.discord

import io.github.bornaradusin.launcher.DiscordAccount
import io.github.bornaradusin.launcher.DiscordUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime

/** What Discord said went wrong, in words for the TV and the phone; [code] is the HTTP status. */
class DiscordException(message: String, val code: Int = 0) : IOException(message)

/** A link's preview under a message (a film's TMDB page, say): its title, words and picture. */
data class DiscordEmbed(val title: String?, val description: String?, val url: String?, val image: String?)

/** A message in a chat, as the TV shows it. */
data class DiscordMessage(
    val id: String,
    val channelId: String,
    val authorId: String,
    val authorName: String,
    val avatar: String?,
    val text: String,
    val at: Long,
    /** Sent through a webhook (the TV's own, when it sends as you). */
    val webhookId: String?,
    val bot: Boolean,
    val images: List<String>,
    val embeds: List<DiscordEmbed>,
) {
    /** One line for a pop-up or the list of chats. */
    val preview: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: embeds.firstOrNull()?.title
            ?: if (images.isNotEmpty()) "sent a picture" else ""
}

/** A text channel the bot can see, and its server. */
data class DiscordChannel(val id: String, val name: String, val guildId: String, val guildName: String, val position: Int)

/**
 * Discord's API, as a bot (the only way Discord lets an app in): the bot's own details, the channels of
 * the servers it's in, a channel's messages, and sending, as the bot or, through a webhook, with your
 * name and picture.
 */
class Discord(private val http: OkHttpClient, private val version: String) {

    /** Checks the token and finds the bot it belongs to. */
    suspend fun connect(token: String): DiscordAccount {
        val t = token.trim().removePrefix("Bot ").trim()
        if (t.isBlank()) throw DiscordException("Paste the bot's token (discord.com/developers › your app › Bot › Reset Token)")
        val me = try {
            call(t, "GET", "/users/@me") as JSONObject
        } catch (e: DiscordException) {
            throw if (e.code == 401) DiscordException("Discord doesn't know that token: copy it again from the Bot page (Reset Token)") else e
        }
        return DiscordAccount(token = t, botId = me.getString("id"), botName = name(me), me = null, pinned = emptyList())
    }

    /** The servers the bot is in, by id: name and owner. */
    suspend fun guilds(token: String): List<Triple<String, String, String?>> {
        val list = call(token, "GET", "/users/@me/guilds") as JSONArray
        return (0 until list.length()).map { i ->
            val g = list.getJSONObject(i)
            val id = g.getString("id")
            val owner = runCatching { (call(token, "GET", "/guilds/$id") as JSONObject).optString("owner_id").takeIf { it.isNotBlank() } }.getOrNull()
            Triple(id, g.optString("name"), owner)
        }
    }

    /** Every text channel in the servers the bot is in. */
    suspend fun channels(token: String, guilds: List<Triple<String, String, String?>>): List<DiscordChannel> =
        guilds.flatMap { (id, guildName, _) ->
            val list = runCatching { call(token, "GET", "/guilds/$id/channels") as JSONArray }.getOrNull() ?: return@flatMap emptyList<DiscordChannel>()
            (0 until list.length()).map { list.getJSONObject(it) }
                .filter { it.optInt("type") == TEXT || it.optInt("type") == ANNOUNCEMENT }
                .map { DiscordChannel(it.getString("id"), it.optString("name"), id, guildName, it.optInt("position")) }
                .sortedBy { it.position }
        }

    /** Someone by their id. */
    suspend fun user(token: String, id: String): DiscordUser {
        val u = call(token, "GET", "/users/$id") as JSONObject
        return DiscordUser(u.getString("id"), name(u), avatar(u))
    }

    /** A channel's last [limit] messages, oldest first. */
    suspend fun messages(token: String, channelId: String, limit: Int = 60): List<DiscordMessage> {
        val list = try {
            call(token, "GET", "/channels/$channelId/messages?limit=$limit") as JSONArray
        } catch (e: DiscordException) {
            throw when (e.code) {
                403 -> DiscordException("The bot can't read this channel: in Discord, let it View Channel and Read Message History here", 403)
                404 -> DiscordException("This channel is gone from Discord, or the bot isn't in its server any more", 404)
                else -> e
            }
        }
        return (0 until list.length()).map { message(list.getJSONObject(it)) }.reversed()
    }

    /** Sends [text] as the bot. */
    suspend fun sendAsBot(token: String, channelId: String, text: String): DiscordMessage {
        val body = JSONObject().put("content", text).put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        return try {
            message(call(token, "POST", "/channels/$channelId/messages", body) as JSONObject)
        } catch (e: DiscordException) {
            throw if (e.code == 403) DiscordException("The bot can't write here: in Discord, let it Send Messages in this channel", 403) else e
        }
    }

    /** The bot's webhook in a channel, made if there isn't one: "id/token". Null when the bot may not make webhooks. */
    suspend fun webhook(account: DiscordAccount, channelId: String): String? {
        val existing = runCatching { call(account.token, "GET", "/channels/$channelId/webhooks") as JSONArray }.getOrElse { e ->
            if (e is DiscordException && e.code == 403) return null
            throw e
        }
        for (i in 0 until existing.length()) {
            val w = existing.getJSONObject(i)
            val by = w.optJSONObject("user")?.optString("id")
            if (by == account.botId && w.optString("token").isNotBlank()) return w.getString("id") + "/" + w.getString("token")
        }
        val made = try {
            call(account.token, "POST", "/channels/$channelId/webhooks", JSONObject().put("name", "Shield launcher")) as JSONObject
        } catch (e: DiscordException) {
            if (e.code == 403) return null
            throw e
        }
        return made.getString("id") + "/" + made.getString("token")
    }

    /** Sends [text] through a webhook ("id/token") with [me]'s name and picture. */
    suspend fun sendAs(webhook: String, me: DiscordUser, text: String): DiscordMessage {
        val body = JSONObject().put("content", text).put("username", me.name)
            .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        me.avatar?.let { body.put("avatar_url", it) }
        return message(call(null, "POST", "/webhooks/$webhook?wait=true", body) as JSONObject)
    }

    /** The link that adds the bot to a server, with what it needs there. */
    fun invite(botId: String) = "https://discord.com/oauth2/authorize?client_id=$botId&scope=bot&permissions=$PERMISSIONS"

    private suspend fun call(token: String?, method: String, path: String, body: JSONObject? = null): Any = withContext(Dispatchers.IO) {
        var tries = 0
        var result: Any? = null
        while (result == null) {
            val req = Request.Builder().url(API + path)
                .header("User-Agent", "DiscordBot (https://github.com/bornaradusin/ShieldLauncher, $version)")
                .apply { if (token != null) header("Authorization", "Bot $token") }
                .method(method, body?.toString()?.toRequestBody(JSON))
                .build()
            val (code, text) = try {
                http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw DiscordException("Couldn't reach Discord")
            }
            if (code == 429 && tries++ < 2) {
                // Too fast: Discord says how long to wait
                val wait = runCatching { JSONObject(text).optDouble("retry_after", 1.0) }.getOrDefault(1.0)
                delay((wait * 1000).toLong().coerceIn(200, 10_000))
                continue
            }
            if (code !in 200..299) {
                val said = runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw DiscordException(
                    when (code) {
                        401 -> "Discord doesn't accept the bot's token any more: connect again from your phone"
                        else -> said?.let { "Discord: $it" } ?: "Discord answered $code"
                    },
                    code,
                )
            }
            result = if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }
        result
    }

    companion object {
        private const val API = "https://discord.com/api/v10"
        private val JSON = "application/json".toMediaType()
        private const val TEXT = 0
        private const val ANNOUNCEMENT = 5

        /** View Channel, Send Messages, Embed Links, Read Message History and Manage Webhooks. */
        private const val PERMISSIONS = 1024L + 2048L + 16384L + 65536L + 536870912L

        fun name(u: JSONObject): String =
            u.optString("global_name").takeIf { it.isNotBlank() && it != "null" } ?: u.optString("username")

        fun avatar(u: JSONObject): String? {
            val id = u.optString("id")
            val hash = u.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
            return if (hash != null) "https://cdn.discordapp.com/avatars/$id/$hash.png?size=128"
            else id.toLongOrNull()?.let { "https://cdn.discordapp.com/embed/avatars/${(it shr 22) % 6}.png" }
        }

        /** A message from Discord's JSON (the API's or the gateway's). */
        fun message(m: JSONObject): DiscordMessage {
            val author = m.optJSONObject("author") ?: JSONObject()
            val mentions = m.optJSONArray("mentions")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { u -> u.optString("id") to name(u) } } }.orEmpty()
            return DiscordMessage(
                id = m.getString("id"),
                channelId = m.optString("channel_id"),
                authorId = author.optString("id"),
                authorName = name(author),
                avatar = avatar(author),
                text = plain(m.optString("content"), mentions),
                at = runCatching { OffsetDateTime.parse(m.optString("timestamp")).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
                webhookId = m.optString("webhook_id").takeIf { it.isNotBlank() && it != "null" },
                bot = author.optBoolean("bot"),
                images = m.optJSONArray("attachments")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }
                        .filter { it.optString("content_type").startsWith("image/") || it.optString("filename").substringAfterLast('.').lowercase() in IMAGE_TYPES }
                        .map { it.optString("proxy_url").ifBlank { it.optString("url") } }
                }.orEmpty(),
                embeds = embeds(m.optJSONArray("embeds")),
            )
        }

        fun embeds(a: JSONArray?): List<DiscordEmbed> = a?.let {
            (0 until it.length()).map { i ->
                val e = it.getJSONObject(i)
                DiscordEmbed(
                    title = e.optString("title").takeIf { t -> t.isNotBlank() },
                    description = e.optString("description").takeIf { t -> t.isNotBlank() },
                    url = e.optString("url").takeIf { t -> t.isNotBlank() },
                    image = (e.optJSONObject("thumbnail") ?: e.optJSONObject("image"))?.let { p -> p.optString("proxy_url").ifBlank { p.optString("url") } }?.takeIf { t -> t.isNotBlank() },
                )
            }.filter { e -> e.title != null || e.image != null }
        }.orEmpty()

        private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp")

        /** Discord's markup as plain words: mentions by name, custom emoji by name, no ** or __. */
        fun plain(text: String, mentions: Map<String, String>): String = text
            .replace(Regex("<@!?(\\d+)>")) { m -> "@" + (mentions[m.groupValues[1]] ?: "someone") }
            .replace(Regex("<#(\\d+)>"), "#channel")
            .replace(Regex("<@&(\\d+)>"), "@role")
            .replace(Regex("<a?:(\\w+):\\d+>")) { m -> ":" + m.groupValues[1] + ":" }
            .replace(Regex("<t:(\\d+)(:\\w)?>")) { m -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(m.groupValues[1].toLong() * 1000)) }
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("~~(.+?)~~"), "$1")
            .replace(Regex("`{1,3}([^`]+)`{1,3}"), "$1")
            .replace(Regex("^#{1,3} ", RegexOption.MULTILINE), "")
            .trim()
    }
}
