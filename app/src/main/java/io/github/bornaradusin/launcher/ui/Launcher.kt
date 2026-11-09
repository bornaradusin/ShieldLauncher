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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    class Trailer(val request: TrailerRequest, val from: Tab) : Screen
}

/**
 * The launcher: Home and Requester (tabs at the top), with Settings and full-screen trailers over
 * them, and a line for short messages.
 */
@Composable
fun Launcher(app: LauncherApp, homePresses: Int, askChannels: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var tabSwitched by remember { mutableStateOf(false) }
    val home = remember { HomeMemory() }
    val requester = remember { HomeMemory() }
    var message by remember { mutableStateOf<String?>(null) }
    var messageId by remember { mutableIntStateOf(0) }
    val toast: (String) -> Unit = {
        message = it
        messageId++
    }
    val onTab: (Tab) -> Unit = { t ->
        if (t != tab) {
            tab = t
            tabSwitched = true
        }
    }

    // Home pressed anywhere in the launcher: back to Home
    LaunchedEffect(homePresses) {
        if (homePresses > 0) {
            screen = Screen.Main
            tab = Tab.HOME
            tabSwitched = false
        }
    }

    Box(Modifier.fillMaxSize().background(Tv.bg)) {
        when (val s = screen) {
            Screen.Main -> when (tab) {
                Tab.HOME -> HomeScreen(
                    app, home,
                    HomeActions(
                        openSettings = { tabSwitched = false; screen = Screen.Settings },
                        openTrailer = { item ->
                            tabSwitched = false
                            screen = Screen.Trailer(
                                TrailerRequest(
                                    title = item.seriesName ?: item.name,
                                    playLabel = if (item.isSeries) "Open the show" else "Play the film",
                                    onPlay = {
                                        screen = Screen.Main
                                        val account = app.store.config.value.emby
                                        if (account != null) app.scope.launch { app.embyLauncher.play(account, item, app.store.config.value.embyApp) }
                                    },
                                    youtubeLink = app.trailers.youtubeLink(item),
                                    load = { app.store.config.value.emby?.let { app.trailers.media(it, item) } },
                                ),
                                Tab.HOME,
                            )
                        },
                        toast = toast,
                    ),
                    homePresses,
                    onTab = onTab,
                    startOnTabs = tabSwitched,
                )
                Tab.REQUESTER -> RequesterScreen(
                    app, requester,
                    RequesterActions(
                        openSettings = { tabSwitched = false; screen = Screen.Settings },
                        openTrailer = { tabSwitched = false; screen = Screen.Trailer(it, Tab.REQUESTER) },
                        toast = toast,
                    ),
                    onTab = onTab,
                    startOnTabs = tabSwitched,
                )
            }
            Screen.Settings -> SettingsScreen(app, onClose = { screen = Screen.Main }, toast = toast, askChannels = askChannels)
            is Screen.Trailer -> TrailerScreen(
                app, s.request,
                onClose = { screen = Screen.Main },
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
