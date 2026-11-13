package io.github.bornaradusin.launcher.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- Freezing: at 0° or below (with the glass effects on), icicles hang from the apps and the weather
// panel, its corners frost over, its temperature turns ice blue, and ice glitters in the air behind the apps.

/** How freezing it is: 0 above 0° (or with the glass effects off), else 0.35 to 1, colder is more. Set from Home. */
object IceCover {
    var level by mutableFloatStateOf(0f)

    /** Seconds on the ice's clock, ticking only while the icicles are on screen (for their glints). */
    var time by mutableFloatStateOf(0f)
}

/** The temperature's colour when it's freezing. */
val IceBlue = Color(0xFFADD9FF)

/**
 * Icicles hanging from the bottom edge of this: a thin wavy sheet of ice along it with drips running off
 * it, a few to a tile, unevenly, now and then a longer one with light down one side and a drop on its
 * tip. Every so often a glint slides down one of the long ones. [inset]: kept clear of the rounded corners.
 */
fun Modifier.icicles(seed: Int = 0, inset: Dp = 7.dp, longest: Dp = 14.dp): Modifier = drawWithCache {
    val level = IceCover.level
    if (level <= 0f || size.width <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val dp = 1.dp.toPx()
    val y = size.height
    val left = inset.toPx()
    val right = size.width - inset.toPx()
    val width = right - left
    if (width <= 4 * dp) return@drawWithCache onDrawWithContent { drawContent() }
    val reach = 0.75f + 0.25f * level
    // Each drip: where it hangs, how long, half its width, how it leans
    val drips = ArrayList<FloatArray>()
    var u = 0.04f + 0.08f * r01(seed, 1)
    var i = 0
    while (u < 0.97f) {
        val sd = seed * 100 + i
        val long = r01(sd, 2) > 0.78f
        val len = (if (long) longest.toPx() * (0.45f + 0.55f * r01(sd, 3)) else (2f + 4f * r01(sd, 4)) * dp) * reach
        val half = (if (long) 2.6f + 1.6f * r01(sd, 5) else 1.8f + 1.4f * r01(sd, 5)) * dp
        drips += floatArrayOf(left + width * u, len, half, (r01(sd, 6) - 0.5f) * 0.8f * dp)
        u += (9f + 16f * r01(sd, 7)) * dp / width
        i++
    }
    // One sheet of ice: a thin wavy band along the edge, the drips running off it
    fun band(x: Float) = y + (0.9f + 0.45f * sin(x / dp * 0.31f + seed) + 0.3f * sin(x / dp * 0.83f + seed * 2)) * dp
    val sheet = Path()
    sheet.moveTo(left, y - 0.6f * dp)
    var x = left
    for ((dx, len, half, lean) in drips) {
        while (x < dx - half) {
            x = min(x + 2 * dp, dx - half)
            sheet.lineTo(x, band(x))
        }
        sheet.cubicTo(dx - half * 0.45f, y + len * 0.3f, dx - half * 0.1f + lean, y + len * 0.85f, dx + lean, y + len)
        sheet.cubicTo(dx + half * 0.1f + lean, y + len * 0.85f, dx + half * 0.45f, y + len * 0.3f, dx + half, band(dx + half))
        x = dx + half
    }
    while (x < right) {
        x = min(x + 2 * dp, right)
        sheet.lineTo(x, band(x))
    }
    sheet.lineTo(right, y - 0.6f * dp)
    sheet.close()
    val deepest = drips.maxOf { it[1] }
    val fill = Brush.verticalGradient(0f to Color(0x6BD6EBFF), 1f to Color(0x1FC7E0FF), startY = y, endY = y + deepest)
    val edge = Stroke(width = 0.35f * dp)
    // Light down one side of the long drips
    val long = drips.filter { it[1] >= 5 * dp }
    val lights = Path()
    for ((dx, len, half, lean) in long) {
        lights.moveTo(dx - half * 0.28f, y + 1.2f * dp)
        lights.cubicTo(dx - half * 0.24f, y + len * 0.35f, dx - half * 0.06f + lean, y + len * 0.7f, dx + lean * 0.9f, y + len * 0.9f)
    }
    val light = Stroke(width = 0.55f * dp, cap = StrokeCap.Round)
    onDrawWithContent {
        drawContent()
        drawPath(sheet, fill)
        drawPath(sheet, Color(0x47E6F5FF), style = edge)
        if (long.isEmpty()) return@onDrawWithContent
        drawPath(lights, Color(0x80FFFFFF), style = light)
        for ((dx, len, _, lean) in long) drawCircle(Color(0x99EBF7FF), radius = 0.85f * dp, center = Offset(dx + lean, y + len + 0.3f * dp))
        // A glint sliding down one of the long drips, every 9 seconds or so (each tile at its own time)
        val t = IceCover.time + seed * 2.3f
        val phase = ((t % 9f) + 9f) % 9f
        if (phase < 1.2f) {
            val (dx, len, _, lean) = long[(floor(t / 9f).toInt() and 0x7fffffff) % long.size]
            val p = phase / 1.2f
            val shine = sin(p * PI.toFloat())
            val at = Offset(dx + lean * p * p, y + 1.5f * dp + (len - 2 * dp) * p)
            drawCircle(Color(0xFFEAF6FF).copy(alpha = 0.22f * shine), radius = 2.6f * dp, center = at)
            drawCircle(Color.White.copy(alpha = 0.9f * shine), radius = 0.9f * dp, center = at)
        }
    }
}

/**
 * Frost in the corners of a panel, behind what's on it: a mottled film gathered in each corner, small
 * six-armed crystals and grains of ice, thickest in the corners and gone well before the middle. Drawn
 * into one picture for the panel's size, again only when that or the cold changes.
 */
fun Modifier.cornerFrost(): Modifier = drawWithCache {
    val level = IceCover.level
    if (level <= 0f || size.width < 2f || size.height < 2f) return@drawWithCache onDrawBehind {}
    val pic = frostCorners(size.width.roundToInt(), size.height.roundToInt(), 1.dp.toPx(), level)
    onDrawBehind { drawImage(pic) }
}

private fun frostCorners(w: Int, h: Int, dp: Float, level: Float): ImageBitmap {
    val wd = w / dp
    val hd = h / dp
    val reach = 80f + 40f * level
    val corners = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(wd, 0f), floatArrayOf(0f, hd), floatArrayOf(wd, hd))
    // 1 in a corner, nothing from [reach] away (in dp)
    fun near(x: Float, y: Float): Float {
        var d = Float.MAX_VALUE
        for ((cx, cy) in corners) d = min(d, hypot(x - cx, y - cy))
        return max(0f, 1 - d / reach).pow(1.4f)
    }
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    // 1. The film, worked out coarsely and smoothed up to the full size
    val fw = max(2, (wd / 2.5f).toInt())
    val fh = max(2, (hd / 2.5f).toInt())
    val cells = IntArray(fw * fh)
    for (j in 0 until fh) for (i in 0 until fw) {
        val x = (i + 0.5f) * wd / fw
        val y = (j + 0.5f) * hd / fh
        val a = 0.2f * near(x, y) * (0.35f + 0.9f * fbm(x / 22f, y / 22f, 71))
        cells[j * fw + i] = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0xD8E8F8
    }
    val film = Bitmap.createBitmap(cells, fw, fh, Bitmap.Config.ARGB_8888)
    c.drawBitmap(film, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    film.recycle()
    // 2. Small six-armed crystals
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * dp
        strokeCap = Paint.Cap.ROUND
    }
    val crystals = (wd * hd / 140f).roundToInt()
    for (i in 0 until crystals) {
        val x = r01(i, 401) * wd
        val y = r01(i, 402) * hd
        if (r01(i, 403) > near(x, y) * 0.9f) continue
        val r = (1.2f + 3.2f * r01(i, 404)) * dp
        val a0 = r01(i, 405) * PI.toFloat()
        p.color = Color(0.92f, 0.97f, 1f, 0.18f + 0.3f * r01(i, 406)).toArgb()
        for (k in 0 until 3) {
            val a = a0 + k * PI.toFloat() / 3
            c.drawLine(x * dp - cos(a) * r, y * dp - sin(a) * r, x * dp + cos(a) * r, y * dp + sin(a) * r, p)
        }
    }
    // 3. Grains of ice catching the light
    val g = Paint(Paint.ANTI_ALIAS_FLAG)
    val grains = (wd * hd / 28f).roundToInt()
    for (i in 0 until grains) {
        val x = r01(i, 301) * wd
        val y = r01(i, 302) * hd
        if (r01(i, 303) > near(x, y)) continue
        val s = (0.5f + 0.7f * r01(i, 304)) * dp
        g.color = Color(0.95f, 0.98f, 1f, 0.12f + 0.25f * r01(i, 305)).toArgb()
        c.drawRect(x * dp, y * dp, x * dp + s, y * dp + s, g)
    }
    return b.asImageBitmap()
}

