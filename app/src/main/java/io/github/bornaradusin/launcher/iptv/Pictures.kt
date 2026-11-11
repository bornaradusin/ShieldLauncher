package io.github.bornaradusin.launcher.iptv

import java.net.URI

/** The provider's own server (its host), to tell when a connection ends up somewhere else. */
object Pictures {
    @Volatile
    var home: String? = null

    fun hostOf(url: String?): String? = url?.let { runCatching { URI(it.trim().replace(" ", "%20")).host?.lowercase() }.getOrNull() }

    /** Logos and posters load from wherever the provider keeps them. */
    fun allowed(url: String?): String? = url?.takeIf { it.isNotBlank() }
}
