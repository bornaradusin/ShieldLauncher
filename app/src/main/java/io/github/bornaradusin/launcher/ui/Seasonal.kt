package io.github.bornaradusin.launcher.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.sky.Extras
import io.github.bornaradusin.launcher.sky.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// ---- The extras: December's lights, Halloween, St Patrick's Day, New Year's fireworks, fog on
// the glass, settled snow, northern lights, rainbows, autumn leaves. Everything with a look of its own is
// drawn once into a small picture and then only placed each frame, so the extras cost a few draws a frame.

private const val U = 100f
private const val PI2 = 6.2831855f
private const val DEG = 57.29578f

internal fun r01(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483647f
}

private fun Color.mix(other: Color, f: Float) = lerp(this, other, f)

private fun pictureOf(w: Int, h: Int, draw: (android.graphics.Canvas) -> Unit): ImageBitmap {
    val b = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    draw(android.graphics.Canvas(b))
    return b.asImageBitmap()
}

private fun pen(c: Int = android.graphics.Color.WHITE, width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = c
    if (width > 0f) {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }
}

/** What the extras draw with: their pictures and brushes, made once. */
internal class SeasonArt {
    private val softs = HashMap<Color, Brush>()
    fun soft(c: Color): Brush = softs.getOrPut(c) { Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset.Zero, radius = U) }

    val path = Path()
    val shape = Path()
    val sprites = android.util.LongSparseArray<ImageBitmap>()
    var buntingFor = 0f
    var washFor = 0f
    var washWarm = false
    var wash: Brush? = null
    var buntingShade: Brush? = null
    var buntingLine: Stroke? = null
    var garland: Pair<Float, ImageBitmap>? = null
    var webs: Pair<Float, Pair<ImageBitmap, ImageBitmap>>? = null
    var aurora: ImageBitmap? = null
    var swagsFor = 0f
    var swags: List<Swag> = emptyList()
    val sparks = FloatArray(4 * 80)
    val heads = FloatArray(2 * 80)
    val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val bandStrokes = HashMap<Int, Stroke>()

    /** A small picture of [kind] (and [index]) at [size], drawn the first time it's needed. */
    inline fun sprite(kind: Int, index: Int, size: Float, make: () -> ImageBitmap): ImageBitmap {
        val key = (kind.toLong() shl 56) or (index.toLong() shl 40) or (size * 100).toLong()
        sprites.get(key)?.let { return it }
        return make().also { sprites.put(key, it) }
    }

    /** The big pictures go when the launcher's out of sight; the small ones stay. */
    fun trim() {
        garland = null
        webs = null
        aurora = null
    }
}

internal val SEASON_ART = SeasonArt()

private fun DrawScope.soft(art: SeasonArt, c: Color, x: Float, y: Float, r: Float, alpha: Float, squash: Float = 1f, mode: BlendMode = BlendMode.SrcOver) {
    if (alpha <= 0.004f || r <= 0.5f) return
    val k = r / U
    withTransform({
        translate(x, y)
        scale(k, k * squash, Offset.Zero)
    }) {
        drawCircle(art.soft(c), radius = U, center = Offset.Zero, alpha = alpha.coerceAtMost(1f), blendMode = mode)
    }
}

private fun DrawScope.sprite(img: ImageBitmap, x: Float, y: Float, ox: Float, oy: Float, scale: Float, degrees: Float, alpha: Float, mode: BlendMode = BlendMode.SrcOver, flip: Float = 1f) {
    if (alpha <= 0.004f) return
    withTransform({
        translate(x, y)
        if (degrees != 0f) rotate(degrees, Offset.Zero)
        scale(scale * flip, scale, Offset.Zero)
    }) {
        drawImage(img, topLeft = Offset(-ox, -oy), alpha = alpha.coerceIn(0f, 1f), blendMode = mode)
    }
}

// ---- Where the string hangs: shallow over the tabs and the buttons, deep over the empty middle

internal class Swag(val x0: Float, val x1: Float, val sag: Float, val edge: Boolean)

private fun swagsFor(art: SeasonArt, w: Float): List<Swag> {
    if (art.swagsFor != w) {
        val s = w / 1920f
        val pts = floatArrayOf(-24f, 220f, 440f, 735f, 1030f, 1325f, 1580f, 1760f, 1944f).map { it * s }
        art.swags = (1 until pts.size).map { i ->
            val mid = (pts[i - 1] + pts[i]) / 2
            val edge = mid < 440f * s || mid > 1580f * s
            Swag(pts[i - 1], pts[i], (if (edge) 14f else 78f) * s, edge)
        }
        art.swagsFor = w
    }
    return art.swags
}

private fun Swag.x(u: Float) = x0 + (x1 - x0) * u
private fun Swag.y(u: Float, s: Float) = -10f * s + sag * 4 * u * (1 - u)

// ---- December: a pine garland along the top, fairy lights woven in, baubles swinging, light glowing down

private val BULB_COLOURS = listOf(Color(0xFFFF3B3B), Color(0xFF2FD65F), Color(0xFF3E8BFF), Color(0xFFFFB52E), Color(0xFFFF52D0))
private val WARM_COLOURS = listOf(Color(0xFFFFD29A), Color(0xFFFFE4BC))
private val BAUBLE_AT = floatArrayOf(0.5f, 0.27f, 0.73f)
private val BAT_RIM = Stroke(width = 0.9f)
private val BAUBLE_COLOURS = listOf(Color(0xFFD7263D), Color(0xFFE6B422), Color(0xFFC8CDD6), Color(0xFF1E8E4E), Color(0xFF3557C9))

private fun garlandPicture(w: Float): ImageBitmap {
    val s = w / 1920f
    val swags = run {
        val pts = floatArrayOf(-24f, 220f, 440f, 735f, 1030f, 1325f, 1580f, 1760f, 1944f).map { it * s }
        (1 until pts.size).map { i ->
            val mid = (pts[i - 1] + pts[i]) / 2
            Swag(pts[i - 1], pts[i], (if (mid < 440f * s || mid > 1580f * s) 14f else 78f) * s, false)
        }
    }
    val greens = intArrayOf(0xFF0E2A17.toInt(), 0xFF143A20.toInt(), 0xFF1B4A29.toInt(), 0xFF24603A.toInt(), 0xFF2E7445.toInt())
    return pictureOf(w.roundToInt(), (200 * s).roundToInt()) { c ->
        val p = pen(width = 2f)
        var n = 0
        swags.forEach { sw ->
            val steps = ((sw.x1 - sw.x0) / (5 * s)).roundToInt()
            for (k in 0..steps) {
                val u = k.toFloat() / steps
                val x = sw.x(u)
                val y = sw.y(u, s)
                for (j in 0 until 7) {
                    val a = r01(n * 7 + j, 61) * PI2
                    val len = (9 + r01(n * 7 + j, 62) * 13) * s
                    val depth = r01(n * 7 + j, 63)
                    p.color = greens[(depth * greens.size).toInt().coerceIn(0, greens.size - 1)]
                    p.strokeWidth = (1.6f + depth) * s
                    c.drawLine(x, y, x + cos(a) * len, y + sin(a) * len * 0.8f, p)
                }
                n++
            }
        }
    }
}

