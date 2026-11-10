package io.github.bornaradusin.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.compose.AsyncImage
import io.github.bornaradusin.launcher.DiscordAccount
import io.github.bornaradusin.launcher.DiscordChat
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.discord.ChatState
import io.github.bornaradusin.launcher.discord.DiscordEmbed
import io.github.bornaradusin.launcher.discord.DiscordException
import io.github.bornaradusin.launcher.discord.DiscordMessage
import io.github.bornaradusin.launcher.discord.Link
import io.github.bornaradusin.launcher.seerr.MediaState
import io.github.bornaradusin.launcher.seerr.SeerrSeason
import io.github.bornaradusin.launcher.seerr.SeerrTitle
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/** Things the Discord tab asks of the rest of the launcher. */
class DiscordActions(
    val openSettings: () -> Unit,
    val toast: (String) -> Unit,
)

private val BLURPLE = Color(0xFF8C96FF)
private val HUES = listOf(Color(0xFFF5C26B), Color(0xFF9FE6B8), Color(0xFF7CC4FF), Color(0xFFFF9DB0), Color(0xFFC7A6FF), Color(0xFFFFB27A))
private fun hue(key: String) = HUES[(key.hashCode() and 0x7fffffff) % HUES.size]

/** Quick answers: one press sends them. */
private val QUICK = listOf("Cheers!", "Is it done yet?", "No rush")

/** What a chat is called: the one other person in it (like a direct message), else the channel. */
private class ChatLook(val title: String, val avatar: String?, val key: String)

private fun look(app: LauncherApp, chat: DiscordChat, state: ChatState?, a: DiscordAccount): ChatLook {
    val others = state?.messages.orEmpty().filter { !it.bot && it.webhookId == null && !app.discordChats.fromYou(it, a) }.distinctBy { it.authorId }
    return if (others.size == 1) ChatLook(others[0].authorName, others[0].avatar, others[0].authorId) else ChatLook("#${chat.name}", null, chat.channelId)
}

/**
 * The Discord tab: the pinned chats on the left, the open one on the right with its messages, a box to
 * write in (the keyboard, or the remote's mic), quick answers, and Send a film or show (Seerr's search,
 * then one press sends it as a card).
 */
