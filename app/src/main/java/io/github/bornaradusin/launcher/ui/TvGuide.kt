package io.github.bornaradusin.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.iptv.Programme
import io.github.bornaradusin.launcher.iptv.TvRow
import io.github.bornaradusin.launcher.iptv.XChannel

/** A channel's line in the guide, and the group it's in ([first]: its group's name goes above it). */
private class GuideLine(val row: TvRow, val c: XChannel, val first: Boolean)

private const val SPAN_MIN = 180
private val GRID_W = 699.dp
private val PER_MIN = 699f / SPAN_MIN
private const val HALF_HOUR = 30 * 60_000L

private fun floorHalf(t: Long) = t - t % HALF_HOUR

/**
 * The guide: your groups' channels down the left, three hours across. ▲ ▼ channel, ◀ ▶ programme (up to
 * the day ahead), OK watches the channel, holding OK puts it in a group. Each channel's guide is asked for
 * as its line comes on screen.
 */
@Composable
fun TvGuide(
    app: LauncherApp,
    rows: List<TvRow>,
    memory: TvMemory,
    now: Long,
    onWatch: (TvRow, XChannel) -> Unit,
    onHold: (XChannel, TvRow) -> Unit,
    onUp: () -> Unit,
) {
    val context = LocalContext.current
    val version by app.iptv.guideVersion.collectAsState()
    val lines = remember(rows) {
        rows.flatMap { r -> r.channels.mapIndexed { i, c -> GuideLine(r, c, i == 0) } }
    }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var line by remember { mutableIntStateOf(memory.guideRow.coerceIn(0, (lines.size - 1).coerceAtLeast(0))) }
    // The time the focus is at: the focused programme's start (or now)
    var at by remember { mutableLongStateOf(memory.guideAt.coerceAtLeast(now)) }
    var start by remember { mutableLongStateOf(floorHalf(now)) }
    LaunchedEffect(now) { if (start < floorHalf(now)) start = floorHalf(now) }

    fun programmes(c: XChannel): List<Programme> = app.iptv.guide(c.id).orEmpty().filter { it.end > now }

    /** The programme focused on [c]'s line: the one on at [time], else the first after. */
    fun pick(c: XChannel, time: Long): Programme? {
        val ps = programmes(c)
        return ps.firstOrNull { it.start <= time && it.end > time } ?: ps.firstOrNull { it.start > time } ?: ps.lastOrNull()
    }

    fun show(p: Programme?) {
        if (p == null) return
        val firstMin = floorHalf(now)
        if (p.start < start) start = maxOf(firstMin, floorHalf(p.start))
        else if (p.start > start + (SPAN_MIN - 20) * 60_000L) start = maxOf(firstMin, floorHalf(p.start) - 2 * HALF_HOUR)
    }

    LaunchedEffect(line) {
        memory.guideRow = line
        val seen = list.layoutInfo.visibleItemsInfo
        val top = seen.firstOrNull()?.index ?: 0
        val bottom = seen.lastOrNull()?.index ?: 0
        if (line <= top || line >= bottom - 1) list.animateScrollToItem((line - 3).coerceAtLeast(0))
    }
    LaunchedEffect(at) { memory.guideAt = at }
    // Groups picked one: there
    LaunchedEffect(memory.guideJump) {
        if (memory.guideJump == 0) return@LaunchedEffect
        line = memory.guideRow.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        at = now
        start = floorHalf(now)
        list.scrollToItem((line - 3).coerceAtLeast(0))
        kotlinx.coroutines.delay(60)
        runCatching { focus.requestFocus() }
    }

    val current = lines.getOrNull(line)
    val focusedProgramme = remember(line, at, version, now, lines) { current?.let { pick(it.c, at) } }

    // The top: the focused programme
    Column(Modifier.padding(start = 48.dp, top = 70.dp, end = 48.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val c = current?.c
        if (c == null) {
            Text("GUIDE · YOUR IPTV", style = Tv.kicker)
            Text("Guide", style = Tv.text(30f, FontWeight.Bold))
            Text("No channels yet.", style = Tv.body)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelLogo(c, 20.dp)
                val p = focusedProgramme
                Text(
                    if (p != null) "${c.name} · ${hm(context, p.start)} – ${hm(context, p.end)}" else c.name,
                    style = Tv.text(11f, color = Tv.ink2), maxLines = 1,
                )
            }
            Text(focusedProgramme?.title?.ifBlank { null } ?: c.name, style = Tv.text(30f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                focusedProgramme?.description?.ifBlank { null }
                    ?: if (app.iptv.guide(c.id) == null) "Looking for what's on…" else if (focusedProgramme == null) "No guide for this channel: OK to watch it" else "",
                style = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (lines.isEmpty()) return

    // The times across the top, and the line at now
    Box(Modifier.padding(start = 210.dp, top = 216.dp).width(GRID_W).height(14.dp)) {
        for (k in 0 until SPAN_MIN / 30) {
            val t = start + k * HALF_HOUR
            Text(hm(context, t), style = Tv.text(9.5f, color = Tv.muted), modifier = Modifier.offset(x = (k * 30 * PER_MIN).dp))
        }
    }
    val nowX = ((now - start) / 60_000f) * PER_MIN
    if (nowX in 0f..GRID_W.value) {
        Box(Modifier.padding(start = 210.dp + nowX.dp, top = 232.dp).width(1.5.dp).fillMaxHeight().background(LIVE_RED.copy(alpha = 0.85f)))
        Text(
            hm(context, now), style = Tv.text(8f, FontWeight.Bold),
            modifier = Modifier.padding(start = (210f + nowX - 14f).coerceAtLeast(210f).dp, top = 214.dp)
                .clip(RoundedCornerShape(5.dp)).background(LIVE_RED).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }

    LazyColumn(
        state = list,
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 234.dp).fillMaxSize()
            .tvItem(
                onClick = { current?.let { onWatch(it.row, it.c) } },
                onLongClick = { current?.let { onHold(it.c, it.row) } },
                focusRequester = focus,
                onFocusChange = { focused = it },
                onKey = { e ->
                    if (!e.isDown()) e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                    else when (e.key) {
                        Key.DirectionUp -> {
                            if (line == 0) {
                                onUp()
                            } else {
                                line--
                                show(pick(lines[line].c, maxOf(at, now)))
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (line < lines.lastIndex) {
                                line++
                                show(pick(lines[line].c, maxOf(at, now)))
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            val c = current?.c
                            val ps = if (c != null) programmes(c) else emptyList()
                            val p = focusedProgramme
                            val next = if (p == null) null else ps.firstOrNull { it.start >= p.end }
                            // Up to about a day ahead
                            if (next != null && next.start < now + 24 * 3_600_000L) {
                                at = next.start
                                show(next)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            val c = current?.c
                            val ps = if (c != null) programmes(c) else emptyList()
                            val p = focusedProgramme
                            val prev = if (p == null) null else ps.lastOrNull { it.end <= p.start }
                            if (prev != null) {
                                at = maxOf(prev.start, now)
                                show(prev)
                            } else {
                                at = now
                                start = floorHalf(now)
                            }
                            true
                        }
                        else -> false
                    }
                },
            ),
    ) {
        itemsIndexed(lines, key = { _, l -> "${l.row.id}|${l.c.id}" }) { i, l ->
            LaunchedEffect(l.c.id) { app.iptv.want(l.c.id) }
            Column {
                if (l.first) {
                    Text(
                        l.row.title.uppercase(), style = Tv.text(8f, FontWeight.Bold, Tv.muted, spacing = 1.2f), maxLines = 1,
                        modifier = Modifier.padding(start = 48.dp, top = if (i == 0) 0.dp else 6.dp, bottom = 3.dp),
                    )
                }
                GuideRow(l.c, programmes(l.c), app.iptv.guide(l.c.id) != null, start, now, if (i == line && focused) focusedProgramme else null, i == line && focused, version)
            }
        }
    }
}

@Composable
private fun GuideRow(c: XChannel, ps: List<Programme>, known: Boolean, start: Long, now: Long, focusedP: Programme?, lit: Boolean, version: Int) {
    val end = start + SPAN_MIN * 60_000L
    Row(Modifier.fillMaxWidth().height(29.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.padding(start = 48.dp).width(162.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (c.num > 0) "${c.num}" else "", style = Tv.text(8.5f, color = Tv.muted), modifier = Modifier.width(22.dp), maxLines = 1)
            ChannelLogo(c, 20.dp)
            Text(c.name, style = Tv.text(10f, FontWeight.SemiBold, if (lit) Tv.ink else Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
        }
        Box(Modifier.width(GRID_W).fillMaxHeight()) {
            val shown = ps.filter { it.end > start && it.start < end }
            if (shown.isEmpty()) {
                // No guide: the whole line is one block
                Block(if (known) "No guide · OK to watch" else "…", 0f, GRID_W.value, lit, onNow = true, cutStart = false)
            } else {
                shown.forEach { p ->
                    val x = ((maxOf(p.start, start) - start) / 60_000f) * PER_MIN
                    val x2 = ((minOf(p.end, end) - start) / 60_000f) * PER_MIN
                    Block(
                        p.title.ifBlank { "Untitled" }, x, (x2 - x - 3f).coerceAtLeast(4f), lit && focusedP?.start == p.start,
                        onNow = p.start <= now && p.end > now, cutStart = p.start < start,
                    )
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, x: Float, w: Float, lit: Boolean, onNow: Boolean, cutStart: Boolean) {
    Box(
        Modifier.offset(x = x.dp).width(w.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
            .background(if (lit) Tv.ink else if (onNow) Color(0x26FFFFFF) else Color(0x14FFFFFF))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (w > 18f) {
            Text(
                (if (cutStart) "‹ " else "") + title,
                style = Tv.text(9.5f, if (onNow || lit) FontWeight.SemiBold else FontWeight.Normal, if (lit) Tv.bg else Tv.ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