private fun haloPicture(c: Color, r: Float): ImageBitmap = pictureOf((r * 2).roundToInt(), (r * 2).roundToInt()) { cv ->
    val p = pen()
    p.shader = RadialGradient(r, r, r, intArrayOf(c.copy(alpha = 0.55f).toArgb(), c.copy(alpha = 0.2f).toArgb(), c.copy(alpha = 0f).toArgb()), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
    cv.drawCircle(r, r, r, p)
}

/** A bulb hanging from its socket; the socket's top is at (5B, 1B) in the picture. */
private fun bulbPicture(c: Color, b: Float): ImageBitmap = pictureOf((10 * b).roundToInt(), (23 * b).roundToInt()) { cv ->
    cv.translate(5 * b, 1 * b)
    val p = pen(0xFF183320.toInt())
    cv.drawRoundRect(-3.2f * b, -1f * b, 3.2f * b, 6f * b, 1.5f * b, 1.5f * b, p)
    val body = android.graphics.Path().apply {
        moveTo(-3.6f * b, 6f * b)
        cubicTo(-4.8f * b, 11f * b, -2.6f * b, 18.5f * b, 0f, 20.5f * b)
        cubicTo(2.6f * b, 18.5f * b, 4.8f * b, 11f * b, 3.6f * b, 6f * b)
        close()
    }
    p.shader = RadialGradient(0f, 12f * b, 8f * b, intArrayOf(Color.White.mix(c, 0.3f).toArgb(), c.toArgb(), c.mix(Color.Black, 0.4f).toArgb()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    cv.drawPath(body, p)
    p.shader = null
    p.color = Color.White.copy(alpha = 0.6f).toArgb()
    cv.drawOval(-2.1f * b, 8f * b, -0.5f * b, 12f * b, p)
}

private fun baublePicture(c: Color, r: Float): ImageBitmap = pictureOf((r * 2 + 4).roundToInt(), (r * 2 + r * 0.5f + 4).roundToInt()) { cv ->
    val cx = r + 2
    val cy = r * 0.5f + r + 2
    val p = pen(0xFFC9A44A.toInt())
    cv.drawRect(cx - r * 0.2f, cy - r - r * 0.28f, cx + r * 0.2f, cy - r + 1, p)
    p.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.3f, intArrayOf(c.mix(Color.White, 0.55f).toArgb(), c.toArgb(), c.mix(Color.Black, 0.6f).toArgb()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    cv.drawCircle(cx, cy, r, p)
    p.shader = null
    p.color = Color.White.copy(alpha = 0.75f).toArgb()
    cv.save()
    cv.rotate(-34f, cx - r * 0.38f, cy - r * 0.42f)
    cv.drawOval(cx - r * 0.56f, cy - r * 0.53f, cx - r * 0.2f, cy - r * 0.31f, p)
    cv.restore()
}

private fun DrawScope.december(art: SeasonArt, e: Extras, t: Float) {
    val w = size.width
    val s = w / 1920f
    val gust = (e.wind / 40f).coerceIn(0.25f, 1.6f)
    // The light thrown down from the string onto the top of the screen
    val tint = if (e.warmLights) Color(0xFFFFC27A) else Color(0xFFFF7A5C)
    if (art.washFor != w || art.washWarm != e.warmLights) {
        art.wash = Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f), tint.copy(alpha = 0f)), endY = 220 * s)
        art.washFor = w
        art.washWarm = e.warmLights
    }
    art.wash?.let { drawRect(it, size = Size(w, 220 * s), blendMode = BlendMode.Plus) }
    val g = art.garland?.takeIf { it.first == w }?.second ?: garlandPicture(w).also { art.garland = w to it }
    drawImage(g, topLeft = Offset.Zero)
    val swags = swagsFor(art, w)
    // Baubles: one big at the bottom of each deep swag, a smaller each side, swinging in the wind
    swags.forEachIndexed { i, sw ->
        if (sw.edge) return@forEachIndexed
        for (j in 0 until 3) {
            val u = BAUBLE_AT[j]
            val x = sw.x(u)
            val y = sw.y(u, s)
            val len = (if (j == 0) 46f else 24f) * s
            val r = (if (j == 0) 17f else 12f) * s
            val ang = sin(t * (0.9f + i * 0.13f + j * 0.2f) + i * 2 + j) * 0.09f * (0.6f + gust)
            val bx = x + sin(ang) * len
            val by = y + cos(ang) * len
            val ci = (i * 3 + j) % BAUBLE_COLOURS.size
            drawLine(Color(0x99E6C88C), Offset(x, y), Offset(bx, by - r), strokeWidth = s)
            val big = 17f * s
            val pic = art.sprite(1, ci, big) { baublePicture(BAUBLE_COLOURS[ci], big) }
            sprite(pic, bx, by, big + 2, big * 1.5f + 2, r / big, ang * DEG, 1f)
        }
    }
    // The bulbs, each twinkling at its own pace, with now and then a wave of light running along the string
    val b = 1.5f * s
    val palette = if (e.warmLights) WARM_COLOURS else BULB_COLOURS
    val wave = (t % 30f) * 800f * s - 300f * s
    var n = 0
    swags.forEachIndexed { _, sw ->
        val count = max(3, ((sw.x1 - sw.x0) / (50 * s)).roundToInt())
        for (k in 0 until count) {
            val u = (k + 0.5f) / count
            val x = sw.x(u)
            val y = sw.y(u, s)
            val slope = sw.sag * 4 * (1 - 2 * u) / (sw.x1 - sw.x0)
            val ang = atan(slope) * 0.5f + (r01(n, 85) - 0.5f) * 0.5f + sin(t * 1.3f + n) * 0.04f * gust
            val ci = if (e.warmLights) (if (n % 3 == 0) 1 else 0) else n % palette.size
            val col = palette[ci]
            val tw = 0.66f + 0.24f * sin(t * (0.7f + r01(n, 81) * 1.4f) + r01(n, 82) * 6.3f) + 0.1f * sin(t * (2.3f + r01(n, 83) * 2) + r01(n, 84) * 6.3f)
            val run = exp(-((x - wave) / (110 * s)).pow(2)) * 0.5f
            val bright = min(1f, tw + run)
            val kind = if (e.warmLights) 4 else 2
            val halo = art.sprite(kind, ci, b) { haloPicture(col, 34 * b) }
            val bulb = art.sprite(kind + 1, ci, b) { bulbPicture(col, b) }
            withTransform({
                translate(x, y)
                rotate(ang * DEG, Offset.Zero)
            }) {
                drawImage(halo, topLeft = Offset(-34 * b, 13 * b - 34 * b), alpha = bright, blendMode = BlendMode.Plus)
                drawImage(bulb, topLeft = Offset(-5 * b, -1 * b), alpha = 0.6f + 0.4f * bright)
            }
            n++
        }
    }
}

/** Gentle snow on December days that aren't wet or snowing already. */
private fun DrawScope.festiveSnow(art: SeasonArt, t: Float) {
    val w = size.width
    val h = size.height
    val s = w / 1920f
    for (i in 0 until 70) {
        val v = (22 + r01(i, 71) * 30) * s
        val r = (1.4f + r01(i, 72) * 2.8f) * s
        val span = h + 20 * s
        val y = ((t * v + r01(i, 73) * span) % span) - 10 * s
        val x = r01(i, 74) * w + sin(t * (0.4f + r01(i, 75) * 0.5f) + i) * 22 * s
        val a = (0.35f + 0.45f * r01(i, 76)) * (0.75f + 0.25f * sin(t * 2 + i))
        soft(art, Color.White, x, y, r * 2.2f, a)
    }
}

// ---- Halloween: cobwebs in the corners and a spider on its thread, bats, mist, pumpkins on the weather

private fun webPicture(r: Float, s: Float): ImageBitmap = pictureOf((r + 4).roundToInt(), (r + 4).roundToInt()) { c ->
    val p = pen(Color(0x66E4E8F2).toArgb(), 1.5f * s)
    val spokes = floatArrayOf(3f, 16f, 30f, 45f, 60f, 74f, 87f).map { it / DEG }
    spokes.forEach { a -> c.drawLine(0f, 0f, cos(a) * r, sin(a) * r, p) }
    p.strokeWidth = 1.2f * s
    var rr = 18f * s
    while (rr < r) {
        val path = android.graphics.Path()
        spokes.forEachIndexed { k, a ->
            val x = cos(a) * rr
            val y = sin(a) * rr
            if (k == 0) path.moveTo(x, y) else {
                val m = (a + spokes[k - 1]) / 2
                path.quadTo(cos(m) * rr * 0.86f, sin(m) * rr * 0.86f, x, y)
            }
        }
        c.drawPath(path, p)
        rr += 15f * s
    }
}

private fun pumpkinPicture(r: Float): ImageBitmap = pictureOf((r * 2.2f).roundToInt(), (r * 2.1f).roundToInt()) { c ->
    val cx = r * 1.1f
    val cy = r * 1.25f
    val rx = r
    val ry = r * 0.78f
    val p = pen()
    floatArrayOf(-0.62f, 0.62f, -0.3f, 0.3f, 0f).forEachIndexed { i, o ->
        val wv = floatArrayOf(0.5f, 0.5f, 0.62f, 0.62f, 0.68f)[i]
        p.shader = RadialGradient(cx + o * rx - rx * 0.15f, cy - ry * 0.3f, ry * 1.1f, intArrayOf(0xFFFFA640.toInt(), 0xFFE2701A.toInt(), 0xFF8E3A0A.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx + o * rx - rx * wv, cy - ry, cx + o * rx + rx * wv, cy + ry, p)
    }
    p.shader = null
    p.color = 0xFF4A5A22.toInt()
    c.drawPath(android.graphics.Path().apply {
        moveTo(cx - r * 0.08f, cy - ry * 0.92f)
        quadTo(cx + r * 0.05f, cy - ry * 1.35f, cx + r * 0.25f, cy - ry * 1.3f)
        lineTo(cx + r * 0.12f, cy - ry * 0.88f)
        close()
    }, p)
    // The carved face, dark until the candle's drawn in it
    p.color = 0xFF2A1204.toInt()
    c.drawPath(faceAndroid(cx, cy, rx, ry), p)
}

private fun faceAndroid(cx: Float, cy: Float, rx: Float, ry: Float) = android.graphics.Path().apply {
    moveTo(cx - rx * 0.5f, cy - ry * 0.05f); lineTo(cx - rx * 0.32f, cy - ry * 0.42f); lineTo(cx - rx * 0.14f, cy - ry * 0.05f); close()
    moveTo(cx + rx * 0.5f, cy - ry * 0.05f); lineTo(cx + rx * 0.32f, cy - ry * 0.42f); lineTo(cx + rx * 0.14f, cy - ry * 0.05f); close()
    moveTo(cx - rx * 0.07f, cy + ry * 0.12f); lineTo(cx, cy - ry * 0.02f); lineTo(cx + rx * 0.07f, cy + ry * 0.12f); close()
    moveTo(cx - rx * 0.55f, cy + ry * 0.25f)
    for (k in 0..6) lineTo(cx - rx * 0.55f + k * rx * 0.183f, cy + ry * (if (k % 2 == 1) 0.36f else 0.25f))
    quadTo(cx, cy + ry * 0.9f, cx - rx * 0.55f, cy + ry * 0.25f)
    close()
}

private fun Path.face(cx: Float, cy: Float, rx: Float, ry: Float) {
    reset()
    moveTo(cx - rx * 0.5f, cy - ry * 0.05f); lineTo(cx - rx * 0.32f, cy - ry * 0.42f); lineTo(cx - rx * 0.14f, cy - ry * 0.05f); close()
    moveTo(cx + rx * 0.5f, cy - ry * 0.05f); lineTo(cx + rx * 0.32f, cy - ry * 0.42f); lineTo(cx + rx * 0.14f, cy - ry * 0.05f); close()
    moveTo(cx - rx * 0.07f, cy + ry * 0.12f); lineTo(cx, cy - ry * 0.02f); lineTo(cx + rx * 0.07f, cy + ry * 0.12f); close()
    moveTo(cx - rx * 0.55f, cy + ry * 0.25f)
    for (k in 0..6) lineTo(cx - rx * 0.55f + k * rx * 0.183f, cy + ry * (if (k % 2 == 1) 0.36f else 0.25f))
    quadraticTo(cx, cy + ry * 0.9f, cx - rx * 0.55f, cy + ry * 0.25f)
    close()
}

/** A jack-o'-lantern sitting at ([x], [bottom]), [r] across, its candle flickering. */
private fun DrawScope.pumpkin(art: SeasonArt, x: Float, bottom: Float, r: Float, t: Float, seed: Int) {
    val flick = 0.75f + 0.15f * sin(t * 9 + seed) + 0.1f * sin(t * 23 + seed * 3)
    soft(art, Color(0xFFFF9628), x, bottom - r * 0.7f, r * 3.2f, 0.35f * flick, mode = BlendMode.Plus)
    val pic = art.sprite(6, 0, r) { pumpkinPicture(r) }
    val left = x - r * 1.1f
    val top = bottom - r * 1.25f - r * 0.78f
    drawImage(pic, topLeft = Offset(left, top))
    art.shape.face(left + r * 1.1f, top + r * 1.25f, r, r * 0.78f)
    drawPath(art.shape, Color(1f, (190 + 40 * flick) / 255f, (60 + 60 * flick) / 255f, (0.8f + 0.2f * flick).coerceAtMost(1f)))
}

private fun DrawScope.web(art: SeasonArt) {
    val w = size.width
    val s = w / 1920f
    val webs = art.webs?.takeIf { it.first == w }?.second ?: (webPicture(200 * s, s) to webPicture(120 * s, s)).also { art.webs = w to it }
    drawImage(webs.first, topLeft = Offset.Zero)
    withTransform({
        translate(w, 0f)
        scale(-1f, 1f, Offset.Zero)
    }) { drawImage(webs.second, topLeft = Offset.Zero) }
}

private fun DrawScope.spider(art: SeasonArt, t: Float) {
    val s = size.width / 1920f
    val a = 74f / DEG
    val ax = cos(a) * 200 * s * 0.6f
    val ay = sin(a) * 200 * s * 0.6f
    val drop = (110 + 90 * (0.5f - 0.5f * cos(t * 0.35f))) * s
    val sx = ax + sin(t * 0.9f) * 3 * s
    val sy = ay + drop
    drawLine(Color(0x73E4E8F2), Offset(ax, ay), Offset(sx, sy - 14 * s), strokeWidth = s)
    val k = 2.4f * s
    val leg = Color(0xFF2E2E3A)
    for (j in 0..1) for (l in 0 until 4) {
        val side = j * 2 - 1
        val kick = sin(t * 3 + l) * 0.6f * k
        val y0 = (-2 + l * 1.6f) * k
        val mid = Offset(sx + side * 7 * k, sy + y0 - (5 - l * 2) * k + kick)
        val end = Offset(sx + side * 10 * k, sy + y0 + (l - 1.5f) * 4 * k + kick)
        drawLine(leg, Offset(sx, sy + y0), mid, strokeWidth = 1.3f * k, cap = StrokeCap.Round)
        drawLine(leg, mid, end, strokeWidth = 1.3f * k, cap = StrokeCap.Round)
    }
    drawOval(Color(0xFF24242E), topLeft = Offset(sx - 4.2f * k, sy - 3 * k), size = Size(8.4f * k, 10 * k))
    drawCircle(Color(0xFF24242E), radius = 2.8f * k, center = Offset(sx, sy - 4 * k))
    drawCircle(Color(0xFFFF6A1A), radius = 0.6f * k, center = Offset(sx - k, sy - 4.4f * k))
    drawCircle(Color(0xFFFF6A1A), radius = 0.6f * k, center = Offset(sx + k, sy - 4.4f * k))
}

/** A bat, [size] across, wings at [flap] (−1 down to 1 up), facing [dir]. */
private fun DrawScope.bat(art: SeasonArt, x: Float, y: Float, size: Float, flap: Float, dir: Float) {
    val p = art.path
    p.reset()
    for (j in 0..1) {
        val side = j * 2 - 1
        val f = flap
        p.moveTo(0f, -2f)
        p.quadraticTo(side * 12f, -10 - f * 12, side * 25f, -6 - f * 18)
        p.quadraticTo(side * 22f, 0 - f * 9, side * 18f, 3 - f * 7)
        p.quadraticTo(side * 15f, -1 - f * 5, side * 11f, 5 - f * 3)
        p.quadraticTo(side * 8f, 1 - f * 2, side * 4f, 5f)
        p.lineTo(0f, 3f)
        p.close()
    }
    p.addOval(androidx.compose.ui.geometry.Rect(-3.6f, -5f, 3.6f, 7f))
    p.moveTo(-2.6f, -3f); p.lineTo(-2.2f, -8f); p.lineTo(-0.8f, -4.5f); p.lineTo(0.8f, -4.5f); p.lineTo(2.2f, -8f); p.lineTo(2.6f, -3f); p.close()
    val k = size / 50f
    withTransform({
        translate(x, y)
        scale(k * dir, k, Offset.Zero)
    }) {
        drawPath(p, Color(0xFF07070B))
        drawPath(p, Color(0x4DAAB4D2), style = BAT_RIM)
    }
}

private fun DrawScope.bats(art: SeasonArt, t: Float, near: Boolean) {
    val w = size.width
    val s = w / 1920f
    val every = 18f
    val cycle = floor(t / every).toInt()
    val p = (t % every) / 9f
    if (p > 1.3f) return
    val dir = if (r01(cycle, 91) > 0.5f) 1f else -1f
    val n = 4 + (r01(cycle, 92) * 4).toInt()
    for (i in 0 until n) {
        val sd = cycle * 10 + i
        val big = r01(sd, 96) > 0.72f
        if (big != near) continue
        val q = (p - r01(sd, 93) * 0.22f) * (if (big) 1.25f else 1f)
        if (q < 0 || q > 1) continue
        val span = w + 300 * s
        val x = if (dir > 0) -150 * s + q * span else w + 150 * s - q * span
        val y = (60 + r01(sd, 94) * (if (big) 380 else 240)) * s + sin(q * 9 + i * 2) * 30 * s + sin(q * 23 + i) * 10 * s
        val sz = (if (big) 110 + r01(sd, 95) * 40 else 50 + r01(sd, 95) * 30) * s
        bat(art, x, y, sz, sin(t * (if (big) 11f else 13f + i * 1.3f) + i), dir)
    }
}

private fun DrawScope.halloweenSky(art: SeasonArt, t: Float, day: Boolean) {
    val w = size.width
    val h = size.height
    val s = w / 1920f
    soft(art, Color(0xFFFF7A1A), w * 0.78f, h * 0.08f, w * 0.45f, if (day) 0.10f else 0.16f, mode = BlendMode.Plus)
    soft(art, Color(0xFF8A3CFF), w * 0.2f, h * 0.05f, w * 0.4f, if (day) 0.06f else 0.14f, mode = BlendMode.Plus)
    web(art)
    spider(art, t)
    // Mist drifting low between the clock and the apps row
    for (i in 0 until 7) {
        val v = (14 + r01(i, 51) * 16) * s
        val span = w + 900 * s
        val x = ((t * v + r01(i, 52) * span) % span) - 450 * s
        val y = (380 + r01(i, 53) * 120) * s
        soft(art, Color(0xFFBEC8E1), x, y, 420 * s, 0.10f, squash = 0.22f)
    }
    bats(art, t, near = false)
}

// ---- St Patrick's Day: bunting in green, white and orange, shamrocks drifting down, a green and gold glow

private val PADDY = listOf(Color(0xFF169B62), Color(0xFFF4F4F0), Color(0xFFFF883E))
private val SHAMROCK_GREENS = listOf(Color(0xFF1FA35C), Color(0xFF2DBF6C), Color(0xFF148A4A), Color(0xFF3ACF7A))

private fun shamrockPicture(c: Color, r: Float): ImageBitmap = pictureOf((r * 2.6f).roundToInt(), (r * 2.8f).roundToInt()) { cv ->
    cv.translate(r * 1.3f, r * 1.25f)
    val p = pen(c.toArgb())
    for (k in 0 until 3) {
        cv.save()
        cv.rotate(k * 120f - 90f + 90f)
        val leaf = android.graphics.Path().apply {
            moveTo(0f, 0f)
            cubicTo(r * 0.15f, -r * 0.35f, r * 0.75f, -r * 0.55f, r * 0.55f, -r * 0.95f)
            cubicTo(r * 0.4f, -r * 1.15f, r * 0.08f, -r * 1.05f, 0f, -r * 0.8f)
            cubicTo(-r * 0.08f, -r * 1.05f, -r * 0.4f, -r * 1.15f, -r * 0.55f, -r * 0.95f)
            cubicTo(-r * 0.75f, -r * 0.55f, -r * 0.15f, -r * 0.35f, 0f, 0f)
            close()
        }
        cv.drawPath(leaf, p)
        cv.restore()
    }
    val stem = pen(c.toArgb(), r * 0.14f)
    cv.drawPath(android.graphics.Path().apply { moveTo(0f, 0f); quadTo(r * 0.2f, r * 0.6f, r * 0.05f, r * 1.1f) }, stem)
}

private fun DrawScope.paddySky(art: SeasonArt, t: Float, day: Boolean, wind: Float) {
    val w = size.width
    val h = size.height
    val s = w / 1920f
    soft(art, Color(0xFF22C46F), w * 0.2f, h * 0.06f, w * 0.45f, if (day) 0.08f else 0.14f, mode = BlendMode.Plus)
    soft(art, Color(0xFFFFC24A), w * 0.82f, h * 0.1f, w * 0.35f, if (day) 0.05f else 0.1f, mode = BlendMode.Plus)
    val big = 16f * s
    for (i in 0 until 26) {
        val v = (30 + r01(i, 41) * 40) * s
        val span = h + 80 * s
        val y = ((t * v + r01(i, 42) * span) % span) - 40 * s
        val x = (((r01(i, 43) * w + sin(t * 0.6f + i) * 30 * s + t * wind * 0.6f * s) % w) + w) % w
        val r = (9 + r01(i, 44) * 12) * s
        val ci = i % SHAMROCK_GREENS.size
        val pic = art.sprite(7, ci, big) { shamrockPicture(SHAMROCK_GREENS[ci], big) }
        val turn = cos(t * (0.8f + r01(i, 47)) + i)
        sprite(pic, x, y, big * 1.3f, big * 1.25f, r / big, (t * (0.5f + r01(i, 46)) + i) * DEG, 0.55f + 0.35f * r01(i, 45), flip = sign(turn).let { if (it == 0f) 1f else it } * (0.3f + 0.7f * abs(turn)))
    }
    for (i in 0 until 40) {
        val a = max(0f, sin(t * (1 + r01(i, 51) * 2) + i * 2))
        if (a > 0.7f) soft(art, Color(0xFFFFD36A), r01(i, 52) * w, r01(i, 53) * h * 0.5f, 5 * s, (a - 0.7f) * 2)
    }
}

private fun DrawScope.bunting(art: SeasonArt, t: Float, wind: Float) {
    val w = size.width
    val s = w / 1920f
    val sway = min(1f, wind / 40f)
    val pw = 30 * s
    val ph = 38 * s
    val tri = art.shape
    tri.reset()
    tri.moveTo(-pw / 2, 0f)
    tri.lineTo(pw / 2, 0f)
    tri.lineTo(0f, ph)
    tri.close()
    if (art.buntingFor != w) {
        art.buntingShade = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.28f)), start = Offset(-pw / 2, 0f), end = Offset(pw / 2, ph))
        art.buntingLine = Stroke(width = 1.6f * s)
        art.buntingFor = w
    }
    val shade = art.buntingShade ?: return
    val string = art.buntingLine ?: return
    swagsFor(art, w).forEachIndexed { i, sw ->
        val line = art.path
        line.reset()
        for (k in 0..24) {
            val u = k / 24f
            if (k == 0) line.moveTo(sw.x(u), sw.y(u, s)) else line.lineTo(sw.x(u), sw.y(u, s))
        }
        drawPath(line, Color(0xBFE6E1D2), style = string)
        val count = max(3, ((sw.x1 - sw.x0) / (46 * s)).roundToInt())
        for (k in 0 until count) {
            val u = (k + 0.5f) / count
            val n = i * 20 + k
            val flap = sin(t * (2 + 3 * sway) + n * 0.9f) * (0.06f + 0.22f * sway)
            withTransform({
                translate(sw.x(u), sw.y(u, s))
                rotate(flap * DEG, Offset.Zero)
            }) {
                drawPath(tri, PADDY[n % 3])
                drawPath(tri, shade)
            }
        }
    }
}

