package io.github.bornaradusin.launcher.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.weather.DayForecast
import io.github.bornaradusin.launcher.weather.DayPart
import io.github.bornaradusin.launcher.weather.Sky
import io.github.bornaradusin.launcher.weather.Warning
import io.github.bornaradusin.launcher.weather.WeatherNow
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A weather's colours: the sky at the top, then glows behind the clock, in the middle and low on the right. */
private class Palette(val sky: Color, val a: Color, val b: Color, val c: Color)

private fun pal(sky: Long, a: Long, b: Long, c: Long) = Palette(Color(sky), Color(a), Color(b), Color(c))

private val DAY_COLOURS = mapOf(
    Sky.CLEAR to pal(0xFF1F5FAE, 0xFF3B8BE0, 0xFFFFB45E, 0xFF5FB0FF),
    Sky.PARTLY to pal(0xFF2D5F96, 0xFF4A82BF, 0xFFD8AE70, 0xFF6C93C2),
    Sky.CLOUDY to pal(0xFF343D4B, 0xFF5A6678, 0xFF45516A, 0xFF6B7688),
    Sky.FOG to pal(0xFF4F5866, 0xFF7C8696, 0xFF677283, 0xFF8C95A3),
    Sky.RAIN to pal(0xFF172636, 0xFF2A4D6E, 0xFF1E5C63, 0xFF30587A),
    Sky.SNOW to pal(0xFF3B5878, 0xFF7EA7D8, 0xFFB5D2F0, 0xFF9CC0E6),
    Sky.THUNDER to pal(0xFF16132B, 0xFF3A2E6E, 0xFF283B5E, 0xFF4A3A7A),
)
private val NIGHT_COLOURS = mapOf(
    Sky.CLEAR to pal(0xFF081430, 0xFF1C2F6E, 0xFF3B2C78, 0xFF14295A),
    Sky.PARTLY to pal(0xFF0C1834, 0xFF22335F, 0xFF33305E, 0xFF1A2748),
    Sky.CLOUDY to pal(0xFF141922, 0xFF2B3342, 0xFF232B3A, 0xFF30394A),
    Sky.FOG to pal(0xFF1D222B, 0xFF3A424F, 0xFF323A47, 0xFF454D59),
    Sky.RAIN to pal(0xFF0B131D, 0xFF1A3048, 0xFF163A40, 0xFF1E3650),
    Sky.SNOW to pal(0xFF16233A, 0xFF3D5A86, 0xFF5A7CA8, 0xFF47658F),
    Sky.THUNDER to pal(0xFF0D0B1C, 0xFF2C2256, 0xFF1E2C48, 0xFF382C62),
)
private val DAWN = pal(0xFF26346E, 0xFFD9785E, 0xFFF2A65A, 0xFF7A5AA8)
private val DUSK = pal(0xFF251A4E, 0xFFB84C78, 0xFFEE8448, 0xFF5B3A8C)

/** How much of a low sun (sunrise, sunset) shows through each weather. */
private val THROUGH = mapOf(
    Sky.CLEAR to 1f, Sky.PARTLY to 0.85f, Sky.CLOUDY to 0.45f, Sky.FOG to 0.55f,
    Sky.RAIN to 0.38f, Sky.SNOW to 0.45f, Sky.THUNDER to 0.28f,
)

/** The weather and the part of the day, as the sky draws them. */
private class SkyState(val sky: Sky, part: DayPart) {
    val day = part != DayPart.NIGHT
    val low = part == DayPart.DAWN || part == DayPart.DUSK
    val dusk = part == DayPart.DUSK
    val through = THROUGH[sky] ?: 0.5f
    val palette: Palette = run {
        val base = (if (day) DAY_COLOURS else NIGHT_COLOURS).getValue(sky)
        if (!low) base else {
            val l = if (dusk) DUSK else DAWN
            Palette(lerp(base.sky, l.sky, through), lerp(base.a, l.a, through), lerp(base.b, l.b, through), lerp(base.c, l.c, through))
        }
    }

    /** Cloud tops and fog catch the colour of a low sun. */
    fun warm(c: Color): Color = if (!low) c else lerp(c, if (dusk) Color(0xFFFF9A8A) else Color(0xFFFFC49A), 0.5f * through + 0.15f)
}

private const val UNIT = 100f
private const val TAU = 6.2831855f

/**
 * What the sky draws with, made once: soft round glows of each colour (one brush per colour, moved and
 * sized as it's drawn), the clouds (each drawn once into a picture, then moved and faded as a whole),
 * the lightning's forks and the sun's rays.
 */
private class SkyArt {
    private val softBrushes = HashMap<Color, Brush>()
    private val blobBrushes = HashMap<Color, Brush>()
    val clouds = HashMap<String, ImageBitmap>()
    /** How many new cloud pictures may still be drawn this frame. */
    var budget = 2
    val path = Path()
    var bolt: Pair<Int, List<Path>>? = null
    private var raysFor: Pair<Size, Offset>? = null
    private var rays: List<Brush> = emptyList()
    var moon: Pair<Size, Path>? = null
    var shade: Pair<String, Path>? = null

    fun soft(c: Color): Brush = softBrushes.getOrPut(c) { Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset.Zero, radius = UNIT) }

    /** A cloud's puff: solid in the middle, soft at the edge. */
    fun blob(c: Color): Brush = blobBrushes.getOrPut(c) {
        Brush.radialGradient(0f to c, 0.62f to c.copy(alpha = 0.92f), 1f to c.copy(alpha = 0f), center = Offset.Zero, radius = UNIT)
    }

    /** The sun's rays: one brush each, from the sun out to the ray's length. */
    fun rays(size: Size, sun: Offset, count: Int, length: (Int) -> Float, colour: Color): List<Brush> {
        if (raysFor != size to sun || rays.size != count) {
            raysFor = size to sun
            rays = (0 until count).map { Brush.radialGradient(listOf(colour, colour.copy(alpha = 0f)), center = sun, radius = length(it)) }
        }
        return rays
    }

    private val brushes = HashMap<Any, Brush>()
    private val strokes = HashMap<Int, Stroke>()
    private val dropShapes = HashMap<Int, Path>()
    private var dropBrushes: Pair<Color, List<Brush>>? = null

    /** A brush that stays the same from frame to frame, made the first time it's needed. */
    fun brush(key: Any, make: () -> Brush): Brush {
        if (brushes.size > 160) brushes.clear()
        return brushes.getOrPut(key, make)
    }

    /** A round-ended line of [width] pixels (to a hundredth). */
    fun stroke(width: Float): Stroke = strokes.getOrPut((width * 100).toInt()) { Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round) }

    /** One of a dozen drop outlines (the size of [UNIT]), stretched a little more at each [step] as it runs. */
    fun dropShape(variant: Int, step: Int): Path = dropShapes.getOrPut(variant * 16 + step) {
        Path().also { dropOutline(it, variant * 7919 + 13, step * MAX_STRETCH / (STRETCH_STEPS - 1)) }
    }

    /** A drop's body (the world upside down), the light gathered at its bottom and its dark rim, for the sky's [tone]. */
    fun dropBrushes(tone: Color): List<Brush> = dropBrushes?.takeIf { it.first == tone }?.second ?: listOf(
        Brush.verticalGradient(
            0f to Color(0xFF04070C).copy(alpha = 0.5f),
            0.45f to tone.copy(alpha = 0.12f),
            0.8f to lerp(tone, Color.White, 0.45f).copy(alpha = 0.42f),
            1f to lerp(tone, Color.White, 0.7f).copy(alpha = 0.6f),
            startY = -UNIT * 1.6f, endY = UNIT,
        ),
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0f)), center = Offset(0f, 0.7f * UNIT), radius = 0.6f * UNIT),
        Brush.radialGradient(0.54f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f), center = Offset(0f, -0.08f * UNIT), radius = 1.15f * UNIT),
    ).also { dropBrushes = tone to it }

    /** Off the screen for a while: the clouds' pictures go, and are drawn again when they're next needed. */
    fun trim() {
        clouds.clear()
        brushes.clear()
    }
}

