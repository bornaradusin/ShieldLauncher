package io.github.bornaradusin.launcher.sky

import io.github.bornaradusin.launcher.weather.DayPart
import io.github.bornaradusin.launcher.weather.Sky
import io.github.bornaradusin.launcher.weather.WeatherNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where something is in the sky: degrees above the horizon, and round from north (east 90, south 180). */
data class AltAz(val alt: Double, val az: Double)

/** The sun and moon where they really are, worked out from the date and the place (to a degree or so). */
object Astro {
    private const val RAD = PI / 180

    private fun days(ms: Long) = ms / 86_400_000.0 - 10957.5

    private fun altAz(ra: Double, dec: Double, d: Double, lat: Double, lon: Double): AltAz {
        val lst = (280.46061837 + 360.98564736629 * d + lon) * RAD
        val h = lst - ra
        val phi = lat * RAD
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi)) + PI
        return AltAz(alt / RAD, az / RAD)
    }

    fun sun(ms: Long, lat: Double, lon: Double): AltAz {
        val d = days(ms)
        val g = (357.529 + 0.98560028 * d) * RAD
        val q = 280.459 + 0.98564736 * d
        val l = (q + 1.915 * sin(g) + 0.02 * sin(2 * g)) * RAD
        val e = (23.439 - 0.00000036 * d) * RAD
        return altAz(atan2(cos(e) * sin(l), cos(l)), asin(sin(e) * sin(l)), d, lat, lon)
    }

    fun moon(ms: Long, lat: Double, lon: Double): AltAz {
        val d = days(ms)
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.22935 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val e = RAD * 23.4397
        val ra = atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l))
        val dec = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l))
        return altAz(ra, dec, d, lat, lon)
    }

    /**
     * Where on the screen it shows, as parts of its width and height: looking south, east on the left
     * and west on the right; the horizon a little under halfway down, overhead at the top.
     */
    fun onScreen(p: AltAz): Pair<Float, Float> {
        val horizon = 0.41
        val x = 0.5 + ((p.az - 180) / 90).coerceIn(-1.05, 1.05) * 0.45
        val y = horizon - (p.alt / 55).coerceIn(-0.2, 1.0) * (horizon - 0.03)
        return x.toFloat() to y.toFloat()
    }
}

/** The times of year with a look of their own. */
enum class Season { NONE, CHRISTMAS, HALLOWEEN, PADDY, NEW_YEAR }

/** Each look, to try out now from Settings (whatever the date or the weather). */
enum class Look(val label: String) {
    CHRISTMAS("December"), HALLOWEEN("Halloween"), PADDY("St Patrick's Day"), COUNTDOWN("New Year's Eve countdown"),
    FIREWORKS("New Year fireworks"), FROST("Freezing"), FOG("Fog on the glass"), SNOW("Snow settling"), AURORA("Northern lights"),
    RAINBOW("Rainbow"), METEORS("Meteor shower"), LEAVES("Autumn leaves"), LUNAR("Lunar eclipse"), SOLAR("Eclipse of the sun"),
}

/** Everything extra the sky and the glass show just now, and how strongly. */
data class Extras(
    val season: Season = Season.NONE,
    val warmLights: Boolean = false,
    val fireworks: Boolean = false,
    /** Seconds left in the year, in its last minute. */
    val countdownTo: Long? = null,
    val frost: Float = 0f,
    val glassFog: Float = 0f,
    val settle: Float = 0f,
    /** km/h. */
    val wind: Float = 10f,
    val aurora: Float = 0f,
    val rainbow: Float = 0f,
    val leaves: Boolean = false,
    val meteors: Boolean = false,
    val lunarEclipse: Boolean = false,
    /** How much of the sun is covered, 0 to 1. */
    val solarEclipse: Float = 0f,
    /** The sun and moon where they really are (else where they always were). */
    val realSky: Boolean = false,
    val sun: AltAz? = null,
    val moon: AltAz? = null,
    val moonPhase: Float = 0f,
) {
    /** Something on the glass moves, so it's drawn every frame (else only when it changes). */
    val glassMoves get() = season != Season.NONE || leaves
}