// ---- Autumn: leaves blown across on windy days

private val LEAF_COLOURS = listOf(Color(0xFFC8501E), Color(0xFFE08A1E), Color(0xFFA63A1A), Color(0xFFD9A52B), Color(0xFF8A4A1C), Color(0xFFB8661E))

private fun leafPicture(c: Color, r: Float, maple: Boolean): ImageBitmap = pictureOf((r * 2.4f).roundToInt(), (r * 2.6f).roundToInt()) { cv ->
    cv.translate(r * 1.2f, r * 1.2f)
    val outline = android.graphics.Path()
    if (maple) {
        for (k in 0..60) {
            val th = k / 60f * PI2 - PI2 / 4
            val lobe = abs(cos(2.5f * (th + PI2 / 4))).pow(0.7f)
            val rr = r * (0.5f + 0.5f * lobe) * (if (th > PI2 * 0.175f && th < PI2 * 0.325f) 0.55f else 1f)
            if (k == 0) outline.moveTo(cos(th) * rr, sin(th) * rr) else outline.lineTo(cos(th) * rr, sin(th) * rr)
        }
        outline.close()
    } else {
        outline.moveTo(0f, -r)
        outline.cubicTo(r * 0.7f, -r * 0.55f, r * 0.6f, r * 0.45f, 0f, r * 0.8f)
        outline.cubicTo(-r * 0.6f, r * 0.45f, -r * 0.7f, -r * 0.55f, 0f, -r)
        outline.close()
    }
    val p = pen()
    p.shader = LinearGradient(-r, -r, r, r, c.mix(Color.White, 0.18f).toArgb(), c.mix(Color.Black, 0.3f).toArgb(), Shader.TileMode.CLAMP)
    cv.drawPath(outline, p)
    val vein = pen(c.mix(Color.Black, 0.45f).toArgb(), max(1f, r * 0.05f))
    cv.drawLine(0f, -r * 0.8f, 0f, r * 1.15f, vein)
    if (!maple) for (k in 1..3) {
        val yy = -r * 0.6f + k * r * 0.35f
        cv.drawLine(0f, yy, r * 0.35f, yy - r * 0.2f, vein)
        cv.drawLine(0f, yy, -r * 0.35f, yy - r * 0.2f, vein)
    }
}

