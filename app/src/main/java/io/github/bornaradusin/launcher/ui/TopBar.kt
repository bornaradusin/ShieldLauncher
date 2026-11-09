package io.github.bornaradusin.launcher.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.weather.WeatherNow
import java.util.Date

/** The two main screens. */
enum class Tab(val title: String) { HOME("Home"), REQUESTER("Requester") }

/**
 * The top of both main screens: the Home and Requester tabs (OK switches), then the update button,
 * the weather and time ([small]: hidden while Home's big clock shows them), search and settings.
 */
@Composable
fun TopBar(
    tab: Tab,
    onTab: (Tab) -> Unit,
    tabFocus: FocusRequester?,
    small: Boolean,
    weather: WeatherNow?,
    update: String?,
    onUpdate: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val now = rememberNow()
    val context = LocalContext.current
    val shown by animateFloatAsState(if (small) 1f else 0f, tween(250), label = "topbar clock")
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 22.dp).height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(15.dp)).background(Color(0x12FFFFFF)).padding(2.5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Tab.entries.forEach { t -> TabButton(t, selected = t == tab, onClick = { onTab(t) }, focusRequester = if (t == tab) tabFocus else null) }
        }
        Spacer(Modifier.weight(1f))
        if (update != null) PillButton("Update $update", onUpdate, icon = Ic.download, primary = true, height = 26.dp, textSize = 10f)
        if (weather != null) {
            Row(Modifier.alpha(shown), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(skyIcon(weather), null, Modifier.size(14.dp), tint = Tv.ink2)
                Text("${weather.temperature}°", style = Tv.text(12f, color = Tv.ink2))
            }
        }
        Text(DateFormat.getTimeFormat(context).format(Date(now)), style = Tv.text(15f, FontWeight.SemiBold, spacing = 0.25f), modifier = Modifier.alpha(shown))
        RoundButton(Ic.search, "Search", onSearch)
        RoundButton(Ic.cog, "Settings", onSettings)
    }
}

@Composable
private fun TabButton(t: Tab, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Tv.ink
        selected -> Color(0x29FFFFFF)
        else -> Color.Transparent
    }
    Text(
        t.title,
        style = Tv.text(11f, FontWeight.SemiBold, if (focused) Tv.bg else if (selected) Tv.ink else Tv.muted),
        modifier = Modifier.height(25.dp).clip(RoundedCornerShape(12.5.dp)).background(bg)
            .tvItem(onClick = onClick, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}
