package io.github.bornaradusin.launcher.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.TvGroup
import io.github.bornaradusin.launcher.iptv.Pictures
import io.github.bornaradusin.launcher.iptv.Programme
import io.github.bornaradusin.launcher.iptv.TvRow
import io.github.bornaradusin.launcher.iptv.XChannel
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.abs

/** A channel's own colour (from its name), for its tile and its card when there's no logo. */
fun channelColour(name: String): Color {
    val colours = listOf(
        Color(0xFF2F6FD6), Color(0xFF1F9C6E), Color(0xFFC2453A), Color(0xFFB8901F), Color(0xFF7B4FD0),
        Color(0xFFB0336E), Color(0xFF237D8F), Color(0xFFC56A26), Color(0xFF4F7A2E), Color(0xFF5A63C9),
    )
    return colours[abs(name.lowercase().hashCode()) % colours.size]
}

/** "Channel One" → "CO", "Sport 1 HD" → "S1", "News 24" → "N24". */
fun channelInitials(name: String): String {
    val words = name.replace(Regex("(?i)\\b(hd|fhd|uhd|4k|sd|hevc|h265|\\|)\\b"), " ")
        .replace(Regex("^[A-Za-z]{2,3}\\s*[:|]\\s*"), "")
        .split(Regex("[\\s\\-_.:|]+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return name.take(2).uppercase()
    val first = words[0].first().uppercaseChar()
    val second = words.getOrNull(1)?.let { w -> if (w.all { it.isDigit() }) w.take(2) else w.first().uppercaseChar().toString() } ?: words[0].drop(1).take(1).uppercase()
    return "$first$second"
}

/** A channel's logo on a tile, or its initials on its colour when there's none (or it won't load). */
@Composable
fun ChannelLogo(c: XChannel, size: Dp, radius: Dp = size / 4.5f) {
    // From wherever the provider keeps it
    val logo = Pictures.allowed(c.logo)
    var failed by remember(logo) { mutableStateOf(false) }
    val colour = channelColour(c.name)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius))
            .background(
                if (logo != null && !failed) Brush.linearGradient(listOf(Color(0xFF232C3B), Color(0xFF151B26)))
                else Brush.linearGradient(listOf(colour, colour.copy(red = colour.red * 0.55f, green = colour.green * 0.55f, blue = colour.blue * 0.55f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(
                logo, c.name, Modifier.fillMaxSize().padding(size / 9f), contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        } else {
            Text(channelInitials(c.name), style = Tv.text(size.value * 0.36f, FontWeight.Bold), maxLines = 1)
        }
    }
}

fun hm(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

/** "19 min left", "1 h 5 min left". */
fun left(p: Programme, now: Long): String {
    val m = ((p.end - now) / 60_000L).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} h ${m % 60} min left" else "$m min left"
}

fun minutes(seconds: Int): String = when {
    seconds <= 0 -> ""
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    else -> "${(seconds + 30) / 60} min"
}

/** A thin bar, [fraction] filled. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 2.dp, colour: Color = Tv.accent, track: Color = Color(0x33FFFFFF)) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(colour))
    }
}

fun Programme.fraction(now: Long): Float = if (end <= start) 0f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)

/** A channel on the TV tab: its logo, name and number, and what's on now. */
@Composable
fun ChannelCard(
    app: LauncherApp,
    c: XChannel,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    focusRequester: FocusRequester?,
    moving: Boolean = false,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Its guide is asked for while it's on screen
    LaunchedEffect(c.id) { app.iptv.want(c.id) }
    val version by app.iptv.guideVersion.collectAsState()
    val on = remember(c.id, version, now) { app.iptv.nowNext(c.id, now).first }
    val known = remember(c.id, version) { app.iptv.guide(c.id) != null }
    val colour = channelColour(c.name)
    FocusCard(
        165.dp, 92.dp, 10.dp, onClick = onClick, onLongClick = onLongClick, onFocus = onFocus,
        focusRequester = focusRequester, onKey = onKey, ring = if (moving) Tv.accent else Tv.ink, forceRing = moving,
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF1A2130), Color(0xFF10141D))))
                .background(Brush.radialGradient(listOf(colour.copy(alpha = 0.28f), Color.Transparent), radius = 260f)),
        ) {
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ChannelLogo(c, 24.dp)
                Text(c.name, style = Tv.text(10.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.num > 0) Text("${c.num}", style = Tv.text(8.5f, color = Tv.muted))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    on?.title?.ifBlank { null } ?: if (known) "No guide for now" else "",
                    style = Tv.text(10.5f, FontWeight.SemiBold, if (on != null) Tv.ink else Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (on != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${hm(context, on.start)}–${hm(context, on.end)}", style = Tv.text(8f, color = Tv.ink2))
                        Progress(on.fraction(now), Modifier.weight(1f))
                    }
                } else Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** One of the TV tab's parts at the top (Live TV, Guide, Films…). */
@Composable
fun SubnavPill(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Tv.ink
        selected -> Color(0x29FFFFFF)
        else -> Color(0x12FFFFFF)
    }
    val fg = if (focused) Tv.bg else if (selected) Tv.ink else Tv.ink2
    Row(
        Modifier.height(23.dp).clip(RoundedCornerShape(11.5.dp)).background(bg)
            .tvItem(onClick = onClick, focusRequester = focusRequester, onFocusChange = {
                focused = it
                onFocus?.invoke(it)
            })
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(11.dp), tint = fg)
        Text(text, style = Tv.text(10f, FontWeight.SemiBold, fg), maxLines = 1)
    }
}

