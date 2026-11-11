package io.github.bornaradusin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.IptvAccount
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.TvGroup
import io.github.bornaradusin.launcher.iptv.XtreamException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hours between films and series refreshes, as Settings offers them (0: by hand). */
val VOD_HOURS = listOf(0, 12, 24, 48, 168)

fun vodHoursText(h: Int) = when (h) {
    0 -> "By hand only"
    168 -> "Every 7 days"
    else -> "Every $h hours"
}

/** Connects to an Xtream provider: the account is checked first, and your groups stay if it's the same one. */
suspend fun connectIptv(app: LauncherApp, server: String, username: String, password: String): String {
    val (root, info) = withContext(Dispatchers.IO) { app.iptv.xtream.login(server, username, password) }
    val old = app.store.config.value.iptv
    val same = old != null && old.server.equals(root, true) && old.username == username.trim()
    app.store.change {
        it.copy(iptv = if (same && old != null) old.copy(server = root, password = password) else IptvAccount(root, username.trim(), password))
    }
    if (same) app.iptv.refreshAll()
    val until = info.expires?.let { " · active until " + SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(it)) }.orEmpty()
    return "Connected to ${root.substringAfter("://")}$until"
}

/** Settings › TV (IPTV): the account, refreshing, films and series, playback, and your groups on the TV tab. */
@Composable
fun IptvSettings(app: LauncherApp, toast: (String) -> Unit, openPhone: () -> Unit) {
    val config by app.store.config.collectAsState()
    val status by app.iptv.state.collectAsState()
    val live by app.iptv.live.collectAsState()
    val elsewhere by app.iptv.elsewhere.collectAsState()
    val scope = rememberCoroutineScope()
    val account = config.iptv

    // Typing the account: server, then user name, then password
    var step by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf(account?.server.orEmpty()) }
    var user by remember { mutableStateOf(account?.username.orEmpty()) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TvGroup?>(null) }
    var groupMenu by remember { mutableStateOf<TvGroup?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val overlay = step != 0 || naming || renaming != null || groupMenu != null || signingOut

    val first = remember { FocusRequester() }
    val focus = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = focus.getOrPut(k) { FocusRequester() }
    LaunchedEffect(importing) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    BackHandler(enabled = moving != null) { moving = null }
    BackHandler(enabled = importing && !overlay) { importing = false }

    if (importing && account != null) {
        ImportPage(app, account, live, first, toast)
        return
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTvEnabled provides !overlay) {
            SettingsPage("SETTINGS", "TV (IPTV)", "Quicker from your phone, but it all works with the remote too.") {
                item { SectionLabel("Account") }
                if (account == null) {
                    item {
                        SettingRow(
                            Ic.tv, "Connect your provider", if (connecting) "Connecting…" else "Xtream: its server's address, your user name and password",
                            onClick = { step = 1 }, focusRequester = first,
                        )
                    }
                    item {
                        SettingRow(Ic.phone, "From your phone", "Type them on your phone instead: Set up from your phone › Accounts", onClick = openPhone)
                    }
                    return@SettingsPage
                }
                item {
                    val info = status.account
                    val until = info?.expires?.let { " · active until " + SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(it)) }.orEmpty()
                    val screens = info?.takeIf { it.max > 0 }?.let { " · ${it.active} of ${it.max} screens" }.orEmpty()
                    SettingRow(
                        Ic.tv, "Xtream",
                        status.problem ?: "${account.username} on ${account.host()}$until$screens · OK to change it",
                        value = if (status.problem == null) "Connected" else null,
                        good = status.problem == null,
                        onClick = {
                            server = account.server
                            user = account.username
                            step = 1
                        },
                        focusRequester = first,
                    )
                }
                item {
                    val at = status.liveAt.takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
                    SettingRow(
                        Ic.restart, "Refresh now",
                        when {
                            status.liveLoading && status.liveTotal > 0 -> "Loading: ${status.liveDone} of ${status.liveTotal} groups · %,d channels so far".format(status.channels)
                            status.liveLoading || status.filmsLoading || status.seriesLoading -> "Loading…"
                            else -> listOfNotNull(
                                at?.let { "Last done $it" },
                                "%,d channels".format(status.channels),
                                if (account.films) "%,d films".format(status.films) else null,
                                if (account.series) "%,d series".format(status.series) else null,
                            ).joinToString(" · ")
                        },
                        onClick = {
                            app.iptv.refreshAll()
                            toast("Fetching the lists again: what's there stays until the new ones are in")
                        },
                    )
                }
                item {
                    SettingRow(Ic.close, "Sign out", "The TV tab goes, with your groups and where you got to", onClick = { signingOut = true })
                }

                // What it connects to: the provider's lists and what you pick; other servers stopped, or warned about
                item { SectionLabel("What it connects to") }
                item {
                    val warnings = remember(elsewhere, account) { app.iptv.warnings() }
                    SettingRow(
                        if (warnings.isEmpty()) Ic.check else Ic.warn,
                        if (warnings.isEmpty()) "Only your provider" else warnings.first().first,
                        if (warnings.isEmpty()) "${account.host()}: the lists, and only the channel, film or episode you pick. No pings, nothing passed on"
                        else warnings.first().second,
                        good = warnings.isEmpty(),
                        onClick = {
                            toast(
                                "IPTV fetches the lists (with the account's state) twice a day, the guide and film details as you look, " +
                                    "and plays only what you pick. Logos and posters come from wherever the provider keeps them; trailers from YouTube, only when you pick one. " +
                                    "Nothing is passed on: the phone page only shows the lists, on your home network.",
                            )
                        },
                    )
                }
                item {
                    SettingRow(
                        Ic.close, "Block other servers",
                        if (account.blockOthers) "On: anything your provider sends to another server is stopped, unless you allow that server below"
                        else "Off: followed (some providers need it for channels to play), with a warning",
                        on = account.blockOthers,
                        good = if (account.blockOthers) null else false,
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(blockOthers = !account.blockOthers)) } },
                    )
                }
                elsewhere.sorted().forEach { host ->
                    item(key = "host:$host") {
                        val allowed = !account.blockOthers || host in account.allowedHosts
                        SettingRow(
                            if (allowed) Ic.warn else Ic.check, host,
                            when {
                                !account.blockOthers -> "Your provider sends things here: followed, Block other servers is off"
                                allowed -> "Your provider sends things here: you allowed it · OK blocks it again"
                                else -> "Your provider tried to send things here: stopped · OK allows it"
                            },
                            value = if (allowed) "Allowed" else "Blocked",
                            good = if (allowed) false else true,
                            onClick = { if (account.blockOthers) app.iptv.allowHost(host, !allowed) },
                        )
                    }
                }
                if (elsewhere.isNotEmpty()) item {
                    SettingRow(
                        Ic.trash, "Forget these servers", "The list starts again: a server shows up here again if your provider sends anything to it",
                        onClick = {
                            app.iptv.forgetElsewhere()
                            toast("Forgotten")
                        },
                    )
                }

                item { SectionLabel("Groups on the TV tab · OK shows or hides · hold OK to move, rename or delete") }
                val groups = account.groups
                itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
                    val count = app.iptv.groupChannels(g, live).size
                    val isMoving = moving == g.id
                    val moveKeys: (KeyEvent) -> Boolean = { e ->
                        when (e.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (e.isDown()) {
                                    app.iptv.moveGroup(g.id, if (e.key == Key.DirectionUp) -1 else 1)
                                    scope.launch {
                                        delay(60)
                                        runCatching { req("g:${g.id}").requestFocus() }
                                    }
                                }
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    SettingRow(
                        if (i == 0) Ic.star else Ic.list, g.name,
                        if (isMoving) "Moving: ▲ ▼ to move it, OK to drop it there"
                        else "$count channel${if (count == 1) "" else "s"} · " + if (g.category != null) "from the provider, kept up to date" else "yours",
                        value = if (g.on) "Shown" else "Hidden",
                        on = null,
                        lifted = isMoving,
                        handle = true,
                        onClick = { if (isMoving) moving = null else app.iptv.showGroup(g.id, !g.on) },
                        onLongClick = { if (moving == null) groupMenu = g },
                        onKey = if (isMoving) moveKeys else null,
                        focusRequester = req("g:${g.id}"),
                    )
                }
                item {
                    SettingRow(
                        Ic.plus, "New group", "Name it, then add channels: hold OK on any channel, or pick them on your phone",
                        onClick = { naming = true },
                    )
                }
                item {
                    SettingRow(
                        Ic.download, "Import a group from your provider", "${live.categories.size} groups · it follows the provider's, kept up to date",
                        onClick = { importing = true },
                    )
                }
                item {
                    SettingRow(
                        Ic.layers, "The provider's other groups",
                        if (account.providerGroups) "Shown after yours on the TV tab" else "Hidden: only your groups (and imported ones) show",
                        on = account.providerGroups,
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(providerGroups = !account.providerGroups)) } },
                    )
                }
                item {
                    SettingRow(Ic.phone, "Groups from your phone", "Easiest there: search all the channels, tick them and drag them into order", onClick = openPhone)
                }

                item { SectionLabel("Films and series") }
                item {
                    SettingRow(
                        Ic.film, "Films", if (account.films) "%,d from the provider, by their groups".format(status.films) else "Off: no Films on the TV tab",
                        on = account.films,
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(films = !account.films)) } },
                    )
                }
                item {
                    SettingRow(
                        Ic.tv, "Series", if (account.series) "%,d, with seasons, episodes and where you got to".format(status.series) else "Off: no Series on the TV tab",
                        on = account.series,
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(series = !account.series)) } },
                    )
                }
                item {
                    SettingRow(
                        Ic.eye, "Hide adult groups", "And anything the provider marks for adults, everywhere on the TV tab",
                        on = account.hideAdult,
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(hideAdult = !account.hideAdult)) } },
                    )
                }
                item {
                    SettingRow(
                        Ic.clock, "Fetched again",
                        status.vodProblem ?: "How often the films and series lists come again · Refresh now, above, does it at once",
                        value = vodHoursText(account.vodHours),
                        good = if (status.vodProblem != null) false else null,
                        onClick = {
                            val next = VOD_HOURS[(VOD_HOURS.indexOf(account.vodHours) + 1).mod(VOD_HOURS.size)]
                            app.store.change { c -> c.copy(iptv = c.iptv?.copy(vodHours = next)) }
                        },
                    )
                }

                item { SectionLabel("Playback") }
                item {
                    SettingRow(
                        Ic.download, "Buffer", bufferText(account.buffer) + " · bigger rides out a shaky connection, but channels take longer to start",
                        value = BUFFER_NAMES[account.buffer.coerceIn(0, 4)],
                        onClick = { app.store.change { c -> c.copy(iptv = c.iptv?.copy(buffer = (account.buffer + 1) % BUFFER_NAMES.size)) } },
                    )
                }
            }
        }

        if (step == 1) {
            TextEntry("IPTV · the server's address", server, "Like http://tv.example.net:8080 · Done for the next step · Back to leave it") { v ->
                if (v == null) step = 0 else {
                    server = v.trim()
                    step = 2
                }
            }
        }
        if (step == 2) {
            TextEntry("IPTV · your user name", user, "Done for the password · Back to leave it") { v ->
                if (v == null) step = 0 else {
                    user = v.trim()
                    step = 3
                }
            }
        }
        if (step == 3) {
            TextEntry("IPTV · your password", "", "Done connects · Back to leave it", password = true) { v ->
                step = 0
                if (v != null) {
                    connecting = true
                    scope.launch {
                        try {
                            toast(connectIptv(app, server, user, v))
                        } catch (e: XtreamException) {
                            toast(e.message ?: "That didn't work")
                        } finally {
                            connecting = false
                        }
                    }
                }
            }
        }
        if (naming) {
            TextEntry("New group", "", "Its name: the keyboard's Done key makes it · Back to leave it") { name ->
                naming = false
                if (!name.isNullOrBlank()) {
                    app.iptv.newGroup(name)
                    toast("“${name.trim()}” is made: hold OK on any channel to add it, or pick them on your phone")
                }
            }
        }
        renaming?.let { g ->
            TextEntry("Rename ${g.name}", g.name, "The keyboard's Done key keeps it · Back to leave it") { name ->
                renaming = null
                if (!name.isNullOrBlank()) app.iptv.saveGroup(g.id, name, null)
            }
        }
        groupMenu?.let { g ->
            ChoicePanel(
                g.name,
                listOf(
                    MenuItem(Ic.drag, "Move it") {
                        groupMenu = null
                        moving = g.id
                        scope.launch {
                            delay(60)
                            runCatching { req("g:${g.id}").requestFocus() }
                        }
                    },
                    MenuItem(Ic.edit, "Rename it") {
                        groupMenu = null
                        renaming = g
                    },
                    MenuItem(Ic.trash, "Delete it") {
                        groupMenu = null
                        app.iptv.deleteGroup(g.id)
                        toast("“${g.name}” is gone")
                    },
                ),
                onClose = { groupMenu = null },
            )
        }
        if (signingOut) {
            ChoicePanel(
                "Sign out of IPTV? The TV tab, your groups and where you got to in films and series go.",
                listOf(
                    MenuItem(Ic.back, "Keep it") { signingOut = false },
                    MenuItem(Ic.close, "Sign out") {
                        signingOut = false
                        app.store.config.value.iptv?.let { app.iptv.forget(it) }
                        app.store.change { it.copy(iptv = null) }
                        toast("Signed out of IPTV")
                    },
                ),
                onClose = { signingOut = false },
            )
        }
    }
}