private const val STRETCH_STEPS = 7
private const val MAX_STRETCH = 0.36f
private val FLARE_RING = Stroke(width = 1.2f)

/** Off the screen for a while (another app is open): the sky's pictures are let go of. */
fun trimSky() = SHARED_ART.trim()

/** A soft round glow: [squash] flattens it into an oval. */
private fun DrawScope.glow(art: SkyArt, c: Color, x: Float, y: Float, r: Float, alpha: Float, squash: Float = 1f) {
    if (alpha <= 0.004f || r <= 0.5f) return
    val k = r / UNIT
    withTransform({
        translate(x, y)
        scale(k, k * squash, Offset.Zero)
    }) {
        drawCircle(art.soft(c), radius = UNIT, center = Offset.Zero, alpha = alpha.coerceAtMost(1f))
    }
}

/** A wash of colour from the top of the screen down to [extent] of its height. */
private fun DrawScope.wash(art: SkyArt, c: Color, alpha: Float, extent: Float) {
    val h = size.height * extent
    val brush = art.brush(Triple("wash", c, h)) { Brush.verticalGradient(listOf(c, c.copy(alpha = 0f)), endY = h) }
    drawRect(brush, size = Size(size.width, h), alpha = alpha.coerceIn(0f, 1f))
}

/**
 * Behind the apps row: the sky's own colours for the weather and the time of day (blue for sun, indigo
 * at night, steel blue in the rain, orange and pink at sunrise and sunset…), softly breathing, with the
 * weather moving over the whole screen. Before the weather is in, the focused app's colours.
 */
@Composable
fun AppsBackdrop(appColor: () -> Color, weather: WeatherNow?, active: Boolean, visible: () -> Float, modifier: Modifier = Modifier) {
    // It stays put when Home moves off the apps row (so coming back costs nothing). It only moves while
    // the apps row is focused: fading out, it holds still, so the fade is just the picture going see-through
    val time by skyTime(active)
    val now = rememberNow()
    val part = weather?.partAt(now) ?: DayPart.DAY
    val state = remember(weather?.sky, part) { weather?.let { SkyState(it.sky, part) } }
    val art = SHARED_ART
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = visible().coerceIn(0f, 1f) }) {
        if (visible() <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        // Breathing: the glows slowly brighten and dim
        val a = 0.75f + 0.25f * tri(time, 4.5f)
        val b = 1f - 0.3f * tri(time, 5.8f)
        art.budget = 2
        drawRect(Tv.bg)
        if (state == null) {
            val c = appColor()
            glow(art, c, w * 0.24f, h * 0.22f, w * 0.42f, 0.5f * a)
            glow(art, c, w * 0.56f, h * 0.38f, w * 0.34f, 0.26f * b)
        } else {
            val p = state.palette
            drawRect(
                art.brush(Triple("sky", p.sky, h)) { Brush.verticalGradient(0f to p.sky.copy(alpha = 0.85f), 0.55f to p.sky.copy(alpha = 0.35f), 1f to p.sky.copy(alpha = 0f), endY = h * 0.75f) },
                size = Size(w, h * 0.75f),
            )
            glow(art, p.a, w * 0.24f, h * 0.22f, w * 0.42f, 0.5f * a)
            glow(art, p.b, w * 0.62f, h * 0.34f, w * 0.36f, 0.32f * b)
            glow(art, p.c, w * 0.9f, h * 0.6f, w * 0.3f, 0.18f * a)
        }
        // Fading into the page under the rows
        drawRect(art.brush(Pair("fade", size)) { Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.72f to Tv.bg.copy(alpha = 0.88f), 1f to Tv.bg) })
        if (state != null) sky(art, state, time)
    }
}

/** Rain on the glass: drops in front of everything, landing, swelling and running down. */
@Composable
fun GlassDrops(weather: WeatherNow?, active: Boolean, visible: () -> Float, modifier: Modifier = Modifier) {
    val w = weather ?: return
    val sky = w.sky
    if (sky != Sky.RAIN && sky != Sky.THUNDER) return
    val time by skyTime(active)
    val part = w.partAt(rememberNow())
    // The sky's light the drops gather along their bottoms
    val tone = remember(sky, part) { SkyState(sky, part).palette.c }
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = visible().coerceIn(0f, 1f) }) {
        if (visible() <= 0.01f) return@Canvas
        drops(SHARED_ART, time, if (sky == Sky.THUNDER) 30 else 24, tone)
    }
}

/** What the sky draws with, kept for as long as the launcher runs: the clouds are drawn once, not every visit. */
private val SHARED_ART = SkyArt()

/** When the sky's clock started: one clock for the launcher's life, so the weather carries on where it was. */
private val SKY_START = System.nanoTime()

private fun skySeconds(nanos: Long) = ((nanos - SKY_START) / 1_000_000_000.0 % 7200.0).toFloat()

/** Seconds on the sky's clock, every frame while [running] (going round every 2 hours, so the numbers stay small). */
@Composable
private fun skyTime(running: Boolean) = produceState(skySeconds(System.nanoTime()), running) {
    if (!running) return@produceState
    while (true) withFrameNanos { value = skySeconds(it) }
}

/** 0 up to 1 and back down again, every [period] seconds each way. */
private fun tri(t: Float, period: Float): Float {
    val p = (t % (2 * period)) / period
    return if (p < 1f) p else 2f - p
}

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483647f
}

