package io.github.bornaradusin.launcher.games

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import io.github.bornaradusin.launcher.Game
import io.github.bornaradusin.launcher.Store
import java.io.File

/**
 * Games from Artemis: hold a game in Artemis and pick "Create shortcut", and Android hands the
 * shortcut to the home screen, which keeps it here. Starting one opens Artemis straight into the
 * game on the PC. (Android only lets the Shield's home screen start these.)
 */
class Games(private val context: Context, private val store: Store) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val dir = File(context.filesDir, "games").apply { mkdirs() }

    /** Keeps a pinned shortcut; its picture (the box art) is saved with it. */
    fun add(info: ShortcutInfo) {
        val art = File(dir, "${info.`package`}_${info.id.hashCode()}.png")
        runCatching {
            val d = launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
            if (d != null) {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 400
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 600
                val scale = 600f / maxOf(w, h)
                val b = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, b.width, b.height)
                d.draw(Canvas(b))
                art.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val game = Game(
            pkg = info.`package`,
            shortcutId = info.id,
            name = info.shortLabel?.toString() ?: info.id,
            longName = info.longLabel?.toString().orEmpty(),
            art = if (art.exists()) art.absolutePath else "",
        )
        store.change { c -> c.copy(games = c.games.filterNot { it.pkg == game.pkg && it.shortcutId == game.shortcutId } + game) }
    }

    /** Opens Artemis into the game; false if Android won't (the launcher isn't the home screen). */
    fun start(game: Game): Boolean = runCatching {
        launcherApps.startShortcut(game.pkg, game.shortcutId, null, null, Process.myUserHandle())
        true
    }.getOrDefault(false)

    fun remove(game: Game) {
        store.change { c -> c.copy(games = c.games.filterNot { it.pkg == game.pkg && it.shortcutId == game.shortcutId }) }
        if (game.art.isNotBlank()) File(game.art).delete()
        // Let go of it in Android too
        runCatching {
            val keep = store.config.value.games.filter { it.pkg == game.pkg }.map { it.shortcutId }
            launcherApps.pinShortcuts(game.pkg, keep, Process.myUserHandle())
        }
    }

    /** The app the games come from, by name (for "From Artemis"). */
    fun sourceName(): String {
        val pkg = store.config.value.games.firstOrNull()?.pkg ?: return "Artemis"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("Artemis")
    }
}