/** The night sky's calendar: meteor showers, and the eclipses seen from Ireland. */
object SkyCalendar {
    /** Each shower's nights: month (1–12), first and last day. */
    private val SHOWERS = listOf(
        Triple(1, 2, 4), Triple(4, 21, 23), Triple(5, 5, 7), Triple(8, 11, 13), Triple(10, 20, 22), Triple(11, 16, 18), Triple(12, 12, 14),
    )

    fun meteorShower(cal: Calendar): Boolean {
        // The night belongs to the evening it starts on
        val c = cal.clone() as Calendar
        if (c.get(Calendar.HOUR_OF_DAY) < 12) c.add(Calendar.DAY_OF_MONTH, -1)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return SHOWERS.any { (month, from, to) -> month == m && d in from..to }
    }

    /** The red part of total eclipses of the moon (UTC, from NASA's tables): the moon only shows it if it's up. */
    private val LUNAR = listOf(
        "2028-12-31T16:16Z" to "2028-12-31T17:28Z",
        "2029-06-26T02:31Z" to "2029-06-26T04:13Z",
        "2029-12-20T22:15Z" to "2029-12-20T23:09Z",
        "2032-10-18T18:41Z" to "2032-10-18T19:28Z",
        "2033-04-14T18:48Z" to "2033-04-14T19:37Z",
        "2036-02-11T21:35Z" to "2036-02-11T22:49Z",
        "2036-08-07T02:04Z" to "2036-08-07T03:39Z",
    ).map { (a, b) -> ms(a) to ms(b) }

    /** Partial eclipses of the sun seen from Ireland: start, greatest, end (UTC) and how much is covered then. */
    private data class Solar(val start: Long, val max: Long, val end: Long, val cover: Float)
    private val SOLAR = listOf(
        Solar(ms("2027-08-02T08:03Z"), ms("2027-08-02T08:55Z"), ms("2027-08-02T09:52Z"), 0.36f),
        Solar(ms("2028-01-26T15:33Z"), ms("2028-01-26T16:42Z"), ms("2028-01-26T17:52Z"), 0.45f),
        Solar(ms("2030-06-01T04:32Z"), ms("2030-06-01T05:31Z"), ms("2030-06-01T06:24Z"), 0.36f),
    )

    fun lunarEclipse(now: Long) = LUNAR.any { (a, b) -> now in (a - 20 * 60_000L)..(b + 20 * 60_000L) }

    fun solarEclipse(now: Long): Float {
        val e = SOLAR.firstOrNull { now in it.start..it.end } ?: return 0f
        val half = if (now < e.max) (e.max - e.start).toFloat() else (e.end - e.max).toFloat()
        return e.cover * (1f - abs(now - e.max) / half).coerceIn(0f, 1f)
    }

    private fun ms(iso: String) = java.time.Instant.parse(iso.replace("Z", ":00Z")).toEpochMilli()
}

/** The northern lights' chances: NOAA's planetary K index, looked at every 20 minutes. */
class AuroraWatch(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _kp = MutableStateFlow(0f)
    val kp: StateFlow<Float> = _kp

    fun start() {
        scope.launch {
            while (true) {
                runCatching {
                    val body = withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url("https://services.swpc.noaa.gov/json/planetary_k_index_1m.json").build()).execute().use { it.body?.string().orEmpty() }
                    }
                    val arr = JSONArray(body)
                    if (arr.length() > 0) _kp.value = arr.getJSONObject(arr.length() - 1).optDouble("estimated_kp", 0.0).toFloat()
                }
                delay(20 * 60_000L)
            }
        }
    }
}

/**
 * What to show just now: the time of year (when [seasonal]), the weather on the glass (when [glass]),
 * and the sky's real sun, moon and events (when [sky]). [preview] shows one look whatever the day.
 */
