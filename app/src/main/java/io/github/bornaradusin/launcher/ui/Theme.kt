package io.github.bornaradusin.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.bornaradusin.launcher.R

/** The launcher's look: near-black, white text, one light blue. */
object Tv {
    val bg = Color(0xFF06080C)
    val panel = Color(0xFF111621)
    val line = Color(0xFF222A37)
    val ink = Color(0xFFF2F5F9)
    val ink2 = Color(0xFFD3DAE3)
    val muted = Color(0xFF9AA5B4)
    val faint = Color(0xFF5B6678)
    val accent = Color(0xFF7CC4FF)
    val ok = Color(0xFF6EDC96)
    val okText = Color(0xFF9FE6B8)
    val bad = Color(0xFFFF8A80)
    val off = Color(0xFF2A3342)

    val font = FontFamily(
        Font(R.font.outfit_light, FontWeight.Light),
        Font(R.font.outfit_regular, FontWeight.Normal),
        Font(R.font.outfit_medium, FontWeight.Medium),
        Font(R.font.outfit_semibold, FontWeight.SemiBold),
        Font(R.font.outfit_bold, FontWeight.Bold),
    )

    // Sizes are the design's pixels on a 1920×1080 screen, halved (the Shield draws 2 pixels a point)
    fun text(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = ink, lineHeight: Float = 0f, spacing: Float = 0f) =
        TextStyle(
            fontFamily = font,
            fontSize = size.sp,
            fontWeight = weight,
            color = color,
            lineHeight = if (lineHeight > 0) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            letterSpacing = spacing.sp,
        )

    val kicker = text(10f, FontWeight.SemiBold, accent, spacing = 1.5f)
    val heroTitle = text(38f, FontWeight.Bold, lineHeight = 40f, spacing = -0.5f)
    val meta = text(12f, color = ink2)
    val body = text(11f, color = muted, lineHeight = 16f)
    val hint = text(9f, color = muted)
    val rowTitle = text(14f, FontWeight.SemiBold, ink2)
    val rowHint = text(10f, color = muted)
    val cardTitle = text(12f, FontWeight.SemiBold)
    val cardSub = text(8.5f, color = ink2)
}
