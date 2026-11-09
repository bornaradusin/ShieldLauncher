package io.github.bornaradusin.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.bornaradusin.launcher.channels.Channels
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.ui.Launcher
import kotlinx.coroutines.flow.MutableStateFlow

/** The home screen. Home on the remote brings it back to the start; Back never leaves it. */
class MainActivity : ComponentActivity() {
    private val homePresses = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LauncherApp
        // Back with nothing open stays on Home (it's the home screen: there's nowhere to go back to)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val presses by homePresses.collectAsState()
            Launcher(app, presses, askChannels = ::askChannels)
        }
        // SmartTube's rows (and other apps' channels) need the TV listings permission: asked once
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (!app.channels.allowed() && !prefs.getBoolean("askedChannels", false)) {
            prefs.edit().putBoolean("askedChannels", true).apply()
            askChannels()
        }
    }

    /** Android's "Allow … to read TV listings?" */
    private fun askChannels() {
        runCatching { requestPermissions(arrayOf(Channels.PERMISSION), ASK_CHANNELS) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_CHANNELS) (application as LauncherApp).channels.refresh()
    }

    companion object {
        private const val ASK_CHANNELS = 7
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while the launcher is already in front
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.value++
    }

    override fun onResume() {
        super.onResume()
        val app = application as LauncherApp
        app.feed.resumed()
        app.channels.refresh()
        app.updater.resume()
        ListenService.start(this)
    }
}
