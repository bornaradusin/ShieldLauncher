package io.github.bornaradusin.launcher.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Pairing a phone: the TV shows a code (in the QR code too), the phone's page sends it back once and
 * gets a key it keeps, so it stays set up for next time. A new code each time the TV screen opens.
 */
class PhoneLink(context: Context) {
    private val prefs = context.getSharedPreferences("phones", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val _code = MutableStateFlow(newCode())
    val code: StateFlow<String> = _code

    private val _phone = MutableStateFlow<String?>(null)
    /** The phone that last paired or changed something, by name (for "… is connected"). */
    val phone: StateFlow<String?> = _phone

    fun refreshCode() {
        _code.value = newCode()
        _phone.value = null
    }

    /** The phone's key for a right code, or null. */
    @Synchronized
    fun pair(code: String, agent: String?): String? {
        if (code.trim() != _code.value) return null
        val key = (1..32).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
        val keys = (prefs.getStringSet("keys", emptySet()) ?: emptySet()).toMutableList()
        keys += key
        // The five most recent phones stay paired
        prefs.edit().putStringSet("keys", keys.takeLast(5).toSet()).apply()
        seen(agent)
        return key
    }

    fun valid(key: String?): Boolean = key != null && key.length == 32 && (prefs.getStringSet("keys", emptySet()) ?: emptySet()).contains(key)

    fun seen(agent: String?) {
        _phone.value = phoneName(agent)
    }

    private fun newCode() = (1..4).map { random.nextInt(10) }.joinToString("")

    companion object {
        /** "Pixel 8" from a phone browser's "… (Linux; Android 15; Pixel 8) …". */
        fun phoneName(agent: String?): String {
            val a = agent ?: return "Your phone"
            if (a.contains("iPhone")) return "Your iPhone"
            val inside = Regex("\\(([^)]*)\\)").find(a)?.groupValues?.get(1) ?: return "Your phone"
            val model = inside.split(';').map { it.trim() }.lastOrNull { it.isNotBlank() && !it.startsWith("Android") && it != "Linux" && it != "K" && !it.startsWith("wv") }
            return model?.removeSuffix(" Build").substringBefore(" Build/") ?: "Your phone"
        }
    }
}
