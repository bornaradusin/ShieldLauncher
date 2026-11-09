package io.github.bornaradusin.launcher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.R
import io.github.bornaradusin.launcher.RowKind
import io.github.bornaradusin.launcher.RowSetting
import io.github.bornaradusin.launcher.emby.EmbyLauncher
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.server.LocalServer
import io.github.bornaradusin.launcher.server.Notice
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.delay

/** A line in Settings: what it is, how it stands, and what OK does. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean? = null,
    value: String? = null,
    good: Boolean? = null,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    lifted: Boolean = false,
    handle: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(9.dp))
            .background(if (focused || lifted) Color(0x1AFFFFFF) else Color(0xB3111621))
            .border(if (focused || lifted) 1.5.dp else 1.dp, if (lifted) Tv.accent else if (focused) Tv.ink else Tv.line, RoundedCornerShape(9.dp))
            .tvItem(onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester, onFocusChange = { focused = it }, onKey = onKey)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (on == false) Tv.muted else Tv.ink2)
        Column(Modifier.weight(1f)) {
            Text(title, style = Tv.text(12.5f, FontWeight.SemiBold, if (on == false) Tv.muted else Tv.ink), maxLines = 1)
            if (sub.isNotBlank()) Text(
                sub, style = Tv.text(9f, color = if (good == true) Tv.okText else if (good == false) Tv.bad else Tv.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        value?.let { Text(it, style = Tv.text(11f, color = Tv.ink2)) }
        on?.let { Switch(it) }
        if (handle) Icon(Ic.drag, "Hold OK to move", Modifier.size(14.dp), tint = Tv.faint)
    }
}

@Composable
fun Switch(on: Boolean) {
    Box(Modifier.width(32.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Tv.accent else Tv.off)) {
        Box(
            Modifier.padding(start = if (on) 16.dp else 2.dp, top = 2.dp).size(14.dp).clip(RoundedCornerShape(7.dp))
                .background(if (on) Tv.bg else Color(0xFF8E99A8)),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Tv.text(8.5f, FontWeight.Bold, Tv.muted, spacing = 1.2f), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
}

/** A settings page: its name on the left, the lines on the right. */
@Composable
fun SettingsPage(kicker: String, title: String, text: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B101B), Tv.bg)))
            .background(Brush.radialGradient(listOf(Color(0x2E7CC4FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1540f, 220f), radius = 900f)),
    ) {
        Column(Modifier.padding(start = 48.dp, top = 75.dp).width(280.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(kicker, style = Tv.kicker)
            Text(title, style = Tv.text(32f, FontWeight.Bold, lineHeight = 35f))
            Text(text, style = Tv.text(12f, color = Tv.muted, lineHeight = 17f))
        }
        LazyColumn(
            Modifier.padding(start = 380.dp, end = 48.dp).fillMaxSize(),
            contentPadding = PaddingValues(top = 60.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = content,
        )
    }
}

enum class SettingsPageId { MAIN, ROWS, PHONE, HOME_BUTTON }

/** Settings: rows, phone set-up, accounts, trailers, notifications, updates and the Home button. */
@Composable
fun SettingsScreen(app: LauncherApp, onClose: () -> Unit, toast: (String) -> Unit, askChannels: () -> Unit) {
    var page by remember { mutableStateOf(SettingsPageId.MAIN) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    BackHandler {
        if (page == SettingsPageId.MAIN) onClose() else page = SettingsPageId.MAIN
    }
    when (page) {
        SettingsPageId.MAIN -> MainSettings(app, returnTo, open = { p, from -> returnTo = from; page = p }, toast = toast, askChannels = askChannels)
        SettingsPageId.ROWS -> RowsSettings(app)
        SettingsPageId.PHONE -> PhoneSetupScreen(app)
        SettingsPageId.HOME_BUTTON -> HomeButtonHelp(app)
    }
}

@Composable
private fun MainSettings(app: LauncherApp, returnTo: String?, open: (SettingsPageId, String) -> Unit, toast: (String) -> Unit, askChannels: () -> Unit) {
    val config by app.store.config.collectAsState()
    val update by app.updater.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = focus.getOrPut(k) { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { req(returnTo ?: "rows").requestFocus() }
    }
    // Some lines depend on things outside the launcher (Android's settings): looked at again now and then
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    val ip = remember(tick) { EmbyLauncher.localIp() }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(app) }
    val ports by ListenService.ports.collectAsState()
    val otherApp = remember(tick) { runCatching { app.packageManager.getPackageInfo(LocalServer.OTHER_APP, 0); true }.getOrDefault(false) }
    val channelsOk = remember(tick) { app.channels.allowed() }
    val isHome = remember(tick) { isHomeScreen(app) }
    val embyApp = app.embyLauncher.app(config.embyApp)
    val embyApps = remember(tick) { app.embyLauncher.installedApps() }

    SettingsPage("SETTINGS", "Shield Launcher", "Version ${app.version}. Most of this is quicker from your phone: Set up from your phone.") {
        item { SectionLabel("Home") }
        item {
            SettingRow(Ic.list, "Rows on Home", "Which rows show, and in what order",
                onClick = { open(SettingsPageId.ROWS, "rows") }, focusRequester = req("rows"))
        }
        item {
            SettingRow(Ic.phone, "Set up from your phone", "Rows, apps, Emby and Seerr sign-in, games: scan a code",
                onClick = { open(SettingsPageId.PHONE, "phone") }, focusRequester = req("phone"))
        }
        item {
            SettingRow(Ic.film, "Trailers behind films", "Muted, after 2 seconds on a film. Hold OK on a film for sound.",
                on = config.trailers, onClick = { app.store.change { it.copy(trailers = !it.trailers) } })
        }

        item { SectionLabel("Accounts") }
        item {
            val a = config.emby
            SettingRow(
                Ic.play, "Emby",
                if (a != null) "${a.userName} on ${a.serverName} · ${a.server}" else "Not signed in: sign in from your phone",
                good = a != null,
                onClick = {
                    if (a == null) open(SettingsPageId.PHONE, "emby") else {
                        app.feed.refresh()
                        toast("Emby's rows are being fetched again")
                    }
                },
                focusRequester = req("emby"),
            )
        }
        item {
            SettingRow(
                Ic.tv, "Films and episodes play in",
                if (embyApps.isEmpty()) "No Emby app on the Shield: install Emby for Android TV" else "OK to switch between the Emby apps installed",
                value = embyApps.firstOrNull { it.first == embyApp }?.second ?: "—",
                onClick = {
                    if (embyApps.size > 1) {
                        val i = embyApps.indexOfFirst { it.first == embyApp }
                        val next = embyApps[(i + 1) % embyApps.size].first
                        app.store.change { it.copy(embyApp = next) }
                    }
                },
            )
        }
        item {
            val a = config.seerr
            SettingRow(
                Ic.download, "Seerr",
                if (a != null) "${a.userName} on Seerr · ${a.server} · requests come from ${a.userName}" else "Not connected: connect from your phone (its server address and API key)",
                good = if (a != null) true else null,
                onClick = {
                    if (a == null) open(SettingsPageId.PHONE, "seerr") else {
                        app.requester.refresh()
                        toast("Requester's rows are being fetched again")
                    }
                },
                focusRequester = req("seerr"),
            )
        }
        item {
            SettingRow(
                Ic.gamepad, "Games from Artemis",
                if (config.games.isEmpty()) "In Artemis, hold a game and choose Create shortcut" else "${config.games.size} on Home · hold OK on one there to remove it",
                onClick = { toast("In Artemis, hold a game and choose Create shortcut: it shows up in Games") },
            )
        }

        item {
            val channels by app.channels.channels.collectAsState()
            SettingRow(
                Ic.star, "App channels",
                when {
                    !channelsOk -> "SmartTube's rows (and other apps'): OK to let Shield Launcher read TV listings"
                    channels.isEmpty() -> "Allowed · no app shares channels yet (SmartTube: its settings › Launcher channels)"
                    else -> "Allowed · ${channels.map { it.appName }.distinct().joinToString(", ")} · turn them on in Rows on Home"
                },
                good = if (channelsOk) true else null,
                onClick = {
                    if (!channelsOk) askChannels() else {
                        app.channels.refresh()
                        toast("Looking at the apps' channels again")
                    }
                },
            )
        }

        item { SectionLabel("Home Assistant") }
        item {
            SettingRow(
                Ic.bell, "Notifications on the TV",
                when {
                    !ports.notifications && otherApp -> "Port 7676 is held by Notifications for Android TV: uninstall it, this replaces it"
                    !ports.notifications -> "Port 7676 is held by another app: Home Assistant's notifications can't reach the launcher"
                    !overlayOk -> "Needs “Display over other apps”: OK to allow it"
                    else -> "Ready on ${ip ?: "the Shield's address"}:${LocalServer.PORT} · add “Notifications for Android TV” in Home Assistant"
                },
                good = overlayOk && ports.notifications,
                onClick = { if (!overlayOk) allowOverlay(app) },
            )
        }
        item {
            SettingRow(
                Ic.bell, "Send a test notification", "Shows over whatever is on, the way Home Assistant's do",
                onClick = {
                    val service = ListenService.running
                    if (service == null || !overlayOk) toast("Allow “Display over other apps” first")
                    else service.overlay.show(
                        Notice(
                            title = "Shield Launcher", message = "Notifications from Home Assistant show up like this.",
                            seconds = 6, position = 0, fontSize = 0, color = android.graphics.Color.rgb(17, 22, 33), transparency = 0f,
                            interrupt = false, icon = ContextCompat.getDrawable(app, R.drawable.ic_launcher)?.toBitmap(96, 96), image = null,
                        ),
                    )
                },
            )
        }

        item { SectionLabel("The Shield") }
        item {
            SettingRow(
                Ic.home, "Home button",
                if (isHome) "Shield Launcher is the home screen" else "Android TV Home still opens on Home: OK for how to switch it off",
                good = isHome,
                onClick = { open(SettingsPageId.HOME_BUTTON, "homebutton") },
                focusRequester = req("homebutton"),
            )
        }
        item {
            val sub = when (val u = update) {
                is UpdateState.Available -> "Version ${u.update.version} is ready: OK to update"
                is UpdateState.Downloading -> "Downloading ${u.update.version}: ${(u.progress * 100).toInt()}%"
                is UpdateState.NeedsPermission -> "Allow the launcher to install apps, then OK"
                is UpdateState.Failed -> u.message
                UpdateState.Checking -> "Looking on GitHub…"
                UpdateState.UpToDate -> "This is the newest version"
                UpdateState.Idle -> "Updates come from GitHub: OK to look now"
            }
            SettingRow(
                Ic.download, "Updates", sub, value = app.version,
                good = if (update is UpdateState.UpToDate) true else if (update is UpdateState.Failed) false else null,
                onClick = {
                    when (update) {
                        is UpdateState.Available, is UpdateState.Failed -> {
                            app.updater.open()
                            app.updater.update()
                            toast("Downloading the update: Android asks to install it when it's done")
                        }
                        is UpdateState.NeedsPermission -> app.updater.allowInstalls()
                        else -> app.updater.check(manual = true)
                    }
                },
            )
        }
        item {
            SettingRow(Ic.cog, "Android settings", "The Shield's own settings: network, display, sound, remotes",
                onClick = { runCatching { app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
    }
}

/** Rows on Home: OK turns one on or off, holding OK picks it up to move with ▲ ▼. */
@Composable
private fun RowsSettings(app: LauncherApp) {
    val config by app.store.config.collectAsState()
    val channels by app.channels.channels.collectAsState()
    var moving by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf(config.rows) }
    LaunchedEffect(config.rows, moving) { if (moving == null) order = config.rows }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    BackHandler(enabled = moving != null) {
        moving = null
        order = config.rows
    }
    // A channel's row shows once its app has it
    val shown = order.filter { r -> r.kind != null || channels.any { it.rowId == r.id } }
    SettingsPage(
        "SETTINGS", "Rows on Home",
        "Turn each row on or off. Hold OK on a row to move it up or down, then OK to drop it. Apps that share rows with the TV (SmartTube, Netflix…) show up here. Your phone can do this too.",
    ) {
        itemsIndexed(shown, key = { _, r -> r.id }) { i, r ->
            val isMoving = moving == r.id
            val kind = r.kind
            val channel = channels.firstOrNull { it.rowId == r.id }
            // While moving: ▲ ▼ shift the row past the next shown one, ◀ ▶ stay put
            val moveKeys: (KeyEvent) -> Boolean = { e ->
                val index = shown.indexOfFirst { it.id == r.id }
                when (e.key) {
                    Key.DirectionUp -> {
                        if (e.isDown() && index > 0) order = swap(order, r.id, shown[index - 1].id)
                        true
                    }
                    Key.DirectionDown -> {
                        if (e.isDown() && index in 0 until shown.size - 1) order = swap(order, r.id, shown[index + 1].id)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            SettingRow(
                icon = when (kind) {
                    RowKind.APPS -> Ic.home
                    RowKind.CONTINUE -> Ic.play
                    RowKind.MOVIES -> Ic.film
                    RowKind.SHOWS -> Ic.tv
                    RowKind.GAMES -> Ic.gamepad
                    null -> Ic.star
                },
                title = kind?.title ?: channel?.name ?: r.id,
                sub = if (isMoving) "Moving: ▲ ▼ to move it, OK to drop it, Back to put it back" else kind?.source ?: channel?.appName.orEmpty(),
                on = r.on,
                handle = true,
                lifted = isMoving,
                focusRequester = if (i == 0) first else null,
                onClick = {
                    if (moving != null) {
                        app.store.setRows(order)
                        moving = null
                    } else {
                        app.store.setRows(config.rows.map { if (it.id == r.id) it.copy(on = !it.on) else it })
                    }
                },
                onLongClick = { if (moving == null) moving = r.id },
                onKey = if (isMoving) moveKeys else null,
            )
        }
    }
}

/** [a] and [b] trade places in the rows. */
private fun swap(rows: List<RowSetting>, a: String, b: String): List<RowSetting> {
    val i = rows.indexOfFirst { it.id == a }
    val j = rows.indexOfFirst { it.id == b }
    if (i < 0 || j < 0) return rows
    return rows.toMutableList().apply {
        val t = this[i]
        this[i] = this[j]
        this[j] = t
    }
}

/** How to make Shield Launcher what Home opens: Android TV Home switched off, once, with ADB. */
@Composable
private fun HomeButtonHelp(app: LauncherApp) {
    val focus = remember { FocusRequester() }
    var isHome by remember { mutableStateOf(isHomeScreen(app)) }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
        while (true) {
            delay(2000)
            isHome = isHomeScreen(app)
        }
    }
    val ip = remember { EmbyLauncher.localIp() ?: "<shield-ip>" }
    SettingsPage(
        "SETTINGS · HOME BUTTON",
        if (isHome) "Home opens Shield Launcher" else "Make Home open Shield Launcher",
        "Android TV doesn't let you pick a home screen, so its own one is switched off once from a computer. Updates after that need nothing.",
    ) {
        val steps = listOf(
            "On the Shield: Settings › Device Preferences › About › Build, 7 times. Then Developer options › Network debugging on.",
            "On the computer, with Google's platform-tools: adb connect $ip  (say Always allow on the TV)",
            "adb shell appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow  (Home Assistant's notifications over other apps)",
            "adb shell pm grant ${app.packageName} com.android.providers.tv.permission.READ_EPG_DATA  (SmartTube's rows, if the TV didn't ask)",
            "adb shell pm disable-user --user 0 com.google.android.tvlauncher",
            "Press Home. To undo it: adb shell pm enable com.google.android.tvlauncher",
        )
        steps.forEachIndexed { i, s ->
            item {
                SettingRow(
                    if (i == steps.lastIndex) Ic.home else Ic.right, "Step ${i + 1}", s,
                    good = if (i == steps.lastIndex && isHome) true else null,
                    onClick = {}, focusRequester = if (i == 0) focus else null,
                )
            }
        }
        item {
            Text(
                if (isHome) "Done: Shield Launcher is the home screen." else "Android TV Home is still the home screen.",
                style = Tv.text(11f, color = if (isHome) Tv.okText else Tv.muted),
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
    }
}

/** Whether Home (the button) opens this launcher. */
fun isHomeScreen(app: LauncherApp): Boolean = runCatching {
    val r = app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    r?.activityInfo?.packageName == app.packageName
}.getOrDefault(false)

private fun allowOverlay(app: LauncherApp) {
    val tries = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")),
    )
    for (t in tries) if (runCatching { app.startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
}

private fun appName(app: LauncherApp, pkg: String) = runCatching {
    app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
