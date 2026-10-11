package os.retinue.ara.shell

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A web origin: scheme, host and an explicit port (the scheme's default port
 * filled in), so `https://h` and `https://h:443` compare equal.
 */
data class Origin(val scheme: String, val host: String, val port: Int) {

    /**
     * The origin as a string: the form `addWebMessageListener` and
     * `addDocumentStartJavaScript` accept as an allowed-origin rule, and the
     * form a page's `location.origin` has (default ports omitted).
     */
    override fun toString(): String {
        // An IPv6 literal is written in brackets, as in a URL.
        val h = if (host.contains(':')) "[$host]" else host
        return if (port == defaultPort(scheme)) "$scheme://$h" else "$scheme://$h:$port"
    }

    companion object {
        fun defaultPort(scheme: String): Int = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }
}

/**
 * The shell's URL arithmetic, framework-free so it can be unit-tested: what the
 * configured base URL normalizes to, its origin, and the URLs the shell loads
 * (start page, wake page, launcher web paths).
 */
object ShellUrls {

    /**
     * Normalize what the user typed as the Retinue base URL: trimmed, no
     * trailing slash, `https://` assumed when no scheme is given. Returns null
     * for anything that is not an https URL with a host — such a value is
     * treated as "not configured". Plain http is refused: the shell allows no
     * cleartext traffic (see [isCleartextBase]).
     */
    fun normalizeBase(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        // Query and fragment have no meaning for a base URL.
        s = s.substringBefore('#').substringBefore('?').trimEnd('/')
        val origin = origin(s) ?: return null
        if (origin.scheme != "https") return null
        val path = pathOf(s) ?: return null
        return origin.toString() + path
    }

    /** True when [raw] names a plain-http base URL, which [normalizeBase] refuses. */
    fun isCleartextBase(raw: String?): Boolean =
        raw?.trim()?.let { UrlRouter.schemeOf(it) } == "http"

    /**
     * The origin of an absolute http(s) URL, or null when it has none. Parsed
     * the way a browser does (OkHttp's [HttpUrl]): Chromium leaves characters
     * such as `|`, `^`, `[]` and `{}` unescaped in a path, query or fragment,
     * and such a URL is still on its origin. Should the whole URL not parse,
     * its scheme and authority alone decide.
     */
    fun origin(url: String?): Origin? {
        if (url.isNullOrBlank()) return null
        val parsed = parse(url) ?: return null
        return Origin(parsed.scheme, parsed.host.lowercase(), parsed.port)
    }

    private fun parse(url: String): HttpUrl? {
        val s = url.trim()
        s.toHttpUrlOrNull()?.let { return it }
        // Fall back to scheme://authority (special schemes treat `\` as `/`).
        val sep = s.indexOf("://")
        if (sep <= 0) return null
        val authorityEnd = s.indexOfAny(charArrayOf('/', '\\', '?', '#'), startIndex = sep + 3)
            .let { if (it < 0) s.length else it }
        return s.substring(0, authorityEnd).toHttpUrlOrNull()
    }

    /** True when [url] is an http(s) URL on the same origin as [base]. */
    fun sameOrigin(base: String?, url: String?): Boolean {
        val a = origin(base) ?: return false
        val b = origin(url) ?: return false
        return a == b
    }

    /** The page the shell opens on launch: the web app's home. */
    fun startUrl(base: String): String = "$base/"

    /** The page a wake loads when the web app is not on screen (contract §4). */
    fun wakeUrl(base: String): String = "$base/?wake=1"

    /**
     * The absolute URL of a launcher "web page" entry. The path is resolved
     * under the base URL (a leading slash is optional); an absolute same-origin
     * URL is accepted as-is, anything else falls back to the start page.
     */
    fun webPathUrl(base: String, path: String): String {
        val p = path.trim()
        if (p.contains("://")) return if (sameOrigin(base, p)) p else startUrl(base)
        if (p.isEmpty() || p == "/") return startUrl(base)
        // "#threads", "?wake=1" and "contacts" all hang off the web app's root.
        return if (p.startsWith("/")) base + p else "$base/$p"
    }

    /**
     * True when [url] is the web app's start page under [base]: the base path
     * itself, `/` or `/index.html`, with any query or fragment.
     */
    fun isStartPage(base: String, url: String?): Boolean {
        if (url.isNullOrBlank() || !sameOrigin(base, url)) return false
        val basePath = pathOf(base) ?: return false
        val path = pathOf(url) ?: return false
        return path == basePath || path == "$basePath/index.html"
    }

    /** The URL's encoded path without a trailing slash ("" for the root), or null when it does not parse. */
    private fun pathOf(url: String): String? =
        url.trim().toHttpUrlOrNull()?.encodedPath?.trimEnd('/')
}
