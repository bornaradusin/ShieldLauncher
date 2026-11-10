package io.github.bornaradusin.launcher.weather

import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

/** One day ahead: its picture, highest and lowest, and the chance of rain or snow (0–100). */
data class DayForecast(val date: String, val sky: Sky, val high: Int, val low: Int, val wet: Int)

/** 1 yellow, 2 orange, 3 red. */
data class Warning(val id: String, val level: Int, val event: String, val area: String, val onset: Long?, val expires: Long?)

/** A day's sunrise and sunset. */
data class Sun(val rise: Long, val set: Long)

/** The part of the day the sky shows: sunrise and sunset are about half an hour each. */
enum class DayPart { DAWN, DAY, DUSK, NIGHT }

data class WeatherNow(
    val sky: Sky,
    /** Open-Meteo's day or night when the weather was fetched; [isDay] goes by the sun, on the minute. */
    val day: Boolean,
    val temperature: Int,
    val feels: Int,
    /** Today and the days after it. */
    val days: List<DayForecast>,
    /** The town it's for. */
    val place: String,
    val warnings: List<Warning>,
    /** Sunrise and sunset, today and the days after. */
    val suns: List<Sun> = emptyList(),
    /** Wind now, km/h. */
    val wind: Int = 0,
    /** Relative humidity now, %. */
    val humidity: Int = 0,
    /** It rained in the last couple of hours and has stopped. */
    val rainedLately: Boolean = false,
    /** Snow in the last few hours and now, cm. */
    val snowLately: Float = 0f,
    /** Where it's for (the sun and moon are placed by it). */
    val lat: Double = 53.0,
    val lon: Double = -8.0,
) {
    /** Day from sunrise to sunset, on the minute. */
    fun isDay(now: Long = System.currentTimeMillis()): Boolean =
        if (suns.isEmpty()) day else suns.any { now >= it.rise && now < it.set }

    /** Day, night, or the half hour or so around sunrise or sunset. */
    fun partAt(now: Long = System.currentTimeMillis()): DayPart {
        if (suns.isEmpty()) return if (day) DayPart.DAY else DayPart.NIGHT
        for (s in suns) {
            if (now >= s.rise - 25 * MINUTE && now <= s.rise + 30 * MINUTE) return DayPart.DAWN
            if (now >= s.set - 30 * MINUTE && now <= s.set + 25 * MINUTE) return DayPart.DUSK
        }
        return if (isDay(now)) DayPart.DAY else DayPart.NIGHT
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}

/**
 * Where the weather is for: a town set from the phone, or (when none is) the internet connection's
 * address. [region] is what Meteoalarm calls the area its warnings are for, when the user names it.
 */
data class WeatherPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val country: String,
    /** County, region and so on, from the map: matched against warnings' areas. */
    val areas: List<String>,
    val region: String = "",
)

/**
 * The weather at home, from Open-Meteo (free, no key), and its weather warnings from Meteoalarm (the
 * European weather services' own). Looked at again every 20 minutes.
 */
