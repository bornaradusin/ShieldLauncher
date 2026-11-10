package io.github.bornaradusin.launcher.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bornaradusin.launcher.sky.Look
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The big clock on the apps row, in the style chosen in Settings: as it always was, flip, or minimal. */
@Composable
fun BigClock(now: Long, day: String, style: String) {
    val context = LocalContext.current
    when (style) {
        "flip" -> {
            val h24 = DateFormat.is24HourFormat(context)
            val hours = SimpleDateFormat(if (h24) "HH" else "h", Locale.getDefault()).format(Date(now))
            val minutes = SimpleDateFormat("mm", Locale.getDefault()).format(Date(now))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlipTile(hours)
                FlipTile(minutes)
                if (!h24) Text(SimpleDateFormat("a", Locale.getDefault()).format(Date(now)), style = Tv.text(12f, FontWeight.SemiBold, color = Tv.ink2), modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(day, style = Tv.text(13f, color = Tv.ink2))
        }
        "minimal" -> {
            val h24 = DateFormat.is24HourFormat(context)
            val hours = SimpleDateFormat(if (h24) "HH" else "h", Locale.getDefault()).format(Date(now))
            val minutes = SimpleDateFormat("mm", Locale.getDefault()).format(Date(now))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(hours) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Light, color = Color(0xFF5E6878))) { append(":") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Light, color = Color(0xFFC3CCD8))) { append(minutes) }
                    if (!h24) withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Color(0xFF9AA5B4))) {
                        append(" " + SimpleDateFormat("a", Locale.getDefault()).format(Date(now)))
                    }
                },
                style = Tv.text(76f, lineHeight = 72f, spacing = -1f),
            )
            Text(day.uppercase(Locale.getDefault()), style = Tv.text(10.5f, FontWeight.Medium, color = Color(0xFF9AA5B4), spacing = 3f))
        }
        else -> {
            Text(DateFormat.getTimeFormat(context).format(Date(now)), style = Tv.text(66f, FontWeight.Light, lineHeight = 66f, spacing = -1f))
            Text(day, style = Tv.text(13f, color = Tv.ink2))
        }
    }
}

private val FLIP_TOP = Brush.verticalGradient(listOf(Color(0xFF3D3F45), Color(0xFF2B2D32)))
private val FLIP_BOTTOM = Brush.verticalGradient(listOf(Color(0xFF24262A), Color(0xFF18191C)))
private val FLIP_TOP_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val FLIP_BOTTOM_SHAPE = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)

/**
 * One card of the flip clock, KitKat-era HTC style: when the number changes, the old one's top half
 * folds down over it, then the new one's bottom half lands.
 */
@Composable
private fun FlipTile(text: String) {
    var from by remember { mutableStateOf(text) }
    var to by remember { mutableStateOf(text) }
    val flip = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text != to) {
            from = to
            to = text
            flip.snapTo(0f)
            flip.animateTo(1f, tween(600, easing = LinearEasing))
        }
    }
    val density = LocalDensity.current.density
    val v = flip.value
    Box(Modifier.size(82.dp, 76.dp).shadow(14.dp, RoundedCornerShape(8.dp), clip = false)) {
        FlipHalf(top = true, text = if (v > 0f) to else from)
        FlipHalf(top = false, text = if (v >= 1f) to else from)
        if (v > 0f && v < 0.5f) {
            FlipHalf(top = true, text = from, modifier = Modifier.graphicsLayer {
                rotationX = -180f * v
                transformOrigin = TransformOrigin(0.5f, 1f)
                cameraDistance = 14f * density
            })
        }
        if (v >= 0.5f && v < 1f) {
            FlipHalf(top = false, text = to, modifier = Modifier.graphicsLayer {
                rotationX = 180f * (1f - v)
                transformOrigin = TransformOrigin(0.5f, 0f)
                cameraDistance = 14f * density
            })
        }
        // The split between the halves, and the hinges at each side
        Box(Modifier.offset(y = 37.5.dp).fillMaxWidth().height(1.dp).background(Color(0xCC000000)))
        Box(Modifier.offset(x = (-1).dp, y = 33.dp).size(3.dp, 10.dp).clip(RoundedCornerShape(1.5.dp)).background(Color(0xFF0B0C0E)))
        Box(Modifier.offset(x = 80.dp, y = 33.dp).size(3.dp, 10.dp).clip(RoundedCornerShape(1.5.dp)).background(Color(0xFF0B0C0E)))
    }
}

@Composable
private fun FlipHalf(top: Boolean, text: String, modifier: Modifier = Modifier) {
    Box(
        Modifier.offset(y = if (top) 0.dp else 38.dp).then(modifier).size(82.dp, 38.dp)
            .clip(if (top) FLIP_TOP_SHAPE else FLIP_BOTTOM_SHAPE).background(if (top) FLIP_TOP else FLIP_BOTTOM),
    ) {
        Box(Modifier.requiredSize(82.dp, 76.dp).offset(y = if (top) 19.dp else (-19).dp), contentAlignment = Alignment.Center) {
            Text(text, style = Tv.text(58f, FontWeight.Medium, color = Color(0xFFF4F6F9), lineHeight = 76f, spacing = -1f))
        }
    }
}

/** The last minute of the year, counting down (the last ten seconds big and glowing). */
@Composable
fun Countdown(to: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(to) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val left = ((to - now + 999) / 1000).coerceIn(0L, 60L)
    val last = left <= 10
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("NEW YEAR IN", style = Tv.text(12f, FontWeight.SemiBold, color = Color(0xFFFFD36A), spacing = 2f))
        Text(
            if (last) "$left" else "0:%02d".format(left),
            style = Tv.text(if (last) 92f else 66f, FontWeight.Light, lineHeight = if (last) 88f else 66f, spacing = -1f)
                .copy(shadow = if (last) Shadow(Color(0xB3FFD36A), blurRadius = 30f) else null),
        )
    }
}

private const val PADDY_WISH = "Lá Fhéile Pádraig sona duit: happy St Patrick’s Day!"

/** A day's own words in place of the fun fact: the New Year's first hour, St Patrick's Day. */
fun specialDay(now: Long, seasonal: Boolean, preview: Look?): String? {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    if (preview == Look.FIREWORKS) return "Happy New Year! Welcome to ${c.get(Calendar.YEAR) + 1}."
    if (preview == Look.PADDY) return PADDY_WISH
    if (!seasonal) return null
    val month = c.get(Calendar.MONTH) + 1
    val day = c.get(Calendar.DAY_OF_MONTH)
    if (month == 1 && day == 1 && c.get(Calendar.HOUR_OF_DAY) == 0) return "Happy New Year! Welcome to ${c.get(Calendar.YEAR)}."
    if (month == 3 && day == 17) return PADDY_WISH
    return null
}