private fun DrawScope.sky(art: SkyArt, s: SkyState, t: Float) {
    // At sunrise and sunset the low sun is behind every weather, as much as the weather lets it show
    if (s.low && s.sky != Sky.CLEAR && s.sky != Sky.PARTLY) lowSun(art, s, t, s.through, clouds = false)
    when (s.sky) {
        Sky.RAIN -> rain(art, s, t, heavy = false)
        Sky.THUNDER -> thunder(art, s, t)
        Sky.SNOW -> snow(art, t)
        Sky.CLEAR -> when {
            s.low -> lowSun(art, s, t, 1f, clouds = true)
            s.day -> sun(art, t, 1f) { _, _ -> 1f }
            else -> night(art, t)
        }
        Sky.PARTLY -> when {
            s.low -> {
                lowSun(art, s, t, s.through, clouds = false)
                cloudField(art, t, Clouds(6, 2, s.warm(Color(0xFFF5F7FB)), if (s.dusk) Color(0xFF4A2E66) else Color(0xFF5A5480), 0.55f, 0.02f, 0.34f, 0.75f, 1.4f))
            }
            s.day -> {
                val clouds = Clouds(6, 2, Color(0xFFF5F7FB), Color(0xFF8D99AE), 0.5f, 0.02f, 0.34f, 0.75f, 1.4f)
                wash(art, Color(0xFF7FA6D8), 0.12f, 0.6f)
                // Clouds crossing the sun dim its rays
                sun(art, t, 0.85f) { sx, sy -> cloudCover(clouds, t, sx, sy) }
                cloudField(art, t, clouds)
            }
            else -> {
                night(art, t)
                cloudField(art, t, Clouds(6, 2, Color(0xFF7A88A4), Color(0xFF151B28), 0.65f, 0.02f, 0.34f, 0.75f, 1.4f))
            }
        }
        Sky.CLOUDY -> {
            wash(art, Color(0xFF1E2532), 0.25f, 0.75f)
            if (s.day && !s.low) glow(art, Color(0xFFFFE2B0), size.width * 0.75f, size.height * 0.05f, size.width * 0.4f, 0.05f)
            cloudField(
                art, t,
                Clouds(11, 3, s.warm(if (s.day) Color(0xFFAEB8C8) else Color(0xFF5C6678)), if (s.day) Color(0xFF3A4354) else Color(0xFF1A1F2A), 0.5f, -0.08f, 0.42f, 1.2f, 1f),
            )
        }
        Sky.FOG -> fog(art, s, t)
    }
}

// ---- Clouds: each a cluster of puffs, shaded underneath and lit on top, in layers drifting at their own speed

private class Clouds(
    val count: Int, val layers: Int, val top: Color, val bottom: Color, val alpha: Float,
    val y0: Float, val y1: Float, val scale: Float, val speed: Float,
)

/** Where cloud [i] is now: its middle, width and height. */
private fun DrawScope.cloudAt(o: Clouds, i: Int, t: Float): Triple<Offset, Float, Float> {
    val w = size.width
    val h = size.height
    val depth = (i % o.layers + 1f) / o.layers
    val cw = w * (0.22f + rnd(i, 41) * 0.2f) * o.scale * (0.7f + 0.5f * depth)
    val ch = cw * 0.3f
    val v = w * (0.004f + 0.007f * depth) * o.speed
    val span = w + cw * 2
    val x = ((t * v + rnd(i, 42) * span) % span) - cw
    val y = h * (o.y0 + rnd(i, 43) * (o.y1 - o.y0))
    return Triple(Offset(x + cw / 2, y), cw, ch)
}

private fun DrawScope.cloudField(art: SkyArt, t: Float, o: Clouds) {
    if (art.clouds.size > 80) art.clouds.clear()
    for (layer in 0 until o.layers) {
        val depth = (layer + 1f) / o.layers
        var i = layer
        while (i < o.count) {
            val (mid, cw, ch) = cloudAt(o, i, t)
            val picture = cloudPicture(art, i, o, cw, ch)
            if (picture == null) {
                i += o.layers
                continue
            }
            // Kept at half size (clouds are soft, so it doesn't show), drawn at full size
            val pw = picture.width * 2
            val ph = picture.height * 2
            drawImage(
                picture, dstOffset = IntOffset((mid.x - pw / 2f).toInt(), (mid.y - ph * 0.62f).toInt()), dstSize = IntSize(pw, ph),
                alpha = (o.alpha * (0.5f + 0.5f * depth)).coerceAtMost(1f), filterQuality = FilterQuality.Low,
            )
            i += o.layers
        }
    }
}

/** Cloud [i], drawn once: the shaded puffs, then their lit tops. */
private fun DrawScope.cloudPicture(art: SkyArt, i: Int, o: Clouds, cw: Float, ch: Float): ImageBitmap? {
    val key = "$i|${o.top.toArgb()}|${o.bottom.toArgb()}|${cw.toInt()}"
    art.clouds[key]?.let { return it }
    if (art.budget <= 0) return null
    art.budget--
    return art.clouds.getOrPut(key) {
        val pw = ceil(cw * 1.3f).toInt().coerceAtLeast(2)
        val ph = ceil(ch * 2.6f).toInt().coerceAtLeast(2)
        val image = ImageBitmap(pw / 2 + 1, ph / 2 + 1)
        val n = 8 + (rnd(i, 44) * 5).toInt()
        val puffs = ArrayList<Triple<Float, Float, Float>>()
        for (j in 0 until n) {
            val f = j / (n - 1f)
            val bump = sin(f * PI.toFloat())
            val px = pw / 2f + (f - 0.5f) * cw * 0.85f + (rnd(i * 13 + j, 45) - 0.5f) * cw * 0.08f
            val py = ph * 0.62f - bump * ch * 0.55f + (rnd(i * 13 + j, 46) - 0.5f) * ch * 0.3f
            val r = ch * (0.4f + 0.5f * bump) * (0.85f + 0.3f * rnd(i * 13 + j, 47))
            puffs += Triple(px, py, r)
        }
        // A few smaller puffs on top for a lumpier outline
        for (j in 0 until 4) {
            val f = 0.25f + rnd(i * 17 + j, 48) * 0.5f
            puffs += Triple(pw / 2f + (f - 0.5f) * cw * 0.8f, ph * 0.62f - ch * (0.75f + rnd(i * 17 + j, 49) * 0.35f), ch * (0.28f + rnd(i * 17 + j, 50) * 0.18f))
        }
        CanvasDrawScope().draw(this, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
            scale(0.5f, 0.5f, Offset.Zero) {
                puffs.forEach { (x, y, r) -> blob(art, o.bottom, x, y + r * 0.15f, r, 1f) }
                puffs.forEach { (x, y, r) -> blob(art, o.top, x - r * 0.1f, y - r * 0.32f, r * 0.72f, 0.85f) }
            }
        }
        image
    }
}

private fun DrawScope.blob(art: SkyArt, c: Color, x: Float, y: Float, r: Float, alpha: Float) {
    val k = r / UNIT
    withTransform({
        translate(x, y)
        scale(k, k, Offset.Zero)
    }) {
        drawCircle(art.blob(c), radius = UNIT, center = Offset.Zero, alpha = alpha)
    }
}

/** How much of the sun gets past the clouds: 1 none in the way, down to 0.35 behind one. */
private fun DrawScope.cloudCover(o: Clouds, t: Float, sx: Float, sy: Float): Float {
    var c = 1f
    for (i in 0 until o.count) {
        val (mid, cw, _) = cloudAt(o, i, t)
        val d = hypot((mid.x - sx) / cw, (mid.y - sy) / (cw * 0.5f))
        c = min(c, 0.35f + 0.65f * min(1f, d / 0.6f))
    }
    return c
}

