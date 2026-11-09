package io.github.bornaradusin.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.weather.DayForecast
import io.github.bornaradusin.launcher.weather.Sky
import io.github.bornaradusin.launcher.weather.Warning
import io.github.bornaradusin.launcher.weather.WeatherNow
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Behind the apps row: the focused app's colours, blown up and softly breathing, with the weather
 * outside falling (or twinkling, or drifting) over the whole screen.
 */
@Composable
fun AppsBackdrop(appColor: Color, weather: WeatherNow?, modifier: Modifier = Modifier) {
    val breathe = rememberInfiniteTransition(label = "breathe")
    val a by breathe.animateFloat(0.75f, 1f, infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by breathe.animateFloat(1f, 0.7f, infiniteRepeatable(tween(5800, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Tv.bg)
        // The app's colour: one big glow behind the clock, a softer one towards the middle
        drawCircle(
            Brush.radialGradient(listOf(appColor.copy(alpha = 0.50f * a), Color.Transparent), center = Offset(w * 0.24f, h * 0.22f), radius = w * 0.42f),
            radius = w * 0.42f, center = Offset(w * 0.24f, h * 0.22f),
        )
        drawCircle(
            Brush.radialGradient(listOf(appColor.copy(alpha = 0.26f * b), Color.Transparent), center = Offset(w * 0.56f, h * 0.38f), radius = w * 0.34f),
            radius = w * 0.34f, center = Offset(w * 0.56f, h * 0.38f),
        )
        // Fading into the page under the rows
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.72f to Tv.bg.copy(alpha = 0.88f), 1f to Tv.bg))
        weather?.let { sky(it, time) }
    }
}

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483647f
}

private fun DrawScope.sky(w: WeatherNow, t: Float) {
    when (w.sky) {
        Sky.RAIN -> rain(t, 90, 1f)
        Sky.THUNDER -> {
            rain(t, 110, 1.2f)
            // Now and then the sky lights up
            val c = t % 9.5f
            val flash = when {
                c < 0.08f -> 0.22f
                c in 0.16f..0.24f -> 0.12f
                else -> 0f
            }
            if (flash > 0f) drawRect(Color.White.copy(alpha = flash))
        }
        Sky.SNOW -> snow(t, 120)
        Sky.CLEAR -> if (w.day) sun(t) else stars(t)
        Sky.PARTLY -> {
            if (w.day) sun(t) else stars(t)
            clouds(t, 3, 0.10f)
        }
        Sky.CLOUDY -> clouds(t, 5, 0.14f)
        Sky.FOG -> fog(t)
    }
}

private fun DrawScope.rain(t: Float, count: Int, speed: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until count) {
        val v = (h * (1.3f + rnd(i, 1) * 0.7f)) * speed
        val len = h * (0.05f + rnd(i, 2) * 0.04f)
        val y = ((t * v + rnd(i, 3) * (h + len)) % (h + len)) - len
        val x = rnd(i, 4) * w * 1.15f - y * 0.17f
        val alpha = 0.25f + rnd(i, 5) * 0.45f
        drawLine(
            Brush.verticalGradient(listOf(Color(0x00BED8FF), Color(0xFFBED8FF).copy(alpha = alpha)), startY = y, endY = y + len),
            Offset(x, y), Offset(x - len * 0.17f, y + len), strokeWidth = 1.6f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.snow(t: Float, count: Int) {
    val w = size.width
    val h = size.height
    for (i in 0 until count) {
        val r = 1.5f + rnd(i, 1) * 3.5f
        val v = h * (0.05f + rnd(i, 2) * 0.08f) * (r / 3f)
        val y = ((t * v + rnd(i, 3) * (h + 20f)) % (h + 20f)) - 10f
        val x = rnd(i, 4) * w + sin(t * (0.4f + rnd(i, 5) * 0.6f) + rnd(i, 6) * 6.28f) * 22f
        drawCircle(Color.White.copy(alpha = 0.35f + rnd(i, 7) * 0.5f), radius = r, center = Offset(x, y))
    }
}

private fun DrawScope.stars(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 80) {
        val tw = 0.5f + 0.5f * sin(t * (0.6f + rnd(i, 1) * 1.4f) + rnd(i, 2) * 6.28f)
        drawCircle(Color.White.copy(alpha = 0.12f + 0.7f * tw), radius = 0.8f + rnd(i, 3) * 1.4f, center = Offset(rnd(i, 4) * w, rnd(i, 5) * h * 0.62f))
    }
    // Now and then, a shooting star
    val c = t % 14f
    if (c < 0.9f) {
        val p = c / 0.9f
        val start = Offset(w * 0.78f, h * 0.08f)
        val head = Offset(start.x - p * w * 0.3f, start.y + p * h * 0.22f)
        val tail = Offset(head.x + w * 0.08f, head.y - h * 0.058f)
        drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * (1 - p)), Color.Transparent), head, tail), head, tail, strokeWidth = 1.6f)
    }
}

private fun DrawScope.sun(t: Float) {
    val w = size.width
    val pulse = 0.85f + 0.15f * sin(t * 2 * PI.toFloat() / 7f)
    val c = Offset(w * 0.86f, -size.height * 0.05f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC870).copy(alpha = 0.22f * pulse), Color.Transparent), center = c, radius = w * 0.35f), radius = w * 0.35f, center = c)
}

private fun DrawScope.clouds(t: Float, count: Int, alpha: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until count) {
        val cw = w * (0.3f + rnd(i, 1) * 0.25f)
        val ch = h * (0.10f + rnd(i, 2) * 0.08f)
        val v = w * (0.006f + rnd(i, 3) * 0.008f)
        val x = ((t * v + rnd(i, 4) * (w + cw)) % (w + cw)) - cw
        val y = h * (0.05f + rnd(i, 5) * 0.5f)
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFB4BED7).copy(alpha = alpha), Color.Transparent), center = Offset(x + cw / 2, y + ch / 2), radius = cw / 2),
            topLeft = Offset(x, y), size = Size(cw, ch),
        )
    }
}

