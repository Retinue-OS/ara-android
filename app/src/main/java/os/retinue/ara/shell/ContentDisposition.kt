package os.retinue.ara.shell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/**
 * A response's `Content-Disposition` (RFC 6266), framework-free so it is
 * unit-tested. Android's `URLUtil.guessFileName` takes a name only from a bare
 * `attachment; filename="…"`: not from `inline`, and not when the RFC 6266
 * `filename*` that carries a non-ASCII name follows it, so a Retinue file saved
 * under its attachment id. The shell reads the header itself.
 *
 * [inline] is the page asking to see the file rather than save it (the web
 * app's view link); [filename] is `filename*` when it decodes, else `filename`,
 * else null. The name is the server's raw word: [FileNames.sanitize] it.
 */
data class ContentDisposition(val inline: Boolean, val filename: String?) {

    companion object {

        /** Null for a missing or blank header. An unknown type counts as `attachment` (RFC 6266, 4.2). */
        fun parse(header: String?): ContentDisposition? {
            if (header.isNullOrBlank()) return null
            val parts = split(header)
            // A header that starts with a parameter has no type: an attachment.
            val first = parts.first().trim()
            val type = if ('=' in first) "" else first.lowercase()
            val params = if (type.isEmpty()) parts else parts.drop(1)
            var plain: String? = null
            var extended: String? = null
            for (part in params) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val value = part.substring(eq + 1).trim()
                when (part.substring(0, eq).trim().lowercase()) {
                    "filename" -> if (plain == null) plain = unquote(value)
                    "filename*" -> if (extended == null) extended = extValue(value)
                }
            }
            val name = (extended ?: plain)?.takeIf { it.isNotBlank() }
            return ContentDisposition(inline = type == "inline", filename = name)
        }

        /** Split on `;` outside quoted strings. */
        private fun split(header: String): List<String> {
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var escaped = false
            for (c in header) {
                when {
                    escaped -> escaped = false
                    quoted && c == '\\' -> escaped = true
                    c == '"' -> quoted = !quoted
                    c == ';' && !quoted -> {
                        parts.add(cur.toString())
                        cur.setLength(0)
                        continue
                    }
                }
                cur.append(c)
            }
            parts.add(cur.toString())
            return parts
        }

        /** A token as is; a quoted string without its quotes and backslash escapes. */
        private fun unquote(value: String): String {
            if (!value.startsWith('"')) return value
            val out = StringBuilder()
            var escaped = false
            for (c in value.substring(1)) {
                when {
                    escaped -> {
                        out.append(c)
                        escaped = false
                    }
                    c == '\\' -> escaped = true
                    c == '"' -> break
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /**
         * An RFC 8187 ext-value, `charset'language'pct-encoded`; null when it
         * is malformed or in a charset other than UTF-8 and ISO-8859-1 (the two
         * the RFC names), so `filename` is used instead.
         */
        private fun extValue(value: String): String? {
            val first = value.indexOf('\'')
            val second = if (first < 0) -1 else value.indexOf('\'', first + 1)
            if (second < 0) return null
            val charset = when (value.substring(0, first).trim().lowercase()) {
                "utf-8" -> Charsets.UTF_8
                "iso-8859-1" -> Charsets.ISO_8859_1
                else -> return null
            }
            val bytes = percentDecode(value.substring(second + 1).trim().removeSurrounding("\"")) ?: return null
            return decode(bytes, charset)
        }

        private fun percentDecode(s: String): ByteArray? {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    // attr-chars are ASCII; anything else is not an ext-value.
                    if (c.code > 0x7e || c.code < 0x21) return null
                    out.write(c.code)
                    i++
                }
            }
            return out.toByteArray()
        }

        /** Strict: bytes that are not valid in [charset] make it null rather than replacement characters. */
        private fun decode(bytes: ByteArray, charset: Charset): String? = try {
            charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