// ---- Rain: three depths, gusts changing its slant, spray under the clouds

private class RainLayer(val n: Int, val heavy: Int, val width: Float, val a0: Float, val a1: Float, val v0: Float, val v1: Float, val l0: Float, val l1: Float, val tone: Color)

private val RAIN = listOf(
    RainLayer(130, 170, 1f, 0.10f, 0.22f, 0.85f, 1.15f, 0.025f, 0.045f, Color(0xFF9FB6D6)),
    RainLayer(75, 100, 1.6f, 0.25f, 0.45f, 1.35f, 1.8f, 0.05f, 0.08f, Color(0xFFBED8FF)),
    RainLayer(20, 30, 2.6f, 0.45f, 0.75f, 2.2f, 2.8f, 0.11f, 0.17f, Color(0xFFD8E8FF)),
)

private fun DrawScope.rain(art: SkyArt, s: SkyState, t: Float, heavy: Boolean) {
    val w = size.width
    val h = size.height
    wash(art, Color(0xFF0B121C), if (heavy) 0.45f else 0.3f, 0.75f)
    cloudField(
        art, t,
        Clouds(
            if (heavy) 10 else 8, 3, s.warm(if (heavy) Color(0xFF4A5368) else Color(0xFF5E6B82)),
            if (heavy) Color(0xFF0E131D) else Color(0xFF171E2B), if (heavy) 0.85f else 0.75f, -0.1f, 0.12f, 1.3f, if (heavy) 2.2f else 1.3f,
        ),
    )
    val slant = 0.2f + sin(t * 0.45f) * 0.07f + sin(t * 1.3f) * 0.025f
    val speed = if (heavy) 1.2f else 1f
    RAIN.forEachIndexed { li, layer ->
        val n = if (heavy) layer.heavy else layer.n
        for (i in 0 until n) {
            val sd = li * 1000 + i
            val v = h * (layer.v0 + rnd(sd, 1) * (layer.v1 - layer.v0)) * speed
            val len = h * (layer.l0 + rnd(sd, 2) * (layer.l1 - layer.l0))
            val y = ((t * v + rnd(sd, 3) * (h + len)) % (h + len)) - len
            val x = rnd(sd, 4) * w * 1.35f - y * slant
            val alpha = layer.a0 + rnd(sd, 5) * (layer.a1 - layer.a0)
            val end = Offset(x - len * slant, y + len)
            if (li == 2) {
                // Close up: a blurred streak round a sharp one that's brightest at its head
                drawLine(layer.tone, Offset(x, y), end, strokeWidth = layer.width * 3, cap = StrokeCap.Round, alpha = alpha * 0.25f)
                drawLine(layer.tone, Offset(x - len * slant * 0.5f, y + len * 0.5f), end, strokeWidth = layer.width, cap = StrokeCap.Round, alpha = alpha)
            } else {
                // Further away a single streak is enough: the eye can't tell
                drawLine(layer.tone, Offset(x, y), end, strokeWidth = layer.width, cap = StrokeCap.Round, alpha = alpha * 0.7f)
            }
        }
    }
    // Spray hanging under the clouds
    val spray = Color(0xFF96AAC8)
    drawRect(
        art.brush(Pair("spray", h)) { Brush.verticalGradient(0f to spray.copy(alpha = 0f), 0.5f to spray, 1f to spray.copy(alpha = 0f), startY = h * 0.12f, endY = h * 0.42f) },
        topLeft = Offset(0f, h * 0.12f), size = Size(w, h * 0.3f), alpha = if (heavy) 0.07f else 0.05f,
    )
}

/**
 * Drops on the glass, as real ones look: lumpy and heavier at the bottom, showing the world behind upside
 * down (dark at the top, the sky's light gathered at the bottom), a dark rim where they bend the light
 * away, a sharp highlight; running ones stretch into a teardrop and leave beads in their wet trail. A fine
 * mist of tiny droplets comes and goes between them. Every shape and brush is made once: each frame only
 * moves them, so the drops stay smooth.
 */
private fun DrawScope.drops(art: SkyArt, t: Float, count: Int, tone: Color) {
    val w = size.width
    val h = size.height
    for (i in 0 until 110) {
        val period = 9f + rnd(i, 201) * 14f
        val tt = t + rnd(i, 202) * period
        val cycle = floor(tt / period).toInt()
        val p = (tt % period) / period
        val k = i * 977 + cycle
        val a = min(1f, p / 0.15f) * min(1f, (1 - p) / 0.2f)
        if (a > 0.01f) bead(rnd(k, 203) * w, rnd(k, 204) * h, 1f + rnd(k, 205) * 2.6f, a)
    }
    val brushes = art.dropBrushes(tone)
    val wet = Color(0xFFBED7F5)
    for (i in 0 until count) {
        val period = 7f + rnd(i, 11) * 9f
        val tt = t + rnd(i, 12) * period
        val cycle = floor(tt / period).toInt()
        val p = (tt % period) / period
        val k = i * 131 + cycle
        val x0 = rnd(k, 13) * w
        val y0 = h * (0.02f + rnd(k, 14) * 0.85f)
        val r = 8f + rnd(k, 15) * 13f
        val runs = rnd(k, 16) > 0.45f
        val alpha = min(1f, p / 0.06f) * min(1f, (1f - p) / 0.12f)
        if (alpha <= 0.01f) continue
        var x = x0
        var y = y0
        var d = r * (0.55f + 0.45f * min(1f, p / 0.5f))
        var stretch = 0f
        if (runs && p > 0.62f) {
            val q = (p - 0.62f) / 0.38f
            y = y0 + q * q * h * 0.45f
            d = r * (1f - 0.3f * q)
            stretch = min(1f, q * 3f) * MAX_STRETCH
            x = x0 + sin((p - 0.62f) * 16f + i) * 3f
            // The wet trail, fainter higher up, with beads left along it
            val end = Offset(x, y - d)
            val half = Offset(x0, (y0 + end.y) / 2)
            drawLine(wet.copy(alpha = 0.04f * alpha), Offset(x0, y0), half, strokeWidth = d * 0.35f, cap = StrokeCap.Round)
            drawLine(wet.copy(alpha = 0.1f * alpha), half, end, strokeWidth = d * 0.35f, cap = StrokeCap.Round)
            for (b in 0 until 12) {
                val f = rnd(k * 31 + b, 17)
                val by = y0 + f * (y - y0 - d)
                if (by > y - d) continue
                bead(x0 + (rnd(k * 31 + b, 18) - 0.5f) * d * 0.5f + sin(f * 9f + i) * 2f, by, 1f + rnd(k * 31 + b, 19) * d * 0.16f, alpha * 0.9f)
            }
        }
        val step = ((stretch / MAX_STRETCH) * (STRETCH_STEPS - 1) + 0.5f).toInt().coerceIn(0, STRETCH_STEPS - 1)
        drop(art, art.dropShape(k.mod(12), step), brushes, x, y, d, step * MAX_STRETCH / (STRETCH_STEPS - 1), alpha)
    }
}