private fun DrawScope.leaves(art: SeasonArt, t: Float, wind: Float, near: Boolean) {
    val w = size.width
    val h = size.height
    val s = w / 1920f
    val gust = (wind / 40f).coerceIn(0.6f, 1.6f)
    val big = 30f * s
    val n = if (near) 3 else 22
    for (i in 0 until n) {
        val sd = (if (near) 500 else 0) + i
        val v = (160 + r01(sd, 31) * 220) * s * gust * (if (near) 1.6f else 1f)
        val span = w + 300 * s
        val x = ((t * v + r01(sd, 32) * span) % span) - 150 * s
        val y = h * (if (near) 0.15f + r01(sd, 33) * 0.6f else 0.05f + r01(sd, 33) * 0.7f) + sin(t * (1.2f + r01(sd, 34)) + i) * 40 * s + sin(x * 0.004f + i) * 30 * s
        val r = (if (near) 44 + r01(sd, 35) * 20 else 17 + r01(sd, 35) * 12) * s
        val ci = i % LEAF_COLOURS.size
        val maple = i % 3 == 0
        val pic = art.sprite(8, ci * 2 + (if (maple) 1 else 0), big) { leafPicture(LEAF_COLOURS[ci], big, maple) }
        val turn = cos(t * (1.6f + r01(sd, 38) * 1.6f) + i)
        val spin = t * (1.2f + r01(sd, 36) * 2) * (if (r01(sd, 37) > 0.5f) 1 else -1) + i
        sprite(pic, x, y, big * 1.2f, big * 1.2f, r / big, spin * DEG, 1f, flip = (if (turn < 0) -1f else 1f) * (0.35f + 0.65f * abs(turn)))
    }
}