/**
 * Typing on the TV: a box with the keyboard up. The keyboard's Done (or Search) key gives the text back;
 * Back gives up.
 */
@Composable
fun TextEntry(kicker: String, initial: String, hint: String, password: Boolean = false, search: Boolean = false, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    Box(Modifier.fillMaxSize().background(Color(0xE604060A))) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 70.dp, start = 48.dp, end = 48.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kicker.uppercase(), style = Tv.kicker)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF))
                    .border(1.5.dp, Tv.ink, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text(if (search) "Type, or use the keyboard's mic" else "Type it with the keyboard", style = Tv.text(15f, color = Tv.muted))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = Tv.text(15f, FontWeight.Medium),
                    cursorBrush = SolidColor(Tv.accent),
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (search) ImeAction.Search else ImeAction.Done,
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboard?.hide(); onDone(text) },
                        onSearch = { keyboard?.hide(); onDone(text) },
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                        .onPreviewKeyEvent { e ->
                            if (e.key == Key.Back && !e.isDown()) {
                                onDone(null)
                                true
                            } else if (e.isOk()) {
                                // OK on the box itself (the keyboard hidden): done, or the keyboard when it's empty
                                if (!e.isDown()) {
                                    if (text.isBlank()) keyboard?.show() else {
                                        keyboard?.hide()
                                        onDone(text)
                                    }
                                }
                                true
                            } else false
                        },
                )
            }
            Text(hint, style = Tv.hint)
        }
    }
}

/** A line in a panel: a tick box, or an action. */
@Composable
private fun PanelLine(
    text: String,
    sub: String? = null,
    checked: Boolean? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
            .border(if (focused) 1.5.dp else 0.dp, if (focused) Tv.ink else Color.Transparent, RoundedCornerShape(10.dp))
            .tvItem(onClick = onClick, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (checked != null) {
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (checked) Tv.accent else Color.Transparent)
                    .border(1.5.dp, if (checked) Tv.accent else Tv.muted, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Ic.check, null, Modifier.size(10.dp), tint = Tv.bg) }
        }
        icon?.let { Icon(it, null, Modifier.size(12.dp), tint = Tv.ink2) }
        Column(Modifier.weight(1f)) {
            Text(text, style = Tv.text(11.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = Tv.text(8.5f, color = Tv.muted), maxLines = 1)
        }
    }
}