/** A crystal of ice catching the light: a bright point, a soft glow and four fine rays (64 pixels across). */
private val SPARKLE: ImageBitmap by lazy {
    val n = 64
    val m = n / 2f
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = RadialGradient(m, m, m * 0.6f, intArrayOf(0x73D9F0FF, 0x00D9F0FF), null, Shader.TileMode.CLAMP)
    c.drawCircle(m, m, m * 0.6f, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 1.6f
    p.strokeCap = Paint.Cap.ROUND
    p.shader = RadialGradient(m, m, m, intArrayOf(0xCCF2FAFF.toInt(), 0x00F2FAFF), null, Shader.TileMode.CLAMP)
    c.drawLine(2f, m, n - 2f, m, p)
    c.drawLine(m, 2f, m, n - 2f, p)
    p.shader = null
    p.style = Paint.Style.FILL
    p.color = 0xE6FFFFFF.toInt()
    c.drawCircle(m, m, 2.2f, p)
    b.asImageBitmap()
}

/**
 * Ice glittering in the air (diamond dust), behind the apps: fine crystals drifting slowly across and down,
 * each twinkling as it turns, a few of them catching the light with a flash of rays. More the colder it is.
 */
internal fun DrawScope.iceGlitter(t: Float, level: Float) {
    if (level <= 0f) return
    val w = size.width
    val h = size.height
    val dp = w / 960f
    val across = w + 40 * dp
    val down = h * 0.9f + 40 * dp
    val n = (150 + 130 * level).roundToInt()
    for (i in 0 until n) {
        val big = r01(i, 210) > 0.88f
        val sx = r01(i, 211) * across + t * (3f + 7f * r01(i, 212)) * dp + sin(t * (0.2f + 0.3f * r01(i, 213)) + i) * 10 * dp
        val sy = r01(i, 214) * down + t * (1.5f + 4f * r01(i, 215)) * dp
        val x = ((sx % across) + across) % across - 20 * dp
        val y = ((sy % down) + down) % down - 20 * dp
        // Twinkling as it turns: mostly faint, now and then bright
        val tw = max(0f, sin(t * (1.2f + 2.2f * r01(i, 216)) + r01(i, 217) * 6.2832f))
        if (big) {
            val a = 0.15f + 0.85f * tw.pow(3)
            if (a < 0.05f) continue
            val r = (3f + 9f * r01(i, 218)) * dp
            val k = 2 * r / 64f
            withTransform({
                translate(x, y)
                scale(k, k, Offset.Zero)
            }) {
                drawImage(SPARKLE, topLeft = Offset(-32f, -32f), alpha = a.coerceAtMost(1f))
            }
        } else {
            val a = (0.12f + 0.3f * r01(i, 219)) * (0.45f + 0.55f * tw)
            drawCircle(Color(0xFFD9EDFF).copy(alpha = a), radius = (0.5f + 0.9f * r01(i, 220)) * dp, center = Offset(x, y))
        }
    }
}