// ---- Northern lights: green curtains with pink tops, rippling slowly across the top of the night

private fun auroraPicture(): ImageBitmap {
    val w = 960
    val h = 330
    return pictureOf(w, h) { c ->
        val p = pen()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        p.maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
        var x = 0
        while (x < w) {
            val ray = 0.5f + 0.5f * sin(x * 0.14f) * sin(x * 0.026f + 1f)
            val a = 0.09f + 0.22f * ray
            val top = h * (0.08f + 0.1f * r01(x, 7))
            p.shader = LinearGradient(
                0f, top, 0f, h.toFloat(),
                intArrayOf(Color(0x00BE5ADC).toArgb(), Color(0.78f, 0.39f, 0.9f, a * 0.6f).toArgb(), Color(0.27f, 1f, 0.59f, a).toArgb(), Color(0x0028DC8C).toArgb()),
                floatArrayOf(0f, 0.2f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(x.toFloat(), top, x + 5f, h.toFloat(), p)
            x += 3
        }
    }
}

private fun DrawScope.aurora(art: SeasonArt, t: Float, level: Float) {
    val w = size.width
    val h = size.height
    val pic = art.aurora ?: auroraPicture().also { art.aurora = it }
    val slices = 48
    val sw = pic.width / slices
    val dw = w / slices
    for (b in 0 until 2) {
        for (i in 0 until slices) {
            val x = i * dw
            val y = h * (0.0f + b * 0.06f) + h * 0.04f * sin(x * 0.0032f + t * (0.18f + b * 0.05f) + b * 2) + h * 0.02f * sin(x * 0.009f - t * 0.31f + b)
            val a = level * (0.7f + 0.3f * sin(x * 0.011f + t * 0.4f + b * 1.7f)) * (if (b == 0) 1f else 0.7f)
            // Side by side to the pixel: slices that overlap add up into a brighter line where they meet
            val x0 = x.roundToInt()
            val x1 = ((i + 1) * dw).roundToInt()
            drawImage(
                pic, srcOffset = IntOffset(((i + b * 17) % slices) * sw, 0), srcSize = IntSize(sw, pic.height),
                dstOffset = IntOffset(x0, y.toInt()), dstSize = IntSize(x1 - x0, (h * 0.6f).toInt()),
                alpha = a.coerceIn(0f, 1f), blendMode = BlendMode.Plus,
            )
        }
    }
}

// ---- A rainbow, as the sun comes out after rain, opposite the sun

private val BOW = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFD60A), Color(0xFF34C759), Color(0xFF30B0FF), Color(0xFF5856D6), Color(0xFFAF52DE))

