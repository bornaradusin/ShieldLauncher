package io.github.bornaradusin.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.apps.AppEntry
import kotlinx.coroutines.delay

class MenuItem(val icon: ImageVector, val text: String, val onClick: () -> Unit)

/** The menu that opens on holding OK: on the right, over Home. */
@Composable
fun MenuPanel(title: String, items: List<MenuItem>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(30)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0x5906080C))) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 95.dp, end = 70.dp).width(210.dp)
                .shadow(40.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEB111621))
                .border(1.dp, Tv.line, RoundedCornerShape(12.dp))
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = Tv.text(9f, color = Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 5.dp, end = 10.dp))
            items.forEachIndexed { i, item -> MenuRow(item, if (i == 0) first else null) }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp))
            .background(if (focused) Tv.ink else Color.Transparent)
            .tvItem(onClick = item.onClick, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(item.icon, null, Modifier.size(12.dp), tint = if (focused) Tv.bg else Tv.ink2)
        Text(item.text, style = Tv.text(11f, color = if (focused) Tv.bg else Tv.ink))
    }
}

/** "Add an app": every app that isn't on the row yet. */
@Composable
fun AppPicker(apps: List<AppEntry>, onPick: (AppEntry) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isEmpty()) {
        delay(30)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xF206080C))) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 40.dp)) {
            Text("ADD AN APP", style = Tv.kicker)
            Spacer(Modifier.height(6.dp))
            Text("Pick an app for the top row", style = Tv.text(26f, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            Text("It goes at the end: hold OK on it after to move it. Back closes this.", style = Tv.body)
            Spacer(Modifier.height(16.dp))
            if (apps.isEmpty()) {
                Text("Every app is on the row already.", style = Tv.text(12f, color = Tv.muted))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(apps, key = { it.key }) { a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FocusCard(
                                108.dp, 61.dp, 10.dp,
                                onClick = { onPick(a) },
                                focusRequester = if (a == apps.first()) first else null,
                                grow = 1.1f,
                            ) {
                                if (a.banner != null) {
                                    Image(a.banner, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Box(
                                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0D1119)))),
                                        contentAlignment = Alignment.Center,
                                    ) { a.icon?.let { Image(it, null, Modifier.size(28.dp)) } }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(a.label, style = Tv.text(9.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(108.dp))
                        }
                    }
                }
            }
        }
    }
}