@Composable
fun DiscordScreen(app: LauncherApp, actions: DiscordActions, onTab: (Tab) -> Unit, startOnTabs: Boolean) {
    val config by app.store.config.collectAsState()
    val account = config.discord ?: return
    val chats by app.discordChats.chats.collectAsState()
    val unread by app.discordChats.unread.collectAsState()
    val link by app.discordChats.link.collectAsState()
    val problem by app.discordChats.problem.collectAsState()
    val noWebhook by app.discordChats.noWebhook.collectAsState()
    val weather by app.weather.now.collectAsState()
    val update by app.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val chat = account.pinned.firstOrNull { it.channelId == selected } ?: account.pinned.firstOrNull()
    val state = chat?.let { chats[it.channelId] }
    val chatLook = chat?.let { look(app, it, state, account) }

    var draft by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var sendTitle by remember { mutableStateOf<SeerrTitle?>(null) }
    var menuFor by remember { mutableStateOf<DiscordChat?>(null) }
    var sending by remember { mutableStateOf(false) }
    val overlay = typing || picking || sendTitle != null || menuFor != null

    val tabFocus = remember { FocusRequester() }
    val composeFocus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }
    val chatFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun chatRequester(id: String) = chatFocus.getOrPut(id) { FocusRequester() }

    // On screen: the open chat is read as messages come, and doesn't pop up
    LifecycleResumeEffect(chat?.channelId) {
        app.discordChats.viewing = chat?.channelId
        onPauseOrDispose { app.discordChats.viewing = null }
    }

    LaunchedEffect(Unit) {
        delay(80)
        val target = when {
            startOnTabs -> tabFocus
            chat != null -> chatRequester(chat.channelId)
            else -> tabFocus
        }
        runCatching { target.requestFocus() }
    }

    fun later(f: FocusRequester) {
        scope.launch {
            delay(60)
            runCatching { f.requestFocus() }
        }
    }

    fun send(text: String, done: () -> Unit = {}) {
        val c = chat ?: return
        if (sending || text.isBlank()) return
        sending = true
        scope.launch {
            try {
                app.discordChats.send(c.channelId, text)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiscordException) {
                actions.toast(e.message ?: "Discord didn't take the message")
            } finally {
                sending = false
            }
        }
    }

    // The remote's mic (Android's voice typing): what was said goes in the box, for OK to send
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            draft = said
            later(sendFocus)
        }
    }
    fun speak() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message to ${chatLook?.title ?: "the chat"}")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            actions.toast("This TV has no voice typing: OK on the box opens the keyboard, and its mic works there")
        }
    }

    Box(
        Modifier.fillMaxSize().background(Tv.bg)
            .background(Brush.radialGradient(listOf(Color(0x298C96FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1580f, 120f), radius = 900f)),
    ) {
        CompositionLocalProvider(LocalTvEnabled provides !overlay) {
            TopBar(
                tab = Tab.DISCORD,
                onTab = onTab,
                tabFocus = tabFocus,
                small = true,
                weather = weather,
                update = (update as? UpdateState.Available)?.update?.version,
                onUpdate = { onTab(Tab.HOME) },
                onSearch = { if (config.seerr != null) picking = true else actions.toast("Connect Seerr from your phone to search films and shows") },
                onSettings = actions.openSettings,
            )

            Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 22.dp)) {
                // The pinned chats
                Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)) {
                        Icon(Ic.pin, null, Modifier.size(10.dp), tint = Tv.muted)
                        Text("PINNED CHATS", style = Tv.text(9f, FontWeight.Bold, Tv.muted, spacing = 1.3f))
                    }
                    if (account.pinned.isEmpty()) {
                        Text(
                            "No chats pinned yet. Pick them on your phone: Set up from your phone › Accounts › Discord.",
                            style = Tv.text(11f, color = Tv.muted, lineHeight = 16f), modifier = Modifier.padding(start = 3.dp, top = 4.dp),
                        )
                    }
                    account.pinned.forEach { c ->
                        val s = chats[c.channelId]
                        ChatItem(
                            look = look(app, c, s, account),
                            chat = c,
                            last = s?.messages?.lastOrNull(),
                            lastYours = s?.messages?.lastOrNull()?.let { app.discordChats.fromYou(it, account) } == true,
                            unread = unread[c.channelId] ?: 0,
                            selected = c.channelId == chat?.channelId,
                            focusRequester = chatRequester(c.channelId),
                            onClick = {
                                selected = c.channelId
                                later(composeFocus)
                            },
                            onFocus = { selected = c.channelId },
                            onHold = { menuFor = c },
                        )
                    }
                    if (account.pinned.isNotEmpty()) {
                        Text(
                            "Hold OK on a chat to move or unpin it. More chats to pin are on your phone.",
                            style = Tv.text(9f, color = Tv.muted, lineHeight = 13f), modifier = Modifier.padding(start = 3.dp, top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.width(32.dp))

                // The open chat
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (chat == null || chatLook == null) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x99111621)).border(1.dp, Tv.line, RoundedCornerShape(14.dp))) {
                            Text(
                                problem ?: "Pin a chat from your phone to see it here.",
                                style = Tv.text(12f, color = Tv.muted), modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    } else {
                        Conversation(
                            app, account, chat, chatLook, state, link, problem,
                            sendingAsBot = account.me != null && chat.channelId in noWebhook,
                            modifier = Modifier.weight(1f),
                        )
                        ComposeBox(
                            draft = draft,
                            placeholder = "Message ${chatLook.title}: OK to type",
                            focusRequester = composeFocus,
                            onClick = { typing = true },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (draft.isNotBlank()) {
                                PillButton(if (sending) "Sending…" else "Send", { send(draft) { draft = "" } }, icon = Ic.send, primary = true, focusRequester = sendFocus, height = 30.dp, textSize = 11f)
                            }
                            if (config.seerr != null) {
                                PillButton("Send a film or show", { picking = true }, icon = Ic.film, primary = draft.isBlank(), height = 30.dp, textSize = 11f)
                            }
                            PillButton("Say it", { speak() }, icon = Ic.mic, height = 30.dp, textSize = 11f)
                            QUICK.forEach { q -> PillButton(q, { send(q) }, height = 30.dp, textSize = 11f) }
                        }
                    }
                }
            }
        }

        if (typing) {
            BackHandler { typing = false; later(composeFocus) }
            TypingBox(
                title = chatLook?.title ?: "",
                initial = draft,
                onDone = { text, sendIt ->
                    typing = false
                    draft = text
                    if (sendIt && text.isNotBlank()) send(text) { draft = "" }
                    later(composeFocus)
                },
            )
        }

        if (picking) {
            BackHandler(enabled = sendTitle == null) { picking = false; later(composeFocus) }
            CompositionLocalProvider(LocalTvEnabled provides (sendTitle == null)) {
                SearchPanel(
                    app,
                    onOpen = { sendTitle = it },
                    onHold = {},
                    heading = "SEND TO ${chatLook?.title?.uppercase() ?: "THE CHAT"}",
                    okHint = { t -> if (t.movie) "OK to send it" else "OK to pick seasons and send it" },
                )
            }
        }

        sendTitle?.let { t ->
            BackHandler { sendTitle = null }
            SendTitleDialog(
                app, t, chatLook?.title ?: "the chat",
                onBack = { sendTitle = null },
                onSend = { text ->
                    sendTitle = null
                    picking = false
                    send(text) { actions.toast("${t.title} is sent to ${chatLook?.title ?: "the chat"}") }
                    later(composeFocus)
                },
            )
        }

        menuFor?.let { c ->
            BackHandler { menuFor = null; later(chatRequester(c.channelId)) }
            val pinned = account.pinned
            val i = pinned.indexOfFirst { it.channelId == c.channelId }
            fun move(to: Int) {
                app.store.change { cfg ->
                    val d = cfg.discord ?: return@change cfg
                    val list = d.pinned.toMutableList()
                    val from = list.indexOfFirst { it.channelId == c.channelId }
                    if (from < 0 || to !in list.indices) return@change cfg
                    list.add(to, list.removeAt(from))
                    cfg.copy(discord = d.copy(pinned = list))
                }
                menuFor = null
                later(chatRequester(c.channelId))
            }
            MenuPanel(
                "#${c.name} · ${c.guildName}",
                buildList {
                    if (i > 0) add(MenuItem(Ic.up, "Move up") { move(i - 1) })
                    if (i in 0 until pinned.size - 1) add(MenuItem(Ic.down, "Move down") { move(i + 1) })
                    add(MenuItem(Ic.close, "Unpin") {
                        app.store.change { cfg -> cfg.copy(discord = cfg.discord?.let { d -> d.copy(pinned = d.pinned.filter { it.channelId != c.channelId }) }) }
                        menuFor = null
                        later(tabFocus)
                    })
                },
            )
        }
    }
}