class Weather(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val placeSetting: () -> WeatherPlace?,
    private val onNewWarning: (Warning) -> Unit,
    /** Where the last weather is kept, so it's on screen the moment the launcher starts. */
    private val saved: android.content.SharedPreferences? = null,
) {
    private val _now = MutableStateFlow(load())
    val now: StateFlow<WeatherNow?> = _now

    /** The areas Meteoalarm has warnings for just now in this country, for the phone page. */
    private val _regions = MutableStateFlow<List<String>>(emptyList())
    val regions: StateFlow<List<String>> = _regions

    private var auto: WeatherPlace? = null
    private var autoAt = 0L
    private val warned = HashSet<String>()
    private var firstWarnings = true
    private var kick = MutableStateFlow(0)
    private var failures = 0
    /** The weather on screen came from Open-Meteo just now (not the one kept from last time). */
    private var fresh = false

    fun start() {
        scope.launch {
            while (true) {
                val ok = runCatching { refresh() }.isSuccess && fresh
                // Not in yet (no connection just after starting, say): tried again soon, then less often
                failures = if (ok) 0 else failures + 1
                val wait = if (ok) 20 * 60_000L else minOf(60_000L, 5_000L shl minOf(failures, 4))
                val started = kick.value
                var waited = 0L
                while (waited < wait && kick.value == started) {
                    delay(1000)
                    waited += 1000
                }
            }
        }
    }

    /** The place changed: looked at again straight away. */
    fun refreshSoon() {
        firstWarnings = true
        kick.value++
    }

    /** A town, found on the map (Open-Meteo's geocoding). */
    suspend fun find(town: String): WeatherPlace? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", town.trim())
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build().toString()
        val r = get(url).optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        WeatherPlace(
            name = r.optString("name"),
            lat = r.getDouble("latitude"),
            lon = r.getDouble("longitude"),
            country = r.optString("country_code").uppercase(),
            areas = listOf("admin1", "admin2", "admin3", "admin4").mapNotNull { k -> r.optString(k).takeIf { it.isNotBlank() } },
        )
    }

    private suspend fun place(): WeatherPlace? {
        placeSetting()?.let { return it }
        if (auto == null || System.currentTimeMillis() - autoAt > 24 * 3600_000L) {
            val o = withContext(Dispatchers.IO) { get("https://ipwho.is/?fields=success,latitude,longitude,city,region,country_code") }
            if (o.optBoolean("success", false)) {
                auto = WeatherPlace(
                    name = o.optString("city"),
                    lat = o.getDouble("latitude"),
                    lon = o.getDouble("longitude"),
                    country = o.optString("country_code").uppercase(),
                    areas = listOfNotNull(o.optString("region").takeIf { it.isNotBlank() }),
                )
                autoAt = System.currentTimeMillis()
            }
        }
        return auto
    }

    private suspend fun refresh() {
        val p = place() ?: return
        val forecast = withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                    "&forecast_days=6&timezone=auto",
                p.lat, p.lon,
            )
            get(url)
        }
        val cur = forecast.getJSONObject("current")
        val days = forecast.optJSONObject("daily")?.let { d ->
            val dates = d.optJSONArray("time") ?: JSONArray()
            (0 until dates.length()).map { i ->
                DayForecast(
                    date = dates.getString(i),
                    sky = skyOf(d.optJSONArray("weather_code")?.optInt(i) ?: 3),
                    high = Math.round(d.optJSONArray("temperature_2m_max")?.optDouble(i) ?: 0.0).toInt(),
                    low = Math.round(d.optJSONArray("temperature_2m_min")?.optDouble(i) ?: 0.0).toInt(),
                    wet = d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0,
                )
            }
        } ?: emptyList()
        // Sunrise and sunset come as the town's own time: the answer says how far that is from UTC
        val offset = java.time.ZoneOffset.ofTotalSeconds(forecast.optInt("utc_offset_seconds", 0))
        val suns = forecast.optJSONObject("daily")?.let { d ->
            val rise = d.optJSONArray("sunrise") ?: JSONArray()
            val set = d.optJSONArray("sunset") ?: JSONArray()
            (0 until minOf(rise.length(), set.length())).mapNotNull { i ->
                runCatching {
                    Sun(
                        java.time.LocalDateTime.parse(rise.getString(i)).toEpochSecond(offset) * 1000,
                        java.time.LocalDateTime.parse(set.getString(i)).toEpochSecond(offset) * 1000,
                    )
                }.getOrNull()
            }
        } ?: emptyList()
        // On screen straight away (keeping the last warnings and wind for now), then the rest follows
        val before = _now.value
        val first = WeatherNow(
            sky = skyOf(cur.getInt("weather_code")),
            day = cur.optInt("is_day", 1) == 1,
            temperature = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feels = Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))).toInt(),
            days = days,
            place = p.name,
            warnings = before?.warnings ?: emptyList(),
            suns = suns,
            wind = before?.wind ?: 0,
            humidity = before?.humidity ?: 0,
            rainedLately = before?.rainedLately ?: false,
            snowLately = before?.snowLately ?: 0f,
            lat = p.lat,
            lon = p.lon,
        )
        _now.value = first
        fresh = true
        // Wind, damp, and the last few hours' rain and snow (for the sky's extras): asked for on their own,
        // so if that's ever turned down the weather itself still comes
        val more = runCatching { withContext(Dispatchers.IO) { recent(p) } }.getOrNull()
        val warnings = runCatching { warnings(p) }.getOrElse { _now.value?.warnings ?: emptyList() }
        _now.value = WeatherNow(
            sky = skyOf(cur.getInt("weather_code")),
            day = cur.optInt("is_day", 1) == 1,
            temperature = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feels = Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))).toInt(),
            days = days,
            place = p.name,
            warnings = warnings,
            suns = suns,
            wind = more?.wind ?: 0,
            humidity = more?.humidity ?: 0,
            rainedLately = more?.rainedLately ?: false,
            snowLately = more?.snow ?: 0f,
            lat = p.lat,
            lon = p.lon,
        )
        _now.value?.let { save(it) }
        // A new warning shows over whatever is on, once (not the ones already there when the launcher starts up… unless they're new to it)
        warnings.forEach { w ->
            if (warned.add(w.id)) onNewWarning(w)
        }
        firstWarnings = false
    }

    /** The weather kept from last time, if it's from the last 6 hours. */
    private fun load(): WeatherNow? = runCatching {
        val o = JSONObject(saved?.getString("last", null) ?: return null)
        if (System.currentTimeMillis() - o.getLong("at") > 6 * 3_600_000L) return null
        val days = o.getJSONArray("days")
        val suns = o.getJSONArray("suns")
        WeatherNow(
            sky = Sky.valueOf(o.getString("sky")),
            day = o.getBoolean("day"),
            temperature = o.getInt("t"),
            feels = o.getInt("f"),
            days = (0 until days.length()).map { i ->
                val d = days.getJSONArray(i)
                DayForecast(d.getString(0), Sky.valueOf(d.getString(1)), d.getInt(2), d.getInt(3), d.getInt(4))
            },
            place = o.optString("place"),
            warnings = emptyList(),
            suns = (0 until suns.length()).map { i -> suns.getJSONArray(i).let { Sun(it.getLong(0), it.getLong(1)) } },
            wind = o.optInt("wind"),
            humidity = o.optInt("hum"),
            rainedLately = o.optBoolean("rain"),
            snowLately = o.optDouble("snow", 0.0).toFloat(),
            lat = o.optDouble("lat", 53.0),
            lon = o.optDouble("lon", -8.0),
        )
    }.getOrNull()

    private fun save(w: WeatherNow) {
        val prefs = saved ?: return
        runCatching {
            val o = JSONObject()
                .put("at", System.currentTimeMillis()).put("sky", w.sky.name).put("day", w.day).put("t", w.temperature).put("f", w.feels)
                .put("place", w.place).put("wind", w.wind).put("hum", w.humidity).put("rain", w.rainedLately).put("snow", w.snowLately.toDouble())
                .put("lat", w.lat).put("lon", w.lon)
                .put("days", JSONArray().apply { w.days.forEach { d -> put(JSONArray().put(d.date).put(d.sky.name).put(d.high).put(d.low).put(d.wet)) } })
                .put("suns", JSONArray().apply { w.suns.forEach { u -> put(JSONArray().put(u.rise).put(u.set)) } })
            prefs.edit().putString("last", o.toString()).apply()
        }
    }

    private class Recent(val wind: Int, val humidity: Int, val rainedLately: Boolean, val snow: Float)

    /** The wind and damp now, and whether it has just stopped raining or has been snowing, from the hours either side of now. */
    private fun recent(p: WeatherPlace): Recent {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=relative_humidity_2m,wind_speed_10m,precipitation,snowfall" +
                "&hourly=precipitation,snowfall&past_days=1&forecast_days=1&timezone=auto",
            p.lat, p.lon,
        )
        val o = get(url)
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val rain = hourly.getJSONArray("precipitation")
        val snowfall = hourly.getJSONArray("snowfall")
        // This hour, by the time the answer gives (the town's own), e.g. 2026-11-10T23:00
        val hour = cur.optString("time").take(13) + ":00"
        val i = (0 until times.length()).firstOrNull { times.getString(it) == hour } ?: (times.length() - 1)
        val lastTwo = (maxOf(0, i - 2) until i).sumOf { rain.optDouble(it, 0.0) }
        val snow = (maxOf(0, i - 3)..i).sumOf { snowfall.optDouble(it, 0.0) } + cur.optDouble("snowfall", 0.0)
        return Recent(
            wind = Math.round(cur.optDouble("wind_speed_10m", 0.0)).toInt(),
            humidity = cur.optInt("relative_humidity_2m", 0),
            rainedLately = lastTwo > 0.2 && cur.optDouble("precipitation", 0.0) <= 0.05,
            snow = snow.toFloat(),
        )
    }

    /** Meteoalarm's warnings for the country, kept to the ones for this place, worst first. */
    private suspend fun warnings(p: WeatherPlace): List<Warning> = withContext(Dispatchers.IO) {
        val country = COUNTRIES[p.country] ?: run {
            _regions.value = emptyList()
            return@withContext emptyList()
        }
        val xml = text("https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-$country")
        val all = parseAtom(xml)
        val now = System.currentTimeMillis()
        val current = all.filter { (it.expires ?: Long.MAX_VALUE) > now && it.level > 0 }
        _regions.value = current.map { it.area }.distinct().sorted()
        val wanted = if (p.region.isNotBlank()) listOf(p.region) else listOf(p.name) + p.areas
        current.filter { w -> wanted.any { matches(w.area, it) } }
            .distinctBy { "${it.area}|${it.level}|${it.onset}|${it.expires}|${simple(it.event).take(6)}" }
            .sortedWith(compareByDescending<Warning> { it.level }.thenBy { it.onset ?: 0L })
    }

    private fun parseAtom(xml: String): List<Warning> {
        val out = mutableListOf<Warning>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var inEntry = false
        var fields = HashMap<String, String>()
        var tag = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.substringAfter(':')
                    if (tag == "entry") {
                        inEntry = true
                        fields = HashMap()
                    }
                }
                XmlPullParser.TEXT -> if (inEntry && tag.isNotEmpty()) {
                    val t = parser.text?.trim().orEmpty()
                    if (t.isNotEmpty() && !fields.containsKey(tag)) fields[tag] = t
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (name == "entry") {
                        inEntry = false
                        entryToWarning(fields)?.let { out += it }
                    }
                    tag = ""
                }
            }
        }
        return out
    }

    private fun entryToWarning(f: Map<String, String>): Warning? {
        val area = f["areaDesc"] ?: return null
        val title = f["title"].orEmpty()
        val level = when {
            f["severity"].equals("Extreme", true) -> 3
            f["severity"].equals("Severe", true) -> 2
            f["severity"].equals("Moderate", true) -> 1
            title.contains("red", true) -> 3
            title.contains("orange", true) -> 2
            title.contains("yellow", true) -> 1
            else -> 0
        }
        if (f["message_type"].equals("Cancel", true)) return null
        val event = f["event"] ?: title
        return Warning(
            id = f["identifier"] ?: f["id"] ?: "$area|$event|${f["onset"]}",
            level = level,
            event = event.replaceFirstChar { it.uppercase() },
            area = area,
            onset = time(f["onset"] ?: f["effective"]),
            expires = time(f["expires"]),
        )
    }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss")) {
            runCatching { return SimpleDateFormat(pattern, Locale.US).apply { if (pattern.endsWith("'Z'")) timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)?.time }
        }
        return null
    }

    private fun get(url: String): JSONObject = JSONObject(text(url))

    private fun text(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "ShieldLauncher (github.com/bornaradusin/ShieldLauncher)").build()
        return http.newCall(req).execute().use { res -> res.body?.string().orEmpty() }
    }

    companion object {
        /** The WMO weather codes Open-Meteo gives, as one of the pictures. */
        fun skyOf(code: Int): Sky = when (code) {
            0, 1 -> Sky.CLEAR
            2 -> Sky.PARTLY
            3 -> Sky.CLOUDY
            45, 48 -> Sky.FOG
            in 51..67, in 80..82 -> Sky.RAIN
            in 71..77, 85, 86 -> Sky.SNOW
            in 95..99 -> Sky.THUNDER
            else -> Sky.CLOUDY
        }

        /** A name without accents, case or words like "county", for matching areas. */
        fun simple(s: String): String {
            val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .replace('đ', 'd').replace('Đ', 'D').replace('ł', 'l').replace('ø', 'o').replace('ß', 's')
                .lowercase(Locale.ROOT)
            return plain.replace(Regex("[^a-z0-9 ]"), " ")
                .split(' ').filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")
        }

        fun matches(area: String, name: String): Boolean {
            val a = simple(area)
            val n = simple(name)
            if (a.isEmpty() || n.length < 3) return false
            if (a == n || a.contains(n) || n.contains(a)) return true
            // "Zagrebacka" and "Zagreb": the same stem
            val stem = n.split(' ').first().take(5)
            return stem.length >= 5 && a.split(' ').any { it.startsWith(stem) }
        }

        private val FILLER = setOf(
            "county", "region", "province", "district", "of", "the", "and", "city", "municipality",
            "zupanija", "regija", "grad", "kreis", "landkreis", "bezirk", "departement", "provincia", "comunidad",
        )

        /** Meteoalarm's feed name for each country. */
        val COUNTRIES = mapOf(
            "AT" to "austria", "BE" to "belgium", "BA" to "bosnia-herzegovina", "BG" to "bulgaria", "HR" to "croatia",
            "CY" to "cyprus", "CZ" to "czechia", "DK" to "denmark", "EE" to "estonia", "FI" to "finland", "FR" to "france",
            "DE" to "germany", "GR" to "greece", "HU" to "hungary", "IS" to "iceland", "IE" to "ireland", "IL" to "israel",
            "IT" to "italy", "LV" to "latvia", "LT" to "lithuania", "LU" to "luxembourg", "MT" to "malta", "MD" to "moldova",
            "ME" to "montenegro", "MK" to "republic-of-north-macedonia", "NL" to "netherlands", "NO" to "norway", "PL" to "poland",
            "PT" to "portugal", "RO" to "romania", "RS" to "serbia", "SK" to "slovakia", "SI" to "slovenia", "ES" to "spain",
            "SE" to "sweden", "CH" to "switzerland", "UA" to "ukraine", "GB" to "united-kingdom",
        )
    }
}
