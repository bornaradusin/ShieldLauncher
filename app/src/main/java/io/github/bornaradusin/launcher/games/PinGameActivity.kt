package io.github.bornaradusin.launcher.games

import android.app.Activity
import android.os.Bundle

/** Artemis' "Create shortcut" on a game: the game goes in the Games row. */
class PinGameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