private fun DrawScope.rainbow(art: SeasonArt, level: Float, sunX: Float) {
    val w = size.width
    val h = size.height
    val cx = w * (1f - sunX).coerceIn(0.2f, 0.8f)
    val cy = h * 1.15f
    val r = h * 0.98f
    val s = w / 1920f
    val inner = art.bandStrokes.getOrPut((9 * s * 100).toInt()) { Stroke(width = 9 * s) }
    val outer = art.bandStrokes.getOrPut((11 * s * 100).toInt() + 1) { Stroke(width = 11 * s) }
    BOW.forEachIndexed { i, c ->
        val ri = r - i * 9 * s
        drawArc(c.copy(alpha = 0.16f * level), 194f, 152f, false, topLeft = Offset(cx - ri, cy - ri), size = Size(ri * 2, ri * 2), style = inner, blendMode = BlendMode.Plus)
        val ro = r * 1.17f + i * 11 * s
        drawArc(c.copy(alpha = 0.05f * level), 198f, 144f, false, topLeft = Offset(cx - ro, cy - ro), size = Size(ro * 2, ro * 2), style = outer, blendMode = BlendMode.Plus)
    }
    soft(art, Color.White, cx, cy, r * 0.95f, 0.04f * level)
}

// ---- Fireworks: rockets climbing behind the rows, bursting, sparks falling and fading

