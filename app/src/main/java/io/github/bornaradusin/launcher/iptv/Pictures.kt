package io.github.bornaradusin.launcher.iptv

import okhttp3.Interceptor
import java.io.IOException
import java.net.URI

/** Logos and posters load from wherever the provider keeps them. */
object Pictures {
    fun hostOf(url: String?): String? = url?.let { runCatching { URI(it.trim().replace(" ", "%20")).host?.lowercase() }.getOrNull() }

    fun allowed(url: String?): String? = url?.takeIf { it.isNotBlank() }
}

/** A connection the provider sent on to another server, stopped because that server isn't allowed. */
class BlockedHost(val host: String) : IOException("$host is blocked")

/** The [BlockedHost] somewhere in what went wrong, if that's what it was. */
fun blockedHost(t: Throwable?): String? {
    var e = t
    repeat(8) {
        if (e is BlockedHost) return (e as BlockedHost).host
        e = e?.cause ?: return null
    }
    return null
}

/**
 * Every IPTV connection, the lists' and the player's, goes through here, each step of every redirect
 * included: one to anywhere but the provider's own server is reported, and stopped unless allowed.
 */
object Guard {
    /** The provider's own server (its host). */
    @Volatile
    var home: String? = null

    @Volatile
    var block: Boolean = true

    @Volatile
    var allowed: Set<String> = emptySet()

    /** Told of each other server something was sent to, and whether it was stopped. */
    @Volatile
    var report: (String, Boolean) -> Unit = { _, _ -> }

    val interceptor = Interceptor { chain ->
        val host = chain.request().url.host.lowercase()
        val h = home
        if (h != null && host != h) {
            val stop = block && host !in allowed
            report(host, stop)
            if (stop) throw BlockedHost(host)
        }
        chain.proceed(chain.request())
    }
}