/** A panel in the middle of the screen, over a darkened page. */
@Composable
fun CentrePanel(width: Dp = 320.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x9906080C)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(width).shadow(40.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2111621)).border(1.dp, Tv.line, RoundedCornerShape(16.dp)).padding(14.dp),
            content = content,
        )
    }
}

/**
 * Holding OK on a channel: which of your groups it's in (OK ticks or unticks), a new group with it, and
 * moving it along the group it was picked in.
 */
@Composable
fun AddToGroupPanel(
    app: LauncherApp,
    c: XChannel,
    from: TvRow?,
    onNewGroup: () -> Unit,
    onMove: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val config by app.store.config.collectAsState()
    val groups: List<TvGroup> = config.iptv?.groups.orEmpty().filter { it.category == null }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val catName = app.iptv.live.value.categoryNames[c.category].orEmpty()
    CentrePanel {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                ChannelLogo(c, 28.dp)
                Column {
                    Text(c.name, style = Tv.text(14f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(c.num.takeIf { it > 0 }?.toString(), catName.ifBlank { null }).joinToString(" · "), style = Tv.text(8.5f, color = Tv.muted), maxLines = 1)
                }
            }
            Text("IN YOUR GROUPS", style = Tv.text(8f, FontWeight.Bold, Tv.muted, spacing = 1.2f), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
                    val inside = c.id in g.channels
                    PanelLine(
                        g.name, sub = if (!g.on) "Hidden on the TV tab" else null, checked = inside,
                        onClick = {
                            app.iptv.setInGroup(g.id, c.id, !inside)
                        },
                        focusRequester = if (i == 0) first else null,
                    )
                }
            }
            PanelLine("New group with this channel", icon = Ic.plus, onClick = onNewGroup, focusRequester = if (groups.isEmpty()) first else null)
            if (onMove != null && from?.group != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Tv.line))
                PanelLine("Move it in ${from.title}", sub = "Earlier or later in the row", icon = Ic.drag, onClick = onMove)
            }
        }
    }
}

/** Jump to a group: every row of the TV tab, OK goes to it. */
@Composable
fun GroupPicker(rows: List<TvRow>, current: String?, onPick: (TvRow) -> Unit, onClose: () -> Unit) {
    val list = rememberLazyListState()
    val start = rows.indexOfFirst { it.id == current }.coerceAtLeast(0)
    val focus = remember { HashMap<Int, FocusRequester>() }
    LaunchedEffect(Unit) {
        list.scrollToItem((start - 3).coerceAtLeast(0))
        delay(60)
        runCatching { focus[start]?.requestFocus() }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0x9906080C))) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 40.dp).width(300.dp).fillMaxHeight().padding(vertical = 30.dp)
                .shadow(40.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2111621)).border(1.dp, Tv.line, RoundedCornerShape(16.dp)).padding(12.dp),
        ) {
            Text("JUMP TO A GROUP", style = Tv.kicker, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                    PanelLine(
                        r.title, sub = "${r.channels.size} channel${if (r.channels.size == 1) "" else "s"}" + if (r.group != null) " · yours" else "",
                        icon = if (r.group != null) Ic.star else Ic.list,
                        onClick = { onPick(r) },
                        focusRequester = focus.getOrPut(i) { FocusRequester() },
                    )
                }
            }
        }
    }
}

/** A menu of a few choices, in the middle of the screen. */
@Composable
fun ChoicePanel(title: String, choices: List<MenuItem>, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    CentrePanel(width = 280.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Tv.text(10f, color = Tv.muted), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            choices.forEachIndexed { i, m -> PanelLine(m.text, icon = m.icon, onClick = m.onClick, focusRequester = if (i == 0) first else null) }
        }
    }
}
