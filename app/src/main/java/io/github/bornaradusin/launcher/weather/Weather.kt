package io.github.bornaradusin.launcher.weather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

data class WeatherNow(val sky: Sky, val day: Boolean, val temperature: Int)

/**
 * The weather at home for the top bar, from Open-Meteo (free, no key). The Shield has no GPS, so
 * where home is comes from the internet connection's address (roughly the town), once a day.
 */
class Weather(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now

    private var place: Pair<Double, Double>? = null
    private var placeAt = 0L

    fun start() {
        scope.launch {
            while (true) {
                runCatching { refresh() }
                delay(if (_now.value == null) 60_000 else 20 * 60_000L)
            }
        }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        if (place == null || System.currentTimeMillis() - placeAt > 24 * 3600_000L) {
            val o = get("https://ipwho.is/?fields=success,latitude,longitude")
            if (o.optBoolean("success", false)) {
                place = o.getDouble("latitude") to o.getDouble("longitude")
                placeAt = System.currentTimeMillis()
            }
        }
        val (lat, lon) = place ?: return@withContext
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day",
            lat, lon,
        )
        val cur = get(url).getJSONObject("current")
        _now.value = WeatherNow(skyOf(cur.getInt("weather_code")), cur.optInt("is_day", 1) == 1, Math.round(cur.getDouble("temperature_2m")).toInt())
    }

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", "ShieldLauncher (github.com/bornaradusin/AndroidLauncher)").build()
        return http.newCall(req).execute().use { res -> JSONObject(res.body?.string().orEmpty()) }
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
    }
}
