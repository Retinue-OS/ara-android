package os.retinue.ara.shell

/**
 * Where a navigation goes. Decided here, framework-free, and carried out by the
 * WebView client.
 */
sealed class Route {
    /** Let the WebView load it (same origin, or an in-page scheme). */
    data object Stay : Route()

    /** An http(s) page on another origin: the user's default browser. */
    data class Browser(val url: String) : Route()

    /** Load this URL in the WebView (a same-origin intent fallback). */
    data class Load(val url: String) : Route()

    /** An `intent:` URL: parsed safely by the platform side (see ShellIntents). */
    data class AndroidIntent(val url: String) : Route()

    /** Another scheme (`tel:`, `sms:`, `mailto:`, `geo:`, `market:`, …): Android's handler. */
    data class External(val url: String) : Route()

    /** Nothing happens (an unsafe or meaningless navigation). */
    data object Block : Route()
}

/**
 * Navigation and origin policy for the shell's WebView (plan §1): the configured
 * origin stays in the view, other web origins open in the browser, and other
 * schemes go to Android. Also answers the origin questions the WebView client
 * asks for permissions, client certificates and the bridge.
 */
class UrlRouter(base: String?) {

    private val baseUrl: String? = ShellUrls.normalizeBase(base)
    val origin: Origin? = ShellUrls.origin(baseUrl)

    /**
     * Decide a navigation. [isMainFrame] is false for an iframe's navigation:
     * embedded content (a map, a video) may come from any web origin and stays
     * inside its frame — only the top-level page is held to the configured
     * origin.
     */
    fun route(url: String, isMainFrame: Boolean = true): Route {
        val scheme = schemeOf(url) ?: return Route.Block
        return when (scheme) {
            "http", "https" -> when {
                !isMainFrame -> Route.Stay
                isConfiguredOrigin(url) -> Route.Stay
                else -> Route.Browser(url)
            }
            // In-page schemes the WebView handles itself.
            "about", "blob" -> Route.Stay
            // Top-level data: navigation is blocked (as Chrome does); in a frame it is content.
            "data" -> if (isMainFrame) Route.Block else Route.Stay
            "javascript" -> Route.Block
            // Local files and content URIs are never navigated to from the web.
            "file", "content" -> Route.Block
            "intent" -> if (isMainFrame) Route.AndroidIntent(url) else Route.Block
            else -> if (isMainFrame) Route.External(url) else Route.Block
        }
    }

    /**
     * Where an `intent:` URL's `browser_fallback_url` goes when no app handles the
     * intent: only http(s) is honoured (same origin loads in the view).
     */
    fun fallback(fallbackUrl: String?): Route {
        if (fallbackUrl.isNullOrBlank()) return Route.Block
        val scheme = schemeOf(fallbackUrl)
        if (scheme != "http" && scheme != "https") return Route.Block
        return if (isConfiguredOrigin(fallbackUrl)) Route.Load(fallbackUrl) else Route.Browser(fallbackUrl)
    }

    /** True for an http(s) URL on the configured origin. */
    fun isConfiguredOrigin(url: String?): Boolean {
        val o = origin ?: return false
        return ShellUrls.origin(url) == o
    }

    /**
     * True when a host (as the WebView reports it for a client-certificate
     * request) and port belong to the configured origin. The certificate is
     * only ever presented there. A port of -1 means "unknown" and matches the
     * scheme's default.
     */
    fun isConfiguredHost(host: String?, port: Int): Boolean {
        val o = origin ?: return false
        if (host.isNullOrEmpty() || !host.equals(o.host, ignoreCase = true)) return false
        return port == o.port || (port <= 0 && o.port == Origin.defaultPort(o.scheme))
    }

    companion object {
        /** The URL's scheme, lowercased, or null when it has no valid one. */
        fun schemeOf(url: String): String? {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            val s = url.substring(0, colon)
            if (!s[0].isLetter()) return null
            if (!s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
            return s.lowercase()
        }
    }
}