/** A drop's outline the size of [UNIT]: lumpy, flatter at the bottom; [stretch] pulls its top up into a tail. */
private fun dropOutline(path: Path, seed: Int, stretch: Float) {
    val n = 30
    for (j in 0..n) {
        val a = j.toFloat() / n * TAU
        val wob = 1 + 0.06f * sin(a * 2 + rnd(seed, 1) * TAU) + 0.045f * sin(a * 3 + rnd(seed, 2) * TAU) + 0.02f * sin(a * 5 + rnd(seed, 3) * TAU)
        var px = cos(a) * UNIT * wob * 0.94f
        var py = sin(a) * UNIT * wob
        if (py > 0f) {
            py *= 0.92f
        } else if (stretch > 0f) {
            val up = -py / UNIT
            py *= 1 + stretch * 1.6f
            px *= 1 - 0.55f * stretch * up
        }
        if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/** One drop of radius [r] at [x], [y]: drawn at the size of [UNIT], then scaled into place. */
private fun DrawScope.drop(art: SkyArt, shape: Path, brushes: List<Brush>, x: Float, y: Float, r: Float, stretch: Float, alpha: Float) {
    val k = r / UNIT
    // How wide a pixel is, at the size the drop is drawn
    val px = UNIT / r
    withTransform({
        translate(x, y)
        scale(k, k, Offset.Zero)
    }) {
        // Its shadow on the screen behind, a little down and to the right
        withTransform({ translate(0.18f * UNIT, 0.28f * UNIT) }) { drawPath(shape, Color.Black, alpha = 0.16f * alpha) }
        // The world behind upside down, the light gathered at the bottom, the dark rim
        drawPath(shape, brushes[0], alpha = alpha)
        drawPath(shape, brushes[1], alpha = alpha)
        drawPath(shape, brushes[2], alpha = alpha)
        drawPath(shape, Color.Black, alpha = 0.35f * alpha, style = art.stroke(px))
        // A thin bright edge low down, and the sharp highlights
        drawArc(
            Color.White, startAngle = 45f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(-0.86f * UNIT, 0.05f * UNIT - 0.86f * UNIT), size = Size(1.72f * UNIT, 1.72f * UNIT),
            alpha = 0.35f * alpha, style = art.stroke(max(1f, r * 0.08f) * px),
        )
        withTransform({
            translate(-0.34f * UNIT, (-0.42f - stretch * 0.5f) * UNIT)
            rotate(-34f, Offset.Zero)
        }) {
            drawOval(Color.White, topLeft = Offset(-0.2f * UNIT, -0.11f * UNIT), size = Size(0.4f * UNIT, 0.22f * UNIT), alpha = 0.9f * alpha)
        }
        drawCircle(Color.White, radius = max(0.8f * px, 0.07f * UNIT), center = Offset(0.32f * UNIT, 0.3f * UNIT), alpha = 0.5f * alpha)
    }
}

/** A tiny droplet: its body and a glint (and a shadow, when it's big enough to have one). */
private fun DrawScope.bead(x: Float, y: Float, r: Float, alpha: Float) {
    if (r > 2f) drawCircle(Color.Black, radius = r, center = Offset(x + r * 0.2f, y + r * 0.3f), alpha = 0.22f * alpha)
    drawCircle(BEAD, radius = r, center = Offset(x, y), alpha = 0.22f * alpha)
    drawCircle(Color.White, radius = max(0.6f, r * 0.32f), center = Offset(x - r * 0.3f, y - r * 0.35f), alpha = 0.7f * alpha)
}

private val BEAD = Color(0xFFBED7F5)

// ---- Thunder: heavy rain, lightning flickering inside the clouds, and a forked bolt now and then

private fun DrawScope.thunder(art: SkyArt, s: SkyState, t: Float) {
    val w = size.width
    val h = size.height
    rain(art, s, t, heavy = true)
    val p2 = 2.7f
    val c2 = floor(t / p2).toInt()
    val k2 = t - c2 * p2
    if (k2 < 0.4f && rnd(c2, 22) > 0.3f) {
        glow(art, Color(0xFFB4C3FF), w * (0.1f + rnd(c2, 23) * 0.8f), h * 0.06f, w * 0.22f, 0.28f * (1 - k2 / 0.4f) * (if (k2 < 0.06f || k2 > 0.12f) 1f else 0.3f))
    }
    val period = 6.5f
    val c = floor(t / period).toInt()
    val k = t - c * period - (0.4f + rnd(c, 21) * 2.5f)
    if (k >= 0f && k < 0.8f) {
        val on = when {
            k < 0.07f -> 1f
            k < 0.12f -> 0.2f
            k < 0.24f -> 0.85f
            else -> max(0f, 1f - (k - 0.24f) / 0.56f) * 0.3f
        }
        drawRect(Color(0xFFE1E8FF).copy(alpha = 0.2f * on))
        if (k < 0.32f) {
            val top = bolt(art, c, on)
            glow(art, Color(0xFFC8D2FF), top.x, top.y, w * 0.3f, 0.45f * on)
        }
    }
}

/** A strike's bolt, made once for it: down from the clouds, zig-zagging, with two or three forks. */
private fun DrawScope.bolt(art: SkyArt, seed: Int, on: Float): Offset {
    val w = size.width
    val h = size.height
    val made = art.bolt?.takeIf { it.first == seed }?.second ?: run {
        val pts = ArrayList<Offset>()
        var x = w * (0.18f + rnd(seed, 51) * 0.7f)
        var y = h * 0.06f
        val end = h * (0.48f + rnd(seed, 52) * 0.2f)
        pts += Offset(x, y)
        var s = 0
        while (y < end) {
            y += h * (0.025f + rnd(seed * 7 + s, 53) * 0.03f)
            x += (rnd(seed * 7 + s, 54) - 0.5f) * w * 0.045f
            pts += Offset(x, y)
            s++
        }
        val paths = ArrayList<Path>()
        paths += Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        listOf(0.3f, 0.55f, 0.75f).forEachIndexed { b, f ->
            if (rnd(seed, 60 + b) < 0.25f) return@forEachIndexed
            val start = pts[(f * (pts.size - 1)).toInt()]
            val dir = if (rnd(seed, 70 + b) < 0.5f) -1f else 1f
            var bx = start.x
            var by = start.y
            val fork = Path().apply { moveTo(bx, by) }
            val n = 3 + (rnd(seed, 80 + b) * 4).toInt()
            for (j in 0 until n) {
                bx += dir * w * (0.008f + rnd(seed * 11 + j + b * 9, 55) * 0.025f)
                by += h * (0.018f + rnd(seed * 11 + j + b * 9, 56) * 0.025f)
                fork.lineTo(bx, by)
            }
            paths += fork
        }
        art.bolt = seed to paths
        paths
    }
    made.forEachIndexed { i, p ->
        val k = if (i == 0) 1f else 0.55f
        drawPath(p, Color(0xFFA0B4FF).copy(alpha = 0.10f * on), style = art.stroke(18f * k))
        drawPath(p, Color(0xFFC8D7FF).copy(alpha = 0.35f * on), style = art.stroke(6f * k))
        drawPath(p, Color.White.copy(alpha = 0.95f * on), style = art.stroke(2f * k))
    }
    return Offset(w * (0.18f + rnd(seed, 51) * 0.7f), h * 0.06f)
}

// ---- Snow: near and far (big soft flakes close up), gusts sweeping it sideways, flakes glinting, frost

private class SnowLayer(val n: Int, val r0: Float, val r1: Float, val v0: Float, val v1: Float, val a0: Float, val a1: Float, val sway: Float, val wind: Float)

private val SNOW = listOf(
    SnowLayer(160, 0.7f, 1.5f, 0.03f, 0.05f, 0.35f, 0.6f, 8f, 0.5f),
    SnowLayer(95, 1.8f, 3.6f, 0.06f, 0.1f, 0.6f, 0.95f, 18f, 1f),
    SnowLayer(16, 10f, 22f, 0.11f, 0.16f, 0.16f, 0.32f, 30f, 1.8f),
)

private fun DrawScope.snow(art: SkyArt, t: Float) {
    val w = size.width
    val h = size.height
    wash(art, Color(0xFFC9DCF5), 0.12f, 0.6f)
    glow(art, Color(0xFFDCE8FF), w * 0.5f, h * 0.95f, w * 0.6f, 0.07f)
    // Frost on the corners of the glass, breathing a little
    val frost = 0.16f + 0.03f * sin(t * 0.3f)
    glow(art, Color(0xFFE8F2FF), 0f, 0f, w * 0.22f, frost)
    glow(art, Color(0xFFE8F2FF), w, 0f, w * 0.22f, frost)
    glow(art, Color(0xFFE8F2FF), 0f, h, w * 0.16f, frost * 0.6f)
    glow(art, Color(0xFFE8F2FF), w, h, w * 0.16f, frost * 0.6f)
    val drift = t * 12f + sin(t * 0.35f) * 70f + sin(t * 0.9f) * 18f
    val across = w + 80f
    SNOW.forEachIndexed { li, layer ->
        for (i in 0 until layer.n) {
            val s = li * 1000 + i
            val r = layer.r0 + rnd(s, 1) * (layer.r1 - layer.r0)
            val v = h * (layer.v0 + rnd(s, 2) * (layer.v1 - layer.v0))
            val y = ((t * v + rnd(s, 3) * (h + 60f)) % (h + 60f)) - 30f
            val raw = rnd(s, 4) * across + drift * layer.wind + sin(t * (0.4f + rnd(s, 5) * 0.6f) + rnd(s, 6) * TAU) * layer.sway
            val x = ((raw % across) + across) % across - 40f
            var a = layer.a0 + rnd(s, 7) * (layer.a1 - layer.a0)
            when (li) {
                2 -> glow(art, Color.White, x, y, r, a)
                else -> {
                    if (li == 1 && rnd(s, 8) > 0.8f) a *= 0.6f + 0.4f * max(0f, sin(t * 3f + rnd(s, 9) * TAU)) * 1.6f
                    drawCircle(Color.White.copy(alpha = a.coerceAtMost(1f)), radius = r, center = Offset(x, y))
                    if (li == 1) glow(art, Color.White, x, y, r * 3f, a * 0.12f)
                }
            }
        }
    }
}

// ---- Sun: a bright sun with slowly turning rays, the lens's flare across the screen, dust in the light

private fun DrawScope.sun(art: SkyArt, t: Float, strength: Float, cover: DrawScope.(Float, Float) -> Float) {
    val w = size.width
    val h = size.height
    val sx = w * 0.84f
    val sy = h * 0.03f
    val k = strength * cover(sx, sy)
    glow(art, Color(0xFFFFBE6E), sx, sy, w * 0.65f, 0.2f * k)
    val rays = art.rays(size, Offset(sx, sy), 16, { i -> w * (0.35f + 0.3f * rnd(i, 31)) }, Color(0xFFFFE4AA))
    val base = t * 0.025f
    for (i in 0 until 16) {
        val a0 = base + i * TAU / 16 + 0.12f * sin(t * 0.17f + i * 2.1f)
        val len = w * (0.35f + 0.3f * rnd(i, 31))
        val half = 0.025f + 0.03f * rnd(i, 32)
        val alpha = (0.05f + 0.07f * rnd(i, 33)) * (0.6f + 0.4f * sin(t * 0.55f + i * 1.7f)) * k
        ray(art, rays[i], sx, sy, a0, half, len, alpha)
    }
    val pulse = 0.9f + 0.1f * sin(t * TAU / 7f)
    glow(art, Color(0xFFFFE6B0), sx, sy, w * 0.17f * pulse, 0.45f * k)
    glow(art, Color(0xFFFFFDF2), sx, sy, w * 0.05f, 0.95f * k)
    // The flare: rings on the line from the sun through the middle of the screen
    val cx = w * 0.5f
    val cy = h * 0.5f
    val sway = 1 + 0.02f * sin(t * 0.4f)
    FLARE.forEach { (f, r, colour, a) ->
        val x = sx + (cx - sx) * f * sway
        val y = sy + (cy - sy) * f * sway
        glow(art, colour, x, y, w * r, a * k * pulse)
        drawCircle(colour.copy(alpha = (a * 0.5f * k).coerceIn(0f, 1f)), radius = w * r * 0.8f, center = Offset(x, y), style = FLARE_RING)
    }
    // Dust drifting and glinting in the light
    val band = h * 0.75f
    for (i in 0 until 60) {
        val v = 6f + rnd(i, 91) * 12f
        val x = ((w * (0.35f + rnd(i, 92) * 0.7f) - t * v) % w + w) % w
        val y = ((band * rnd(i, 93) - t * v * 0.35f) % band + band) % band
        val a = (0.15f + 0.45f * max(0f, sin(t * (0.8f + rnd(i, 94)) + rnd(i, 95) * TAU))) * k
        val near = 1 - min(1f, hypot(x - sx, y - sy) / (w * 0.7f))
        drawCircle(Color(0xFFFFF2D6).copy(alpha = (a * (0.3f + 0.7f * near) * 0.6f).coerceIn(0f, 1f)), radius = 1f + rnd(i, 96) * 1.5f, center = Offset(x, y))
    }
}

private data class Ghost(val at: Float, val radius: Float, val colour: Color, val alpha: Float)

private val FLARE = listOf(
    Ghost(0.32f, 0.012f, Color(0xFFFFD27A), 0.16f), Ghost(0.55f, 0.03f, Color(0xFF7CE0FF), 0.07f),
    Ghost(0.78f, 0.016f, Color(0xFFC7A6FF), 0.12f), Ghost(1.15f, 0.055f, Color(0xFF8CFFC1), 0.05f),
    Ghost(1.4f, 0.022f, Color(0xFFFFB27A), 0.1f), Ghost(1.62f, 0.08f, Color(0xFF7CC4FF), 0.04f),
)

/** A ray: a thin wedge out from the sun, fading along its length. */
private fun DrawScope.ray(art: SkyArt, brush: Brush, sx: Float, sy: Float, angle: Float, half: Float, len: Float, alpha: Float) {
    if (alpha <= 0.003f) return
    val p = art.path
    p.reset()
    p.moveTo(sx, sy)
    p.lineTo(sx + cos(angle - half) * len, sy + sin(angle - half) * len)
    p.lineTo(sx + cos(angle + half) * len, sy + sin(angle + half) * len)
    p.close()
    drawPath(p, brush, alpha = alpha.coerceAtMost(1f))
}

// ---- Sunrise and sunset: the sun low on the right, haze along the horizon, thin clouds lit from below

private fun DrawScope.lowSun(art: SkyArt, s: SkyState, t: Float, k: Float, clouds: Boolean) {
    val w = size.width
    val h = size.height
    val dusk = s.dusk
    // The last (or first) stars
    if (k >= 0.8f) {
        for (i in 0 until 70) {
            val tw = 0.5f + 0.5f * sin(t * (0.6f + rnd(i, 1) * 1.4f) + rnd(i, 2) * TAU)
            val y = rnd(i, 5) * h * 0.38f
            drawCircle(Color.White.copy(alpha = ((0.08f + 0.3f * tw) * (1 - y / (h * 0.38f))).coerceIn(0f, 1f)), radius = 0.7f + rnd(i, 3), center = Offset(rnd(i, 4) * w * (if (dusk) 0.7f else 0.6f), y))
        }
    }
    val sx = w * 0.95f
    val hy = h * 0.41f
    val pulse = 0.92f + 0.08f * sin(t * TAU / 8f)
    glow(art, if (dusk) Color(0xFFFF5E3A) else Color(0xFFFF9450), sx, hy, w * 0.8f, 0.30f * pulse * (0.4f + 0.6f * k))
    glow(art, if (dusk) Color(0xFFFFB067) else Color(0xFFFFD08A), sx, hy, w * 0.3f, 0.42f * pulse * k)
    // Bands of haze along the horizon, drifting
    for (i in 0 until 6) {
        val y = hy - h * (0.04f + i * 0.045f)
        val shift = sin(t * 0.12f + i * 1.7f) * w * 0.05f
        val colour = if (i % 2 == 1) (if (dusk) Color(0xFFFF7A8C) else Color(0xFFFFB089)) else (if (dusk) Color(0xFFFFA05A) else Color(0xFFFFD2A0))
        val band = art.brush(Triple("band", colour, w)) {
            Brush.horizontalGradient(0f to colour.copy(alpha = 0f), 0.7f to colour, 1f to colour.copy(alpha = 0.25f), startX = -w * 0.1f, endX = w * 1.1f)
        }
        withTransform({ translate(shift, 0f) }) {
            drawRect(band, topLeft = Offset(-shift, y), size = Size(w, h * 0.02f), alpha = ((0.09f - i * 0.01f) * (0.5f + 0.5f * k)).coerceIn(0f, 1f))
        }
    }
    // Its long rays, when enough of it shows
    if (k > 0.5f) {
        val rays = art.rays(size, Offset(sx, hy), 10, { i -> w * (0.4f + 0.3f * rnd(i, 31)) }, Color(0xFFFFD696))
        for (i in 0 until 10) {
            val a0 = PI.toFloat() + 0.15f + i * 0.12f + 0.03f * sin(t * 0.3f + i)
            val len = w * (0.4f + 0.3f * rnd(i, 31))
            val half = 0.012f + 0.012f * rnd(i, 32)
            ray(art, rays[i], sx, hy, a0, half, len, (0.05f + 0.06f * rnd(i, 33)) * (0.6f + 0.4f * sin(t * 0.5f + i * 1.3f)) * k)
        }
    }
    glow(art, Color(0xFFFFF3D6), sx, hy, w * 0.05f, 0.95f * k * k)
    if (clouds) {
        cloudField(art, t, Clouds(5, 2, if (dusk) Color(0xFFFFA3B0) else Color(0xFFFFC9A0), if (dusk) Color(0xFF4A2E66) else Color(0xFF4E4A7A), 0.45f, 0.06f, 0.3f, 0.45f, 1.2f))
    }
}

// ---- Night: the Milky Way, stars twinkling in colour (the brightest glint), the moon, shooting stars

private val STAR_TINTS = listOf(Color.White, Color(0xFFCFE0FF), Color(0xFFFFE6C2))

private fun DrawScope.night(art: SkyArt, t: Float) {
    val w = size.width
    val h = size.height
    wash(art, Color(0xFF0A1430), 0.35f, 0.8f)
    // The Milky Way: a soft band, dusted with faint stars
    withTransform({
        translate(w * 0.42f, h * 0.32f)
        rotate(-24f, Offset.Zero)
    }) {
        glow(art, Color(0xFF8C8CFF), 0f, 0f, w * 0.62f, 0.10f, squash = 0.16f)
        glow(art, Color(0xFFC8B4FF), w * 0.1f, 0f, w * 0.35f, 0.06f, squash = 0.16f)
    }
    val slope = tan(0.42f)
    for (i in 0 until 380) {
        val f = rnd(i, 101)
        val off = (rnd(i, 102) + rnd(i, 103) + rnd(i, 104) - 1.5f) * h * 0.13f
        val x = w * (-0.1f + f * 1.1f)
        val y = h * 0.32f - (x - w * 0.42f) * slope + off
        drawRect(Color(0xFFDCE1FF).copy(alpha = 0.12f + rnd(i, 105) * 0.3f), topLeft = Offset(x, y), size = Size(1.1f, 1.1f))
    }
    for (i in 0 until 120) {
        val tier = if (i < 8) 2 else if (i < 40) 1 else 0
        val tw = 0.5f + 0.5f * sin(t * (0.6f + rnd(i, 1) * 1.6f) + rnd(i, 2) * TAU)
        val x = rnd(i, 4) * w
        val y = rnd(i, 5) * h * 0.66f
        val r = (if (tier == 2) 2.2f else if (tier == 1) 1.3f else 0.7f) + rnd(i, 3) * 0.6f
        val a = ((if (tier == 2) 0.55f else 0.15f) + 0.6f * tw).coerceAtMost(1f)
        val tint = STAR_TINTS[(rnd(i, 6) * 3).toInt().coerceIn(0, 2)]
        drawCircle(tint.copy(alpha = a), radius = r, center = Offset(x, y))
        if (tier > 0) glow(art, tint, x, y, r * 5, a * 0.18f)
        if (tier == 2) {
            val l = 10f + 16f * tw
            drawLine(tint.copy(alpha = a * 0.55f), Offset(x - l, y), Offset(x + l, y), strokeWidth = 1f)
            drawLine(tint.copy(alpha = a * 0.55f), Offset(x, y - l), Offset(x, y + l), strokeWidth = 1f)
        }
    }
    // The moon as it is tonight: its phase from the date, its glow as bright as it is full
    val mx = w * 0.7f
    val my = h * 0.085f
    val mr = h * 0.042f
    val phase = moonPhase(System.currentTimeMillis())
    val lit = (1 - cos(phase * TAU)) / 2
    glow(art, Color(0xFFC8D7FF), mx, my, mr * 7, 0.13f * (0.25f + 0.75f * lit))
    glow(art, Color(0xFFE6EEFF), mx, my, mr * 2.2f, 0.18f * (0.25f + 0.75f * lit))
    val disc = art.moon?.takeIf { it.first == size }?.second ?: Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(mx, my), mr))
    }.also { art.moon = size to it }
    clipPath(disc) {
        drawCircle(
            art.brush(Pair("moon", size)) { Brush.radialGradient(listOf(Color(0xFFFBFCFF), Color(0xFFBAC5D8)), center = Offset(mx - mr * 0.35f, my - mr * 0.35f), radius = mr * 1.4f) },
            radius = mr, center = Offset(mx, my),
        )
        CRATERS.forEach { (dx, dy, rr) -> glow(art, Color(0xFF8E9AB2), mx + dx * mr, my + dy * mr, rr * mr, 0.35f) }
        drawPath(moonShadow(art, mx, my, mr, phase), Color(0xDB10182C))
    }
    // Shooting stars: two can be on their way at once, each at its own moment
    for (s in 0 until 2) {
        val period = 5.5f + s * 2.3f
        val tt = t + s * 2.7f
        val c = floor(tt / period).toInt()
        val k = tt - c * period
        val seed = c * 3 + s
        val dur = 0.7f + rnd(seed, 111) * 0.5f
        if (k > dur) continue
        val p = k / dur
        val ang = 2.55f + rnd(seed, 112) * 0.35f
        val len = w * (0.22f + rnd(seed, 113) * 0.12f)
        val x0 = w * (0.45f + rnd(seed, 114) * 0.5f)
        val y0 = h * (0.02f + rnd(seed, 115) * 0.25f)
        val head = Offset(x0 + cos(ang) * len * p, y0 + sin(ang) * len * p)
        val tail = len * 0.45f * min(1f, p * 3)
        val end = Offset(head.x - cos(ang) * tail, head.y - sin(ang) * tail)
        val fade = min(1f, (1 - p) * 3)
        drawLine(
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f * fade), Color(0x00B4C8FF)), start = head, end = end),
            head, end, strokeWidth = 2.2f, cap = StrokeCap.Round,
        )
        glow(art, Color.White, head.x, head.y, 10f, 0.7f * fade)
    }
}

