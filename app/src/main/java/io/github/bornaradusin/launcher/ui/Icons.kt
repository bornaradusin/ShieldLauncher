package io.github.bornaradusin.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons on a 24-unit grid, drawn with a 2-unit stroke and tinted where they're used. */
object Ic {
    private fun icon(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    val search = icon("search", circle(11f, 11f, 7f), "M20 20l-4-4")
    val cog = icon("cog", circle(12f, 12f, 3f), "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M4.9 19.1l2.1-2.1", "M17 7l2.1-2.1")
    val play = icon("play", "M8 5l11 7-11 7z")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val left = icon("left", "M15 18l-6-6 6-6")
    val right = icon("right", "M9 18l6-6-6-6")
    val up = icon("up", "M18 15l-6-6-6 6")
    val down = icon("down", "M6 9l6 6 6-6")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val grid = icon("grid", "M4 4h7v7H4z", "M13 4h7v7h-7z", "M4 13h7v7H4z", "M13 13h7v7h-7z")
    val home = icon("home", "M3 11l9-7 9 7", "M5 10v10h14V10")
    val film = icon("film", "M4 4h16v16H4z", "M8 4v16", "M16 4v16", "M4 9h4", "M4 15h4", "M16 9h4", "M16 15h4")
    val tv = icon("tv", "M3 6h18v12H3z", "M8 21h8", "M12 18v3")
    val star = icon("star", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z")
    val drag = icon("drag", "M9 6h.01", "M15 6h.01", "M9 12h.01", "M15 12h.01", "M9 18h.01", "M15 18h.01", width = 3f)
    val mute = icon("mute", "M4 9h4l5-4v14l-5-4H4z", "M17 9l5 6", "M22 9l-5 6")
    val sound = icon("sound", "M4 9h4l5-4v14l-5-4H4z", "M16.5 8.5a5 5 0 0 1 0 7", "M19.5 5.5a9 9 0 0 1 0 13")
    val phone = icon("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    val download = icon("download", "M12 3v12", "M7 10l5 5 5-5", "M4 20h16")
    val check = icon("check", "M5 12l5 5 9-10")
    val box = icon("box", "M3 7l9-4 9 4-9 4z", "M3 7v10l9 4 9-4V7", "M12 11v10")
    val trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val back = icon("back", "M9 14L4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-3")
    val info = icon("info", circle(12f, 12f, 9f), "M12 11v6", "M12 7.5h.01")
    val restart = icon("restart", "M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")
    val eye = icon("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val list = icon("list", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val bell = icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val gamepad = icon("gamepad", "M6 11h4", "M8 9v4", "M15 12h.01", "M18 10h.01",
        "M17.3 5H6.7a4 4 0 0 0-3.96 3.43L2 14.6A2.6 2.6 0 0 0 4.6 17.6c.7 0 1.4-.3 1.9-.8L8.3 15h7.4l1.8 1.8c.5.5 1.2.8 1.9.8a2.6 2.6 0 0 0 2.6-3L21.26 8.43A4 4 0 0 0 17.3 5z")
    val youtube = icon("youtube", "M2.5 17a24 24 0 0 1 0-10 2 2 0 0 1 1.4-1.4 49.6 49.6 0 0 1 16.2 0A2 2 0 0 1 21.5 7a24 24 0 0 1 0 10 2 2 0 0 1-1.4 1.4 49.6 49.6 0 0 1-16.2 0A2 2 0 0 1 2.5 17", "M10 15l5-3-5-3z")

    val clock = icon("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val warn = icon("warn", "M12 3l10 18H2z", "M12 10v5", "M12 18h.01")
    val chat = icon("chat", "M4 5h16v11H9l-5 4z")
    val send = icon("send", "M4 12l16-8-6 16-3-6z", "M11 14l9-10")
    val pin = icon("pin", "M9 3h6l-1 6 4 3v2H6v-2l4-3z", "M12 14v7")
    val hash = icon("hash", "M5 9h15", "M4 15h15", "M10 3L8 21", "M16 3l-2 18")
    val mic = icon("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")

    val sun = icon("sun", circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2",
        "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4")
    val moon = icon("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val cloud = icon("cloud", "M7 18h10a4 4 0 0 0 .5-7.97A6 6 0 0 0 6.1 11 3.5 3.5 0 0 0 7 18z")
    val partly = icon("partly", circle(8f, 8f, 2.5f), "M8 2.5v1", "M2.5 8h1", "M4.1 4.1l.7.7", "M11.9 4.1l-.7.7",
        "M9 20h8a3.5 3.5 0 0 0 .4-6.98A5 5 0 0 0 8.2 13.5 3 3 0 0 0 9 20z")
    val fog = icon("fog", "M7 11h10a4 4 0 0 0 .5-7.97A6 6 0 0 0 6.1 4 3.5 3.5 0 0 0 7 11z", "M4 15h16", "M6 19h12")
    val rain = icon("rain", "M7 14h10a4 4 0 0 0 .5-7.97A6 6 0 0 0 6.1 7 3.5 3.5 0 0 0 7 14z", "M8 17l-1 3", "M12 17l-1 3", "M16 17l-1 3")
    val snowfall = icon("snowfall", "M7 14h10a4 4 0 0 0 .5-7.97A6 6 0 0 0 6.1 7 3.5 3.5 0 0 0 7 14z", "M8 18v.5", "M12 20v.5", "M16 18v.5", "M10 21.5v.5", "M14 21.5v.5")
    val thunder = icon("thunder", "M7 14h10a4 4 0 0 0 .5-7.97A6 6 0 0 0 6.1 7 3.5 3.5 0 0 0 7 14z", "M12.5 14l-2 4h3l-2 4")
}