private val FIREWORK = intArrayOf(0xFFFF4D4D.toInt(), 0xFFFFD24D.toInt(), 0xFF4DD2FF.toInt(), 0xFF7CFF6B.toInt(), 0xFFFF6BEF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF9F40.toInt())

private fun DrawScope.fireworks(art: SeasonArt, t: Float) {
    val w = size.width
    val h = size.height
    val s = w / 1920f
    val paint = art.sparkPaint
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        val last = floor(t / 0.55f).toInt()
        for (n in max(0, last - 8)..last) {
            val tt = t - (n * 0.55f + r01(n, 81) * 0.4f)
            if (tt < 0) continue
            val bx = w * (0.1f + r01(n, 82) * 0.8f)
            val by = h * (0.08f + r01(n, 83) * 0.3f)
            val rise = 0.9f + r01(n, 84) * 0.4f
            val col = FIREWORK[(r01(n, 85) * FIREWORK.size).toInt().coerceIn(0, FIREWORK.size - 1)]
            val col2 = FIREWORK[(r01(n, 86) * FIREWORK.size).toInt().coerceIn(0, FIREWORK.size - 1)]
            if (tt < rise) {
                val p = tt / rise
                val e = 1 - (1 - p) * (1 - p)
                val x = bx + (r01(n, 87) - 0.5f) * 40 * s * (1 - e)
                val y = h * 0.62f + (by - h * 0.62f) * e
                paint.color = 0xFFFFE7B0.toInt()
                paint.strokeWidth = 5 * s
                c.drawPoint(x, y, paint)
                paint.color = 0x88FFB060.toInt()
                paint.strokeWidth = 3 * s
                c.drawLine(x, y, x, y + 50 * s * (1 - p * 0.5f), paint)
                continue
            }
            val life = tt - rise
            if (life > 2.6f) continue
            val fade = (1 - life / 2.6f).pow(1.5f)
            val sparks = 70
            val kd = 1.6f
            val grav = 160 * s
            val speed = (260 + r01(n, 88) * 160) * s
            val q = (1 - exp(-kd * life)) / kd
            val qBefore = (1 - exp(-kd * max(0f, life - 0.12f))) / kd
            var li = 0
            var hi = 0
            for (k in 0 until sparks) {
                val a = k / sparks.toFloat() * PI2 + r01(n * 100 + k, 89) * 0.1f
                val v = speed * (0.75f + 0.25f * r01(n * 100 + k, 90))
                val drop = grav * (life - q) / kd
                val dropBefore = grav * (max(0f, life - 0.12f) - qBefore) / kd
                art.sparks[li++] = bx + cos(a) * v * qBefore
                art.sparks[li++] = by + sin(a) * v * qBefore + dropBefore
                art.sparks[li++] = bx + cos(a) * v * q
                art.sparks[li++] = by + sin(a) * v * q + drop
                art.heads[hi++] = bx + cos(a) * v * q
                art.heads[hi++] = by + sin(a) * v * q + drop
            }
            val alpha = (fade * 255).toInt().coerceIn(0, 255)
            paint.color = col
            paint.alpha = (alpha * 0.55f).toInt()
            paint.strokeWidth = 2 * s
            c.drawLines(art.sparks, 0, li, paint)
            paint.color = col2
            paint.alpha = alpha
            paint.strokeWidth = 4 * s
            c.drawPoints(art.heads, 0, hi, paint)
        }
    }
    paint.xfermode = null
}

/** The extras in the sky, behind the rows: drawn over the weather. */
internal fun DrawScope.skyExtras(e: Extras, t: Float, day: Boolean, festiveSnowOk: Boolean, sunX: Float) {
    val art = SEASON_ART
    if (e.aurora > 0f && !day) aurora(art, t, e.aurora)
    if (e.rainbow > 0f && day) rainbow(art, e.rainbow, sunX)
    if (e.fireworks) fireworks(art, t)
    when (e.season) {
        Season.HALLOWEEN -> halloweenSky(art, t, day)
        Season.PADDY -> paddySky(art, t, day, e.wind)
        Season.CHRISTMAS -> if (festiveSnowOk) festiveSnow(art, t)
        else -> {}
    }
    if (e.leaves) leaves(art, t, e.wind, near = false)
}

// ---- The glass, in front of everything on the apps row

/** Smooth value noise, 0..1. */
private fun vnoise(x: Float, y: Float, salt: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val fx = x - xi
    val fy = y - yi
    val ux = fx * fx * (3 - 2 * fx)
    val uy = fy * fy * (3 - 2 * fy)
    fun at(a: Int, b: Int) = r01(a * 7919 + b * 104729, salt)
    val a = at(xi, yi)
    val b = at(xi + 1, yi)
    val c = at(xi, yi + 1)
    val d = at(xi + 1, yi + 1)
    return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
}

/** Noise with detail in it: three layers of [vnoise], each finer and fainter. */
internal fun fbm(x: Float, y: Float, salt: Int) =
    0.55f * vnoise(x, y, salt) + 0.3f * vnoise(x * 2.1f, y * 2.1f, salt + 1) + 0.15f * vnoise(x * 4.3f, y * 4.3f, salt + 2)

