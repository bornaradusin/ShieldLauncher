package io.github.bornaradusin.launcher.server

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * A notification's fields, read straight from the request rather than through NanoHTTPD's form
 * parsing (which renames a second picture, reads text as ASCII and can't take a chunked body).
 * Takes multipart forms (Home Assistant's integration), url-encoded forms and JSON.
 */
class NoticeBody(val fields: Map<String, String>, val files: Map<String, ByteArray>) {

    /** What came in, for Settings when nothing in it can be shown. */
    var about: String = ""
        private set

    fun field(name: String): String = fields.entries.firstOrNull { it.key.equals(name, true) }?.value?.trim().orEmpty()

    /** The icon: the part "filename". */
    val icon: ByteArray? get() = files["filename"]

    /** The picture: "filename2", or any other picture sent besides the icon. */
    val image: ByteArray? get() = files["filename2"] ?: files.entries.firstOrNull { it.key != "filename" }?.value

    companion object {
        fun read(headers: Map<String, String>, input: InputStream): NoticeBody {
            val bytes = readBody(headers, input)
            val type = headers["content-type"].orEmpty()
            val body = when {
                type.contains("multipart/form-data", true) -> multipart(bytes, boundary(type) ?: guessBoundary(bytes)).let {
                    // A boundary in the header that the body doesn't use: the body's own
                    if (it.fields.isEmpty() && it.files.isEmpty()) multipart(bytes, guessBoundary(bytes)) else it
                }
                type.contains("application/x-www-form-urlencoded", true) -> urlEncoded(String(bytes, Charsets.UTF_8))
                else -> {
                    val text = String(bytes, Charsets.UTF_8).trim()
                    when {
                        text.startsWith("{") -> json(text)
                        text.startsWith("--") -> multipart(bytes, guessBoundary(bytes))
                        text.contains("=") -> urlEncoded(text)
                        text.isNotEmpty() -> NoticeBody(mapOf("msg" to text), emptyMap())
                        else -> NoticeBody(emptyMap(), emptyMap())
                    }
                }
            }
            val start = String(bytes, 0, minOf(bytes.size, 60), Charsets.ISO_8859_1).map { if (it.code in 32..126) it else '·' }.joinToString("")
            body.about = "${bytes.size} bytes, type ${type.substringBefore(';').ifBlank { "none" }}, " +
                "length ${headers["content-length"] ?: "none"}${headers["transfer-encoding"]?.let { ", $it" } ?: ""}" +
                (headers["expect"]?.let { ", expect $it" } ?: "") + (if (bytes.isNotEmpty()) ", starts “$start”" else "")
            return body
        }

        private fun readBody(headers: Map<String, String>, input: InputStream): ByteArray {
            val length = headers["content-length"]?.trim()?.toLongOrNull()
            if (length != null) {
                val out = ByteArrayOutputStream(length.coerceAtMost(MAX).toInt())
                val buf = ByteArray(16 * 1024)
                var left = length.coerceAtMost(MAX)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                return out.toByteArray()
            }
            if (headers["transfer-encoding"]?.contains("chunked", true) == true) return chunked(input)
            // No length given, and the body may come in a packet of its own: wait for it, then read
            // until it ends (a multipart form's closing boundary, or nothing more for a moment)
            val end = boundary(headers["content-type"].orEmpty())?.let { "--$it--".toByteArray(Charsets.ISO_8859_1) }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val started = System.currentTimeMillis()
            var lastData = started
            while (out.size() < MAX) {
                val ready = input.available()
                if (ready > 0) {
                    val n = input.read(buf, 0, minOf(ready, buf.size))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    lastData = System.currentTimeMillis()
                    if (end != null && indexOf(out.toByteArray(), end, maxOf(0, out.size() - n - end.size)) >= 0) break
                    continue
                }
                val now = System.currentTimeMillis()
                if (out.size() == 0 && now - started > 3000) break
                if (out.size() > 0 && now - lastData > 400) break
                Thread.sleep(20)
            }
            return out.toByteArray()
        }

        private fun chunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (out.size() < MAX) {
                val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) {
                    while (!line(input).isNullOrEmpty()) Unit
                    break
                }
                val buf = ByteArray(size)
                var got = 0
                while (got < size) {
                    val n = input.read(buf, got, size - got)
                    if (n < 0) return out.toByteArray()
                    got += n
                }
                out.write(buf)
                line(input)
            }
            return out.toByteArray()
        }

        private fun line(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        private fun boundary(type: String): String? =
            Regex("boundary\\s*=\\s*\"?([^\";,]+)\"?", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()

        private fun guessBoundary(bytes: ByteArray): String? {
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
            return head.lineSequence().firstOrNull()?.trim()?.takeIf { it.startsWith("--") }?.removePrefix("--")
        }

        private fun multipart(bytes: ByteArray, boundary: String?): NoticeBody {
            val fields = LinkedHashMap<String, String>()
            val files = LinkedHashMap<String, ByteArray>()
            if (boundary.isNullOrEmpty()) return NoticeBody(fields, files)
            val marker = "--$boundary".toByteArray(Charsets.ISO_8859_1)
            var pos = indexOf(bytes, marker, 0)
            while (pos >= 0) {
                var start = pos + marker.size
                // "--" after the boundary: the end
                if (start + 1 < bytes.size && bytes[start] == '-'.code.toByte() && bytes[start + 1] == '-'.code.toByte()) break
                if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start++
                if (start < bytes.size && bytes[start] == '\n'.code.toByte()) start++
                val next = indexOf(bytes, marker, start)
                if (next < 0) break
                var end = next
                if (end > start && bytes[end - 1] == '\n'.code.toByte()) end--
                if (end > start && bytes[end - 1] == '\r'.code.toByte()) end--
                part(bytes, start, end, fields, files)
                pos = next
            }
            return NoticeBody(fields, files)
        }

        private fun part(bytes: ByteArray, start: Int, end: Int, fields: MutableMap<String, String>, files: MutableMap<String, ByteArray>) {
            var split = indexOf(bytes, "\r\n\r\n".toByteArray(), start)
            var gap = 4
            if (split < 0 || split > end) {
                split = indexOf(bytes, "\n\n".toByteArray(), start)
                gap = 2
            }
            if (split < 0 || split > end) return
            val headers = String(bytes, start, split - start, Charsets.UTF_8)
            val data = bytes.copyOfRange(split + gap, end)
            var name: String? = null
            var fileName: String? = null
            var type: String? = null
            headers.lines().forEach { h ->
                when {
                    h.startsWith("content-disposition", true) -> {
                        name = Regex("(?<![a-z])name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                            ?: Regex("(?<![a-z])name\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                        fileName = Regex("filename\\s*=\\s*\"?([^\";]*)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)
                    }
                    h.startsWith("content-type", true) -> type = h.substringAfter(':').trim()
                }
            }
            val key = name ?: return
            val isFile = fileName != null || (type != null && !type!!.startsWith("text/", true))
            if (isFile) files[key] = data else fields[key] = String(data, Charsets.UTF_8)
        }

        private fun urlEncoded(text: String): NoticeBody {
            val fields = LinkedHashMap<String, String>()
            text.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                runCatching { fields[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") }
            }
            return NoticeBody(fields, emptyMap())
        }

        private fun json(text: String): NoticeBody {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return NoticeBody(emptyMap(), emptyMap())
            val fields = LinkedHashMap<String, String>()
            o.keys().forEach { k -> o.opt(k)?.takeIf { it != JSONObject.NULL }?.let { fields[k] = it.toString() } }
            return NoticeBody(fields, emptyMap())
        }

        private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
            if (pattern.isEmpty()) return -1
            var i = maxOf(from, 0)
            val last = data.size - pattern.size
            outer@ while (i <= last) {
                for (j in pattern.indices) if (data[i + j] != pattern[j]) { i++; continue@outer }
                return i
            }
            return -1
        }

        /** Pictures from Home Assistant are camera snapshots: 20 MB is plenty. */
        private const val MAX = 20L * 1024 * 1024
    }
}