private fun DrawScope.fog(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 4) {
        val y = h * (0.15f + i * 0.17f)
        val shift = sin(t * 0.08f + i) * w * 0.08f
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFC8D2E1).copy(alpha = 0.10f), Color.Transparent), startX = shift - w * 0.2f, endX = w * 1.2f + shift),
            topLeft = Offset(0f, y), size = Size(w, h * 0.09f),
        )
    }
}

/** The weather beside the clock: now, then the next 5 days, with a warning above them when there is one. */
@Composable
fun WeatherStrip(w: WeatherNow, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Color(0x2E0C101A))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(16.dp)),
    ) {
        w.warnings.firstOrNull()?.let { WarningBanner(it, w.warnings.size) }
        Row(Modifier.fillMaxWidth().height(108.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(176.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (w.place.isNotBlank()) Text(w.place, style = Tv.text(10.5f, FontWeight.Medium, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(skyIcon(w), null, Modifier.size(30.dp), tint = skyTint(w.sky, w.day))
                    Text("${w.temperature}°", style = Tv.text(34f, FontWeight.Light, lineHeight = 36f))
                }
                Text("${words(w.sky, w.day)} · feels ${w.feels}°", style = Tv.text(10.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 16.dp).background(Color(0x1AFFFFFF)))
            w.days.drop(1).take(5).forEach { d -> Day(d, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Day(d: DayForecast, modifier: Modifier) {
    val name = runCatching { SimpleDateFormat("EEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date)!!) }.getOrDefault("")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = Tv.text(11f, FontWeight.Medium, color = Tv.ink2))
        Icon(dayIcon(d.sky), null, Modifier.size(22.dp), tint = skyTint(d.sky, true))
        Row {
            Text("${d.high}°", style = Tv.text(12f, FontWeight.SemiBold))
            Spacer(Modifier.width(4.dp))
            Text("${d.low}°", style = Tv.text(12f, color = Tv.muted))
        }
        Text("${d.wet}%", style = Tv.text(9.5f, color = if (d.wet >= 50) Tv.accent else Tv.muted))
    }
}

@Composable
private fun WarningBanner(w: Warning, count: Int) {
    val colour = when (w.level) {
        3 -> Color(0xFFE5484D)
        2 -> Color(0xFFF2994A)
        else -> Color(0xFFF2C94C)
    }
    val level = when (w.level) { 3 -> "Red"; 2 -> "Orange"; else -> "Yellow" }
    val until = w.expires?.let { " until " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(it) } ?: ""
    Row(
        Modifier.fillMaxWidth().background(colour.copy(alpha = 0.22f)).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colour))
        Text(
            "$level warning: ${w.event}$until" + if (count > 1) "  ·  ${count - 1} more" else "",
            style = Tv.text(10.5f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun words(s: Sky, day: Boolean) = when (s) {
    Sky.CLEAR -> if (day) "Sunny" else "Clear"
    Sky.PARTLY -> "Partly cloudy"
    Sky.CLOUDY -> "Cloudy"
    Sky.FOG -> "Fog"
    Sky.RAIN -> "Rain"
    Sky.SNOW -> "Snow"
    Sky.THUNDER -> "Thunder"
}

private fun dayIcon(s: Sky) = when (s) {
    Sky.CLEAR -> Ic.sun
    Sky.PARTLY -> Ic.partly
    Sky.CLOUDY -> Ic.cloud
    Sky.FOG -> Ic.fog
    Sky.RAIN -> Ic.rain
    Sky.SNOW -> Ic.snowfall
    Sky.THUNDER -> Ic.thunder
}

private fun skyTint(s: Sky, day: Boolean) = when (s) {
    Sky.CLEAR, Sky.PARTLY, Sky.THUNDER -> if (day || s == Sky.THUNDER) Color(0xFFFFD27A) else Color(0xFFFFE3A8)
    Sky.RAIN -> Color(0xFFBFE2FF)
    Sky.SNOW -> Color(0xFFE6F1FF)
    else -> Tv.ink2
}

private val colours = HashMap<String, Color>()

/** The colour an app's banner (or icon) is mostly, bright enough to glow with. */
fun appColour(key: String, picture: ImageBitmap?): Color {
    colours[key]?.let { return it }
    val found = picture?.let { runCatching { dominant(it) }.getOrNull() } ?: Tv.accent
    colours[key] = found
    return found
}

private fun dominant(img: ImageBitmap): Color? {
    val px = img.toPixelMap()
    val buckets = Array(24) { FloatArray(4) }
    val hsv = FloatArray(3)
    val step = maxOf(1, minOf(img.width, img.height) / 48)
    var y = 0
    while (y < img.height) {
        var x = 0
        while (x < img.width) {
            val c = px[x, y]
            if (c.alpha > 0.5f) {
                android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                if (hsv[1] > 0.25f && hsv[2] > 0.2f) {
                    val weight = hsv[1] * hsv[2]
                    val b = buckets[(hsv[0] / 15f).toInt().coerceIn(0, 23)]
                    b[0] += weight; b[1] += c.red * weight; b[2] += c.green * weight; b[3] += c.blue * weight
                }
            }
            x += step
        }
        y += step
    }
    val best = buckets.maxByOrNull { it[0] } ?: return null
    if (best[0] < 1f) return null
    val c = Color(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    // Lifted so dark brand colours still glow
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.75f)
    hsv[1] = hsv[1].coerceAtMost(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