/** How far through its month the moon is: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
fun moonPhase(now: Long): Float {
    // A new moon: 6 January 2000, 18:14 UTC; a lunar month is 29.53 days
    val days = (now - 947_182_440_000L) / 86_400_000.0
    val p = (days / 29.530588853) % 1.0
    return (if (p < 0) p + 1 else p).toFloat()
}

/**
 * The moon's dark part: the dark side's edge, then back along the line between day and night on it
 * (lit on the right while it grows, on the left while it wanes, as seen from the north).
 */
private fun moonShadow(art: SkyArt, cx: Float, cy: Float, r: Float, phase: Float): Path {
    val key = "${(phase * 2000).toInt()}|$cx|$cy|$r"
    art.shade?.takeIf { it.first == key }?.let { return it.second }
    val c = cos(phase * TAU)
    val growing = phase < 0.5f
    val path = Path()
    val n = 36
    for (j in 0..n) {
        val y = -r + 2 * r * j / n
        val half = sqrt(max(0f, r * r - y * y))
        val x = if (growing) cx - half else cx + half
        if (j == 0) path.moveTo(x, cy + y) else path.lineTo(x, cy + y)
    }
    for (j in n downTo 0) {
        val y = -r + 2 * r * j / n
        val half = sqrt(max(0f, r * r - y * y))
        path.lineTo(if (growing) cx + c * half else cx - c * half, cy + y)
    }
    path.close()
    art.shade = key to path
    return path
}

