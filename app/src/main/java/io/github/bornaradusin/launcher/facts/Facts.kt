package io.github.bornaradusin.launcher.facts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * The fun fact above the clock: a new one every hour that Home is on screen, and never one that's been
 * shown before. They come from the list in the launcher's GitHub repo (fetched again every day, so it
 * grows without a new version), and once those have all been shown, from Wikipedia's "On this day",
 * which has new ones for every date.
 */
class Facts(context: Context, http: OkHttpClient, private val scope: CoroutineScope, private val version: String) {
    private val prefs = context.getSharedPreferences("facts", Context.MODE_PRIVATE)
    private val saved = File(context.filesDir, "facts.txt")
    private val assets = context.assets
    private val http = http
    private val lock = Mutex()

    /** The repo's list: what was last fetched, or the one the launcher came with. */
    @Volatile private var list: List<String> = read()

    /** Wikipedia's "On this day", for [spareDay] only. */
    @Volatile private var spare: List<String> = emptyList()
    @Volatile private var spareDay = ""

    private val seen = prefs.getStringSet("seen", emptySet()).orEmpty().toMutableSet()

    private val _current = MutableStateFlow(prefs.getString("current", null).orEmpty())
    val current: StateFlow<String> = _current

    fun start() {
        scope.launch {
            if (_current.value.isBlank()) next(System.currentTimeMillis())
            while (true) {
                runCatching { fetchList() }
                delay(DAY)
            }
        }
    }

    /** Called while the clock is on screen: in a new hour, the next fact. */
    fun tick(now: Long) {
        val hour = now / HOUR
        if (prefs.getLong("hour", -1) == hour && _current.value.isNotBlank()) return
        scope.launch { next(now) }
    }

    private suspend fun next(now: Long) = lock.withLock {
        val hour = now / HOUR
        if (prefs.getLong("hour", -1) == hour && _current.value.isNotBlank()) return@withLock
        var fact = unseen(list).randomOrNull()
        if (fact == null) {
            // The list is all used: Wikipedia's "On this day" for today, and the list again from GitHub in case it's grown
            runCatching { fetchList() }
            fact = unseen(list).randomOrNull() ?: run {
                runCatching { onThisDay(now) }
                unseen(spare).randomOrNull()
            }
        }
        if (fact == null) {
            // Nothing new anywhere (no internet): keep this one up rather than show nothing
            if (_current.value.isNotBlank()) return@withLock
            fact = list.randomOrNull() ?: return@withLock
        }
        seen += key(fact)
        _current.value = fact
        prefs.edit().putStringSet("seen", seen.toSet()).putString("current", fact).putLong("hour", hour).apply()
        // Getting low: fetch ahead, so the next ones are ready
        if (unseen(list).size < 24) scope.launch { runCatching { fetchList() } }
    }

    private fun unseen(from: List<String>) = from.filter { key(it) !in seen }

    private fun key(fact: String) = fact.trim().lowercase().hashCode().toString(36)

    private fun read(): List<String> {
        val text = runCatching { saved.readText() }.getOrNull() ?: runCatching { assets.open("facts.txt").bufferedReader().readText() }.getOrDefault("")
        return parse(text)
    }

    private fun parse(text: String) = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()

    /** The list from GitHub: facts added there reach the TV without a new version. */
    private suspend fun fetchList() = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LIST).header("User-Agent", "ShieldLauncher/$version").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext
            val text = r.body?.string() ?: return@withContext
            val facts = parse(text)
            if (facts.size < 10) return@withContext
            saved.writeText(text)
            // The launcher's own list stays in too, so a fact can't come back by dropping out of the file
            list = (facts + parse(runCatching { assets.open("facts.txt").bufferedReader().readText() }.getOrDefault(""))).distinct()
        }
    }

    /** Wikipedia's "On this day" for today, kept to the ones short enough for above the clock. */
    private suspend fun onThisDay(now: Long) = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val today = "$month/$day"
        if (spareDay == today && unseen(spare).isNotEmpty()) return@withContext
        val url = "https://en.wikipedia.org/api/rest_v1/feed/onthisday/events/%02d/%02d".format(month, day)
        val req = Request.Builder().url(url)
            .header("User-Agent", "ShieldLauncher/$version (https://github.com/bornaradusin/ShieldLauncher)")
            .header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext
            val events = JSONObject(r.body?.string() ?: return@withContext).optJSONArray("events") ?: return@withContext
            spare = (0 until events.length()).mapNotNull { i ->
                val e = events.getJSONObject(i)
                val year = e.optInt("year", 0)
                val text = e.optString("text").trim().removeSuffix(".")
                // Fun facts, not sad ones: wars, disasters and deaths are left out
                if (year <= 0 || text.isEmpty() || GRIM.containsMatchIn(text)) return@mapNotNull null
                "On this day in $year: $text.".takeIf { it.length <= 70 }
            }
            spareDay = today
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private val GRIM = Regex(
            "\\b(kill|killed|killing|dies|died|death|dead|deaths|murder|massacre|war|wars|battle|bomb|bombing|attack|attacks|shot|shooting|" +
                "executed|execution|assassinat|terror|genocide|disaster|crash|crashes|earthquake|tsunami|flood|famine|sinks|sank|explosion|" +
                "invade|invades|invasion|siege|hostage|riot|plague|epidemic|pandemic|slave|holocaust|nazi|victims|wounded|injured)",
            RegexOption.IGNORE_CASE,
        )
        private const val LIST = "https://raw.githubusercontent.com/bornaradusin/ShieldLauncher/main/app/src/main/assets/facts.txt"
    }
}