/** Fog on the glass: a damp mist, thicker at the edges, beaded with tiny drops, a few clear runs where drops slid down. */
private fun fogPicture(): ImageBitmap {
    val w = 1280
    val h = 720
    val s = w / 1920f
    return pictureOf(w, h) { c ->
        val p = pen()
        p.shader = RadialGradient(w / 2f, h * 0.45f, w * 0.62f, intArrayOf(Color(0x0DD2DCE8).toArgb(), Color(0x29D2DCE8).toArgb(), Color(0x57D2DCE8).toArgb()), floatArrayOf(0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        for (i in 0 until 5000) {
            val x = r01(i, 21) * w
            val y = r01(i, 22) * h
            val edge = min(1f, sqrt(((x - w / 2f) / (w / 2f)).pow(2) + ((y - h * 0.45f) / (h / 2f)).pow(2)))
            if (r01(i, 23) > 0.25f + 0.75f * edge) continue
            val r = (0.5f + r01(i, 24) * 1.6f) * s
            p.color = Color(0.92f, 0.95f, 0.98f, 0.18f + 0.3f * r01(i, 25)).toArgb()
            c.drawCircle(x, y, r, p)
            p.color = Color(0x1F283246).toArgb()
            c.drawCircle(x + r * 0.3f, y + r * 0.4f, r * 0.7f, p)
        }
        val clear = pen(android.graphics.Color.BLACK)
        clear.style = Paint.Style.STROKE
        clear.strokeCap = Paint.Cap.ROUND
        clear.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        clear.maskFilter = BlurMaskFilter(3 * s, BlurMaskFilter.Blur.NORMAL)
        clear.alpha = 150
        val drop = pen()
        for (i in 0 until 7) {
            val x = (0.06f + r01(i, 31) * 0.88f) * w
            val y0 = r01(i, 32) * h * 0.4f
            val len = h * (0.15f + r01(i, 33) * 0.35f)
            val wd = (10 + r01(i, 34) * 8) * s
            clear.strokeWidth = wd
            c.drawPath(android.graphics.Path().apply { moveTo(x, y0); cubicTo(x + 8 * s, y0 + len * 0.3f, x - 8 * s, y0 + len * 0.6f, x + 3 * s, y0 + len) }, clear)
            val r = wd * 0.6f
            drop.shader = RadialGradient(x + 3 * s - r * 0.3f, y0 + len - r * 0.3f, r, intArrayOf(Color(0x8CFFFFFF).toArgb(), Color(0x40C8D7EB).toArgb(), Color(0x593C4B64).toArgb()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x + 3 * s, y0 + len, r, drop)
        }
    }
}

/** How much snow sits on the tiles, buttons and the weather panel (0: none); set from Home. */
object SnowCover {
    var level by mutableFloatStateOf(0f)
}

/**
 * Snow settled on top of this: a lumpy white cap along the top edge (a little dome on round ones), drawn
 * only when there is some, and only again when it changes.
 */
fun Modifier.snowCap(height: Dp = 5.5.dp, round: Boolean = false, seed: Int = 0): Modifier = drawWithCache {
    val level = SnowCover.level
    if (level <= 0f || size.width <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val h = height.toPx() * level
    val inset = if (round) size.width * 0.18f else 3.dp.toPx()
    val x0 = inset - 1.dp.toPx()
    val x1 = size.width - inset + 1.dp.toPx()
    val y = if (round) 1.5.dp.toPx() else 0.5.dp.toPx()
    val n = max(8, (size.width / 3.dp.toPx()).toInt())
    val cap = Path()
    cap.moveTo(x0, y)
    for (k in 0..n) {
        val u = k / n.toFloat()
        val edge = min(1f, min(u / 0.08f, (1 - u) / 0.08f)).coerceAtLeast(0f)
        val bump = 0.7f + 0.18f * sin(u * 17 + seed) + 0.12f * sin(u * 41 + seed * 3)
        val top = h * bump * edge.pow(0.6f) * (if (round) sin(u * PI2 / 2) else 1f)
        cap.lineTo(x0 + (x1 - x0) * u, y - top)
    }
    cap.lineTo(x1, y + 0.75.dp.toPx())
    cap.quadraticTo((x0 + x1) / 2, y + 1.6.dp.toPx(), x0, y + 0.75.dp.toPx())
    cap.close()
    val fill = Brush.verticalGradient(0f to Color.White, 0.7f to Color(0xFFEEF4FB), 1f to Color(0xFFC3D2E4), startY = y - h, endY = y + 1.5.dp.toPx())
    val rim = Stroke(width = 0.5.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawPath(cap, fill)
        drawPath(cap, Color(0x5978A0C8), style = rim)
    }
}

/** Seconds on the extras' clock, every frame while [running]. */
@Composable
private fun seasonTime(running: Boolean) = produceState(seasonSeconds(System.nanoTime()), running) {
    if (!running) return@produceState
    while (true) withFrameNanos { value = seasonSeconds(it) }
}

private val SEASON_START = System.nanoTime()
private fun seasonSeconds(nanos: Long) = ((nanos - SEASON_START) / 1_000_000_000.0 % 7200.0).toFloat()

/**
 * On the glass, in front of everything on the apps row: the string of lights or bunting along the top,
 * the pumpkins on the weather panel, a bat or a leaf going past close up, and fog.
 * [stripLeft]: where the weather panel starts, in pixels; [stripTop] its top.
 */
@Composable
fun GlassExtras(extras: () -> Extras, active: Boolean, visible: () -> Float, stripLeft: () -> Float, stripTop: Float, modifier: Modifier = Modifier) {
    val e = extras()
    val fog by produceState<ImageBitmap?>(null, e.glassFog > 0f) {
        value = if (e.glassFog <= 0f) null else withContext(Dispatchers.Default) { fogPicture() }
    }
    val moving = e.glassMoves
    if (!moving && fog == null) return
    val time by seasonTime(active && moving)
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = visible().coerceIn(0f, 1f) }) {
        if (visible() <= 0.01f) return@Canvas
        val art = SEASON_ART
        val t = if (moving) time else 0f
        when (e.season) {
            Season.CHRISTMAS -> december(art, e, t)
            Season.PADDY -> bunting(art, t, e.wind)
            Season.HALLOWEEN -> {
                val dp = size.width / 960f
                val left = stripLeft()
                pumpkin(art, left + 62 * dp, stripTop + 1, 27 * dp, t, 1)
                pumpkin(art, left + 110 * dp, stripTop + 1, 19 * dp, t, 2)
                bats(art, t, near = true)
            }
            else -> {}
        }
        if (e.leaves) leaves(art, t, e.wind, near = true)
        fog?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = e.glassFog.coerceIn(0f, 1f)) }
    }
}