/** Import a group: the provider's groups, OK makes one follow it on the TV tab. */
@Composable
private fun ImportPage(app: LauncherApp, account: IptvAccount, live: io.github.bornaradusin.launcher.iptv.LiveCatalog, first: FocusRequester, toast: (String) -> Unit) {
    val cats = remember(live, account.hideAdult) { live.categories.filter { !account.hideAdult || !it.adult } }
    SettingsPage(
        "SETTINGS · IPTV", "Import a group",
        "OK puts one of your provider's groups on the TV tab. It follows the provider's: channels they add or drop come and go by themselves. Back when you're done.",
    ) {
        if (cats.isEmpty()) item {
            Text("The provider's groups haven't come yet.", style = Tv.text(11f, color = Tv.muted), modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
        itemsIndexed(cats, key = { _, c -> c.id }) { i, c ->
            val added = account.groups.any { it.category == c.id }
            val count = live.byCategory[c.id]?.size ?: 0
            SettingRow(
                Ic.list, c.name, "$count channel${if (count == 1) "" else "s"}",
                value = if (added) "Added" else "Import",
                good = if (added) true else null,
                onClick = {
                    if (added) toast("${c.name} is on the TV tab already")
                    else if (app.iptv.importCategory(c.id)) toast("${c.name} is on the TV tab, kept up to date")
                },
                focusRequester = if (i == 0) first else null,
            )
        }
    }
}
