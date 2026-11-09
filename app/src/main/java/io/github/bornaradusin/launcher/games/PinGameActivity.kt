package io.github.bornaradusin.launcher.games

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import io.github.bornaradusin.launcher.LauncherApp

/**
 * Artemis' "Create shortcut" on a game: Android asks the home screen to keep it, and this says yes
 * and puts the game in the Games row.
 */
class PinGameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        val info = request?.shortcutInfo
        if (request != null && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && info != null && request.isValid) {
            if (runCatching { request.accept() }.getOrDefault(false)) {
                LauncherApp.instance.games.add(info)
                Toast.makeText(this, "${info.shortLabel} is in Games on Home", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
