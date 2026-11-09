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

data class WeatherNow(
    val sky: Sky,
    val day: Boolean,
    val temperature: Int,
    val feels: Int,
    /** Today and the days after it. */
    val days: List<DayForecast>,
    /** The town it's for. */
    val place: String,
    val warnings: List<Warning>,
)

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
) {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now

    /** The areas Meteoalarm has warnings for just now in this country, for the phone page. */
    private val _regions = MutableStateFlow<List<String>>(emptyList())
    val regions: StateFlow<List<String>> = _regions

    private var auto: WeatherPlace? = null
    private var autoAt = 0L
    private val warned = HashSet<String>()
    private var firstWarnings = true
    private var kick = MutableStateFlow(0)

    fun start() {
        scope.launch {
            while (true) {
                runCatching { refresh() }
                val wait = if (_now.value == null) 60_000L else 20 * 60_000L
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
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
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
        val warnings = runCatching { warnings(p) }.getOrElse { _now.value?.warnings ?: emptyList() }
        _now.value = WeatherNow(
            sky = skyOf(cur.getInt("weather_code")),
            day = cur.optInt("is_day", 1) == 1,
            temperature = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feels = Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))).toInt(),
            days = days,
            place = p.name,
            warnings = warnings,
        )
        // A new warning shows over whatever is on, once (not the ones already there when the launcher starts up… unless they're new to it)
        warnings.forEach { w ->
            if (warned.add(w.id)) onNewWarning(w)
        }
        firstWarnings = false
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
