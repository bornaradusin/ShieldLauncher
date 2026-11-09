package io.github.bornaradusin.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.emby.EmbyItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Trailer(val item: EmbyItem) : Screen
}

/** The launcher: Home, with Settings and full-screen trailers over it, and a line for short messages. */
@Composable
fun Launcher(app: LauncherApp, homePresses: Int) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val memory = remember { HomeMemory() }
    var message by remember { mutableStateOf<String?>(null) }
    var messageId by remember { mutableIntStateOf(0) }
    val toast: (String) -> Unit = {
        message = it
        messageId++
    }

    // Home pressed anywhere in the launcher: back to Home
    LaunchedEffect(homePresses) { if (homePresses > 0) screen = Screen.Home }

    Box(Modifier.fillMaxSize().background(Tv.bg)) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                app, memory,
                HomeActions(
                    openSettings = { screen = Screen.Settings },
                    openTrailer = { screen = Screen.Trailer(it) },
                    toast = toast,
                ),
                homePresses,
            )
            Screen.Settings -> SettingsScreen(app, onClose = { screen = Screen.Home }, toast = toast)
            is Screen.Trailer -> TrailerScreen(
                app, s.item,
                onClose = { screen = Screen.Home },
                onPlayFilm = {
                    screen = Screen.Home
                    val account = app.store.config.value.emby
                    if (account != null) app.scope.launch { app.embyLauncher.play(account, s.item, app.store.config.value.embyApp) }
                },
                toast = toast,
            )
        }

        LaunchedEffect(messageId) {
            if (message == null) return@LaunchedEffect
            delay(4500)
            message = null
        }
        AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                message.orEmpty(),
                style = Tv.text(11f, color = Tv.ink2, lineHeight = 15f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 26.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF0111621)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