@Composable
private fun Avatar(name: String, url: String?, key: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(hue(key)), contentAlignment = Alignment.Center) {
        Text(name.trimStart('#', '@').take(1).uppercase(), style = Tv.text(size.value * 0.42f, FontWeight.Bold, Tv.bg))
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ChatItem(
    look: ChatLook,
    chat: DiscordChat,
    last: DiscordMessage?,
    lastYours: Boolean,
    unread: Int,
    selected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onHold: () -> Unit,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(shape)
            .background(if (focused) Color(0x1FFFFFFF) else if (selected) Color(0x1F8C96FF) else Color(0xB8111621))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Tv.ink else Tv.line, shape)
            .tvItem(onClick = onClick, onLongClick = onHold, onFocus = onFocus, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (selected && !focused) Box(Modifier.width(2.dp).height(30.dp).clip(RoundedCornerShape(1.dp)).background(BLURPLE))
        Avatar(look.title, look.avatar, look.key, 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(look.title, style = Tv.text(13f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("#${chat.name} · ${chat.guildName}", style = Tv.text(8.5f, color = Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val preview = last?.let { (if (lastYours) "You: " else "") + it.preview } ?: ""
            Text(preview, style = Tv.text(10f, color = if (unread > 0) Tv.ink2 else Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            last?.let { Text(whenText(context, it.at, short = true), style = Tv.text(8.5f, color = Tv.muted)) }
            if (unread > 0) {
                Box(Modifier.heightIn(min = 16.dp).widthIn(min = 16.dp).clip(RoundedCornerShape(8.dp)).background(BLURPLE).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(if (unread > 99) "99+" else "$unread", style = Tv.text(9f, FontWeight.Bold, Tv.bg))
                }
            }
        }
    }
}

/** A row of the conversation: a day's heading, or a message. */
private sealed interface Line {
    class Day(val label: String, val key: String) : Line
    class Msg(val m: DiscordMessage, val first: Boolean) : Line
}

@Composable
private fun Conversation(
    app: LauncherApp,
    account: DiscordAccount,
    chat: DiscordChat,
    look: ChatLook,
    state: ChatState?,
    link: Link,
    problem: String?,
    sendingAsBot: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val messages = state?.messages.orEmpty()

    // Days, then messages; a message from the same person a little after the last doesn't repeat the name
    val lines = remember(messages) {
        val out = mutableListOf<Line>()
        var lastDay = ""
        var lastAuthor = ""
        var lastAt = 0L
        messages.forEach { m ->
            val day = dayLabel(context, m.at)
            if (day != lastDay) {
                out += Line.Day(day, "day-${m.id}")
                lastDay = day
                lastAuthor = ""
            }
            out += Line.Msg(m, first = m.authorId != lastAuthor || m.at - lastAt > 5 * 60_000)
            lastAuthor = m.authorId
            lastAt = m.at
        }
        out.asReversed().toList()
    }
    // Newest at the bottom: a new message brings the chat back to it
    LaunchedEffect(messages.lastOrNull()?.id) { if (!focused) list.scrollToItem(0) }

    Column(
        modifier.fillMaxWidth().clip(shape).background(Color(0x99111621))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Tv.ink else Tv.line, shape)
            .padding(horizontal = 17.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(look.title, look.avatar, look.key, 32.dp)
            Column(Modifier.weight(1f)) {
                Text(look.title, style = Tv.text(17f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Ic.hash, null, Modifier.size(9.dp), tint = Tv.muted)
                    Text("${chat.name} · ${chat.guildName}", style = Tv.text(9.5f, color = Tv.muted), maxLines = 1)
                }
            }
            val (dot, words) = when (link) {
                Link.LIVE -> Tv.ok to "Live"
                Link.CONNECTING -> AMBER_DOT to "Connecting to Discord…"
                Link.FAILED -> Tv.bad to (problem ?: "Not connected")
                Link.OFF -> Tv.muted to "Not connected"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Text(words, style = Tv.text(9.5f, color = if (link == Link.FAILED) Tv.bad else Tv.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.padding(vertical = 11.dp).fillMaxWidth().height(1.dp).background(Tv.line))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .tvItem(
                    onClick = {},
                    onKey = { e ->
                        if (!e.isDown()) false else when (e.key) {
                            // ▲ goes back in the chat, ▼ forward; at either end the remote moves on
                            Key.DirectionUp -> if (list.canScrollForward) { scope.launch { list.animateScrollBy(step) }; true } else false
                            Key.DirectionDown -> if (list.canScrollBackward) { scope.launch { list.animateScrollBy(-step) }; true } else false
                            else -> false
                        }
                    },
                ),
        ) {
            when {
                state == null || !state.loaded -> Text("Loading the chat…", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.align(Alignment.Center))
                state.error != null && messages.isEmpty() -> Text(state.error, style = Tv.text(11f, color = Tv.bad, lineHeight = 16f), modifier = Modifier.align(Alignment.Center).padding(horizontal = 30.dp))
                messages.isEmpty() -> Text("Nothing here yet: say hello", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    state = list,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(lines, key = { l -> if (l is Line.Msg) l.m.id else (l as Line.Day).key }) { l ->
                        when (l) {
                            is Line.Day -> DayLine(l.label)
                            is Line.Msg -> MessageRow(l.m, l.first, app.discordChats.fromTv(l.m, account), account)
                        }
                    }
                }
            }
            if (focused) {
                Text(
                    "▲ ▼ to read back", style = Tv.text(8.5f, color = Tv.muted),
                    modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(Color(0xE6111621)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        if (sendingAsBot) {
            Text(
                "Messages from the TV go as the bot here: in Discord, let the bot Manage Webhooks in this channel to send as ${account.me?.name}.",
                style = Tv.text(8.5f, color = Tv.muted), modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private val AMBER_DOT = Color(0xFFF5C26B)

@Composable
private fun DayLine(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(Tv.line))
        Text(label, style = Tv.text(8.5f, FontWeight.SemiBold, Tv.muted))
        Box(Modifier.weight(1f).height(1.dp).background(Tv.line))
    }
}

@Composable
private fun MessageRow(m: DiscordMessage, first: Boolean, fromTv: Boolean, account: DiscordAccount) {
    val context = LocalContext.current
    val name = if (fromTv && m.webhookId == null) account.me?.name ?: m.authorName else m.authorName
    val avatar = if (fromTv && m.webhookId == null) account.me?.avatar ?: m.avatar else m.avatar
    val key = if (fromTv) account.me?.id ?: m.authorId else m.authorId
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.width(24.dp)) { if (first) Avatar(name, avatar, key, 24.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (first) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = Tv.text(10.5f, FontWeight.SemiBold, hue(key)))
                    if (fromTv) {
                        Text(
                            "FROM THE TV", style = Tv.text(7f, FontWeight.SemiBold, Tv.muted, spacing = 0.6f),
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x12FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Text(DateFormat.getTimeFormat(context).format(Date(m.at)), style = Tv.text(8.5f, color = Tv.muted))
                }
            }
            if (m.text.isNotBlank()) Text(m.text, style = Tv.text(11.5f, color = Tv.ink, lineHeight = 16f))
            m.embeds.forEach { EmbedCard(it) }
            m.images.take(2).forEach { url ->
                AsyncImage(url, null, Modifier.width(200.dp).height(112.dp).clip(RoundedCornerShape(8.dp)).background(Tv.off), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** A link's preview: a film's TMDB page shows its poster and title, say. */
@Composable
private fun EmbedCard(e: DiscordEmbed) {
    Row(
        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x0FFFFFFF)).border(1.dp, Tv.line, RoundedCornerShape(9.dp)).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        e.image?.let { AsyncImage(it, null, Modifier.width(36.dp).height(54.dp).clip(RoundedCornerShape(6.dp)).background(Tv.off), contentScale = ContentScale.Crop) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            e.title?.let { Text(it, style = Tv.text(12f, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            e.description?.let { Text(it, style = Tv.text(9f, color = Tv.muted, lineHeight = 12f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ComposeBox(draft: String, placeholder: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.5.dp)
    Row(
        Modifier.fillMaxWidth().height(35.dp).clip(shape)
            .background(if (focused) Color(0x1FFFFFFF) else Color(0x12FFFFFF))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Tv.ink else Tv.line, shape)
            .tvItem(onClick = onClick, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ic.chat, null, Modifier.size(13.dp), tint = Tv.muted)
        Text(
            draft.ifBlank { placeholder },
            style = Tv.text(11.5f, color = if (draft.isBlank()) Tv.muted else Tv.ink),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** Writing a message: Android's keyboard (its mic works too); its Send key sends. */
@Composable
private fun TypingBox(title: String, initial: String, onDone: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC04060A))) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 70.dp, start = 48.dp, end = 48.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MESSAGE ${title.uppercase()}", style = Tv.kicker)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF))
                    .border(1.5.dp, Tv.ink, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text("Type, or use the keyboard's mic", style = Tv.text(15f, color = Tv.muted))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = Tv.text(15f, FontWeight.Medium),
                    cursorBrush = SolidColor(Tv.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        keyboard?.hide()
                        onDone(text, true)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                        .onPreviewKeyEvent { e ->
                            // Back with the keyboard closed keeps what's written for later
                            if (e.key == Key.Back && !e.isDown()) {
                                onDone(text, false)
                                true
                            } else false
                        },
                )
            }
            Text("The keyboard's Send key sends it · Back keeps it in the box for later", style = Tv.hint)
        }
    }
}

/** A film or show picked to send: a show can say which seasons. */
@Composable
private fun SendTitleDialog(app: LauncherApp, t: SeerrTitle, to: String, onBack: () -> Unit, onSend: (String) -> Unit) {
    val config by app.store.config.collectAsState()
    val sendFocus = remember { FocusRequester() }
    var seasons by remember { mutableStateOf<List<SeerrSeason>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { sendFocus.requestFocus() }
        if (!t.movie) {
            val a = config.seerr ?: return@LaunchedEffect
            seasons = runCatching { app.seerr.details(a, t.tmdbId, false).seasons.filter { it.number > 0 } }.getOrDefault(emptyList())
            // The seasons not on Emby yet, picked to begin with
            picked = seasons.filter { it.state != MediaState.AVAILABLE }.map { it.number }.toSet().takeIf { it.size < seasons.size }.orEmpty()
        }
    }
    val text = titleMessage(t, picked.sorted())
    Box(Modifier.fillMaxSize().background(Color(0xD904060A))) {
        Row(
            Modifier.align(Alignment.Center).width(600.dp).shadow(60.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Tv.panel).border(1.dp, Tv.line, RoundedCornerShape(16.dp)).padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(9.dp)).background(Tv.off)) {
                AsyncImage(t.poster, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SEND TO ${to.uppercase()}", style = Tv.kicker)
                Text(t.title, style = Tv.text(22f, FontWeight.Bold, lineHeight = 25f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (t.movie) "Film" else "TV show", t.year).joinToString(" · "), style = Tv.meta)
                StateChip(t.state, t.download)
                if (seasons.isNotEmpty()) {
                    Text("Which seasons? None picked: the whole show.", style = Tv.text(9.5f, color = Tv.muted))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        seasons.take(10).forEach { s ->
                            val on = s.number in picked
                            SeasonChip(s, on) { picked = if (on) picked - s.number else picked + s.number }
                        }
                    }
                }
                Text(
                    text.replace("**", "").lineSequence().first(),
                    style = Tv.text(11f, color = Tv.ink2), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 9.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Send to $to", { onSend(text) }, icon = Ic.send, primary = true, focusRequester = sendFocus)
                    PillButton("Back", onBack, icon = Ic.back)
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(s: SeerrSeason, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        "S${s.number}",
        style = Tv.text(10.5f, FontWeight.SemiBold, if (focused) Tv.bg else if (on) Tv.bg else if (s.state == MediaState.AVAILABLE) Tv.muted else Tv.ink),
        modifier = Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (focused) Tv.ink else if (on) Tv.accent else Color(0x1AFFFFFF))
            .tvItem(onClick = onClick, onFocusChange = { focused = it })
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The message a film or show goes as: its name and year, the seasons, and its TMDB page (Discord shows the poster). */
private fun titleMessage(t: SeerrTitle, seasons: List<Int>): String {
    val name = t.title + (t.year?.let { " ($it)" } ?: "")
    val which = when {
        t.movie || seasons.isEmpty() -> ""
        seasons.size == 1 -> ", season ${seasons[0]}"
        else -> ", seasons " + seasons.joinToString(", ")
    }
    return "**$name**$which\nhttps://www.themoviedb.org/${if (t.movie) "movie" else "tv"}/${t.tmdbId}"
}

private fun dayLabel(context: android.content.Context, at: Long): String = when {
    DateUtils.isToday(at) -> "Today"
    DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
}

/** For the list of chats: the time today, else the day. */
private fun whenText(context: android.content.Context, at: Long, short: Boolean): String =
    if (DateUtils.isToday(at)) DateFormat.getTimeFormat(context).format(Date(at))
    else if (DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS)) "Yesterday"
    else DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or (if (short) DateUtils.FORMAT_NO_YEAR else 0))
