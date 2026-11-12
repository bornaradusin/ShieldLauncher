package io.github.bornaradusin.launcher.iptv

import java.net.URI

/** Logos and posters load from wherever the provider keeps them. */
object Pictures {
    fun hostOf(url: String?): String? = url?.let { runCatching { URI(it.trim().replace(" ", "%20")).host?.lowercase() }.getOrNull() }

    fun allowed(url: String?): String? = url?.takeIf { it.isNotBlank() }
}
