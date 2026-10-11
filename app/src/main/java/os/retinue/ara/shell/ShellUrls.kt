package os.retinue.ara.shell

import java.net.URI

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
    override fun toString(): String =
        if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"

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
     * for anything that is not an http(s) URL with a host — such a value is
     * treated as "not configured".
     */
    fun normalizeBase(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        // Query and fragment have no meaning for a base URL.
        s = s.substringBefore('#').substringBefore('?').trimEnd('/')
        val origin = origin(s) ?: return null
        val path = pathOf(s)
        return origin.toString() + path
    }

    /** The origin of an absolute http(s) URL, or null when it has none. */
    fun origin(url: String?): Origin? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) Origin.defaultPort(scheme) else uri.port
        return Origin(scheme, host, port)
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

    private fun pathOf(url: String): String = try {
        URI(url).rawPath.orEmpty().trimEnd('/')
    } catch (_: Exception) {
        ""
    }
}