private val CRATERS = listOf(Triple(-0.3f, 0.1f, 0.22f), Triple(0.25f, -0.3f, 0.15f), Triple(0.1f, 0.35f, 0.18f), Triple(-0.05f, -0.15f, 0.1f), Triple(0.4f, 0.2f, 0.09f))

// ---- Fog: banks of mist drifting at different depths and speeds, specks of damp in the air

private fun DrawScope.fog(art: SkyArt, s: SkyState, t: Float) {
    val w = size.width
    val h = size.height
    drawRect(if (s.day) Color(0x2496A0B2) else Color(0x245A6270))
    wash(art, s.warm(if (s.day) Color(0xFFAEB8C8) else Color(0xFF5E6674)), 0.12f, 0.9f)
    val bank = s.warm(if (s.day) Color(0xFFD2DAE6) else Color(0xFF8E97A6))
    for (i in 0 until 14) {
        val depth = 0.4f + 0.6f * rnd(i, 121)
        val bw = w * (0.55f + rnd(i, 122) * 0.6f)
        val bh = h * (0.1f + rnd(i, 123) * 0.14f)
        val v = w * (0.008f + 0.02f * depth) * (if (rnd(i, 124) < 0.5f) -1f else 1f)
        val span = w + bw
        val x = (((t * v + rnd(i, 125) * span) % span) + span) % span - bw * 0.75f
        val y = h * (0.06f + rnd(i, 126) * 0.68f) + sin(t * 0.2f + i) * h * 0.01f
        val a = (0.16f + 0.2f * depth) * (0.7f + 0.3f * sin(t * 0.35f + i * 1.3f)) * (if (s.day) 1f else 0.7f)
        glow(art, bank, x + bw / 2, y, bw / 2, a, squash = bh / bw)
    }
    for (i in 0 until 70) {
        val x = (rnd(i, 131) * w + t * (4f + rnd(i, 132) * 8f)) % w
        val y = rnd(i, 133) * h * 0.8f + sin(t * 0.5f + i) * 6f
        drawCircle(Color(0xFFE6ECF5).copy(alpha = 0.06f + 0.12f * rnd(i, 134)), radius = 1f + rnd(i, 135) * 1.5f, center = Offset(x, y))
    }
}

/** The weather beside the clock: now, then the next 5 days, with a warning above them when there is one. */
@Composable
fun WeatherStrip(w: WeatherNow, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val day = w.isDay(now)
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
                    Icon(skyIcon(w, now), null, Modifier.size(30.dp), tint = skyTint(w.sky, day))
                    Text("${w.temperature}°", style = Tv.text(34f, FontWeight.Light, lineHeight = 36f))
                }
                Text("${words(w.sky, day)} · feels ${w.feels}°", style = Tv.text(10.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