fun extrasAt(
    now: Long,
    weather: WeatherNow?,
    kp: Float,
    seasonal: Boolean,
    warmLights: Boolean,
    glass: Boolean,
    sky: Boolean,
    preview: Look?,
): Extras {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val w = weather
    val part = w?.partAt(now) ?: DayPart.DAY
    val dark = part == DayPart.NIGHT
    val lat = w?.lat ?: 53.0
    val lon = w?.lon ?: -8.0
    val clearish = w != null && (w.sky == Sky.CLEAR || w.sky == Sky.PARTLY)

    var season = Season.NONE
    var fireworks = false
    var countdownTo: Long? = null
    if (seasonal) {
        season = when {
            month == 12 || (month == 1 && day <= 6) -> Season.CHRISTMAS
            month == 10 && day >= 24 -> Season.HALLOWEEN
            month == 3 && day == 17 -> Season.PADDY
            else -> Season.NONE
        }
        // The last minute of the year counts down; the first quarter of an hour of the new one has fireworks
        if (month == 12 && day == 31 && hour == 23 && minute == 59) {
            countdownTo = (cal.clone() as Calendar).apply { add(Calendar.MINUTE, 1); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        }
        if (month == 1 && day == 1 && hour == 0 && minute < 15) fireworks = true
    }

    var frost = 0f
    var glassFog = 0f
    var settle = 0f
    if (glass && w != null) {
        if (w.temperature <= 0) frost = ((0.5f - w.temperature) / 5f).coerceIn(0.35f, 1f)
        val misty = w.sky == Sky.FOG || (w.humidity >= 95 && hour in 5..10 && w.temperature <= 10)
        if (misty) glassFog = if (w.sky == Sky.FOG) 1f else 0.7f
        if (w.snowLately > 0.2f && w.temperature <= 2) settle = (w.snowLately / 2f).coerceIn(0.4f, 1f)
    }

    var aurora = 0f
    var rainbow = 0f
    var leaves = false
    var meteors = false
    var lunar = false
    var solar = 0f
    val sun = Astro.sun(now, lat, lon)
    val moon = Astro.moon(now, lat, lon)
    if (sky && w != null) {
        if (dark && clearish && kp >= 6f) aurora = ((kp - 5.5f) / 2.5f).coerceIn(0.35f, 1f)
        if (!dark && clearish && w.rainedLately && sun.alt in 3.0..42.0) rainbow = 1f
        val autumn = (month == 9 && day >= 15) || month == 10 || month == 11
        leaves = autumn && w.wind >= 30 && w.sky != Sky.SNOW
        meteors = dark && clearish && SkyCalendar.meteorShower(cal)
        lunar = SkyCalendar.lunarEclipse(now) && moon.alt > 0
        solar = if (sun.alt > 0) SkyCalendar.solarEclipse(now) else 0f
    }

    var e = Extras(
        season = season, warmLights = warmLights, fireworks = fireworks, countdownTo = countdownTo,
        frost = frost, glassFog = glassFog, settle = settle, wind = (w?.wind ?: 10).toFloat(),
        aurora = aurora, rainbow = rainbow, leaves = leaves, meteors = meteors, lunarEclipse = lunar, solarEclipse = solar,
        realSky = sky, sun = sun, moon = moon, moonPhase = moonPhaseAt(now),
    )
    // Trying one out from Settings: just that look, on top of whatever's real
    when (preview) {
        null -> {}
        Look.CHRISTMAS -> e = e.copy(season = Season.CHRISTMAS)
        Look.HALLOWEEN -> e = e.copy(season = Season.HALLOWEEN)
        Look.PADDY -> e = e.copy(season = Season.PADDY, wind = maxOf(e.wind, 20f))
        Look.COUNTDOWN -> e = e.copy(countdownTo = now - now % 60_000L + 60_000L)
        Look.FIREWORKS -> e = e.copy(fireworks = true)
        Look.FROST -> e = e.copy(frost = 0.9f)
        Look.FOG -> e = e.copy(glassFog = 1f)
        Look.SNOW -> e = e.copy(settle = 1f)
        Look.AURORA -> e = e.copy(aurora = 1f)
        Look.RAINBOW -> e = e.copy(rainbow = 1f)
        Look.METEORS -> e = e.copy(meteors = true)
        Look.LEAVES -> e = e.copy(leaves = true, wind = maxOf(e.wind, 45f))
        Look.LUNAR -> e = e.copy(lunarEclipse = true)
        Look.SOLAR -> e = e.copy(solarEclipse = 0.75f)
    }
    return e
}

/** How far through its month the moon is: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
fun moonPhaseAt(now: Long): Float {
    // A new moon: 6 January 2000, 18:14 UTC; a lunar month is 29.53 days
    val days = (now - 947_182_440_000L) / 86_400_000.0
    val p = (days / 29.530588853) % 1.0
    return (if (p < 0) p + 1 else p).toFloat()
}
