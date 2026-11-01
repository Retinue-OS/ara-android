package os.retinue.ara.shell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a `retinue-shell:` link asks of the shell. */
sealed class LinkTarget {
    /**
     * Load [url] in the WebView: it is on the configured origin. The URL is
     * the canonical form of what the link carried (fragment included), with
     * the host as the origin check saw it (see [ShellLinks.target]).
     */
    data class Open(val url: String) : LinkTarget()

    /**
     * The link is for [origin], which is not the configured one. Nothing is
     * loaded and nothing is set up: the user is only told.
     */
    data class OtherOrigin(val origin: String) : LinkTarget()

    /** Not a link the shell understands; ignored with a short message. */
    data object Malformed : LinkTarget()
}

/**
 * "Open this dashboard URL in the app" links (contract §1). A page in an
 * ordinary browser builds
 * `intent://open?url=<encoded URL>#Intent;scheme=retinue-shell;package=…;S.browser_fallback_url=…;end`,
 * which Chrome hands to the app as `retinue-shell://open?url=<encoded URL>`;
 * the opaque form `retinue-shell:open?url=…` is accepted too. The URL is
 * loaded only when its origin is exactly the configured one (scheme, host and
 * port, default ports filled in; http is never upgraded to https). There are
 * no App Links: the origin is configured at runtime, so the check is ours.
 *
 * What loads is the URL as the parser that made the check writes it, never
 * the link's own spelling: the WebView would canonicalise a host of its own
 * (`straße.example`, or the same percent-encoded) by other rules than the
 * parser (IDNA 2008 against IDNA 2003), so the spelling could pass the check
 * as one host and load as another. A URL the parser does not take as a whole
 * is refused (no authority-only fallback here, unlike [ShellUrls.origin]).
 * Framework-free, so it is unit-tested.
 */
object ShellLinks {

    const val SCHEME = "retinue-shell"

    /** True when [raw] is a `retinue-shell:` link (of any shape). */
    fun isShellLink(raw: String?): Boolean = raw != null && UrlRouter.schemeOf(raw.trim()) == SCHEME

    /** Decide what the link [raw] does for the configured [base] URL (null: none configured). */
    fun target(raw: String?, base: String?): LinkTarget {
        val url = targetUrl(raw) ?: return LinkTarget.Malformed
        val parsed = url.toHttpUrlOrNull() ?: return LinkTarget.Malformed
        val origin = Origin(parsed.scheme, parsed.host.lowercase(), parsed.port)
        return if (UrlRouter(base).origin == origin) {
            LinkTarget.Open(parsed.toString())
        } else {
            LinkTarget.OtherOrigin(origin.toString())
        }
    }

    /**
     * The decoded `url` parameter of an `open` link, or null when the link is
     * not one: another scheme or action, no or several `url` parameters, or a
     * value that is not an absolute http(s) URL.
     */
    fun targetUrl(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (!isShellLink(s)) return null
        // Past "retinue-shell:"; a fragment of the link itself means nothing here.
        var rest = s.substring(SCHEME.length + 1).substringBefore('#')
        if (rest.startsWith("//")) rest = rest.substring(2)
        if (!rest.contains('?')) return null
        val action = rest.substringBefore('?').trimEnd('/')
        if (!action.equals("open", ignoreCase = true)) return null
        val values = rest.substringAfter('?').split('&')
            .filter { it.substringBefore('=') == "url" }
            .map { it.substringAfter('=', "") }
        if (values.size != 1) return null
        val url = percentDecode(values[0]) ?: return null
        if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val scheme = UrlRouter.schemeOf(url)
        if (scheme != "https" && scheme != "http") return null
        if (!url.substring(scheme.length + 1).startsWith("//")) return null
        return url
    }

    /**
     * RFC 3986 percent-decoding as UTF-8. A `+` stays a `+` (this is not form
     * encoding: `encodeURIComponent` writes a space as `%20`). Null for a
     * broken escape or invalid UTF-8.
     */
    fun percentDecode(s: String): String? {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                // Keep a surrogate pair together.
                val end = if (c.isHighSurrogate() && i + 1 < s.length) i + 2 else i + 1
                bytes.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
