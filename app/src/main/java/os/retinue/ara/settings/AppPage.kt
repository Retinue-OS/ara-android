package os.retinue.ara.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The app's own settings page: assets/settings/ in the APK, served by the app
 * itself at a reserved address inside its WebViews, so it opens with or
 * without a server, on first run, and when the dashboard cannot load. Google
 * reserves `appassets.androidplatform.net` for exactly this (androidx.webkit's
 * WebViewAssetLoader uses it): nothing on the internet answers it, and the app
 * intercepts every request to it, so no request for it ever leaves the phone.
 *
 * The page embeds the current endpoint's `settings-instance.html` as its last
 * section, and the dashboard's gateway lets only this origin frame that page.
 * Its own Content-Security-Policy allows the page's files, that one frame and
 * nothing else; no one may frame the page itself. Framework-free and
 * unit-tested; AppPageServer answers the WebView with it.
 */
object AppPage {

    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"
    const val PATH = "/settings/"
    const val START_URL = "$ORIGIN${PATH}index.html"

    /** The folder of the page in the APK's assets. */
    const val ASSET_DIR = "settings"

    /** A file of the page: no folders, no dots in front, a type the app serves. */
    private val fileName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(html|css|mjs|js|svg|png)$")

    /** True for any URL on the reserved host, whatever its scheme, port or path. */
    fun isAppHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.host.equals(HOST, ignoreCase = true)
    }

    /** True for the settings page itself (and its files): https, the reserved host, its folder. */
    fun isAppPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.host.equals(HOST, ignoreCase = true) &&
            parsed.port == 443 && parsed.encodedPath.startsWith(PATH)
    }

    /**
     * The asset a request [path] maps to (`settings/<file>`), or null when the
     * app serves nothing there: anything outside the page's folder, in a
     * subfolder, or of a type the page does not use.
     */
    fun assetFor(path: String?): String? {
        if (path == null || !path.startsWith(PATH)) return null
        val name = path.removePrefix(PATH).ifEmpty { "index.html" }
        if (!fileName.matches(name)) return null
        return "$ASSET_DIR/$name"
    }

    /** The media type of an asset, by its extension. */
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "mjs", "js" -> "text/javascript"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    /** Text assets are UTF-8. */
    fun encodingFor(mime: String): String? = if (mime.startsWith("text/") || mime == "image/svg+xml") "utf-8" else null

    /**
     * The page's policy: its own files only; in a frame, only the current
     * endpoint's origin ([frameOrigin], null for none); no network access of its
     * own, and no one frames it.
     */
    fun contentSecurityPolicy(frameOrigin: String?): String {
        val frames = frameOrigin?.takeIf { isOrigin(it) } ?: "'none'"
        return "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "connect-src 'none'; frame-src $frames; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    }

    /** An `https://host[:port]` origin, with nothing a policy could misread. */
    private fun isOrigin(s: String): Boolean = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(s)

    /**
     * The URL the page opens at: at an endpoint's editor ([editId]), at a new
     * one ([add]), or at the top.
     */
    fun startUrl(editId: String? = null, add: Boolean = false): String = when {
        add -> "$START_URL#add"
        editId != null -> "$START_URL#edit=${java.net.URLEncoder.encode(editId, "UTF-8").replace("+", "%20")}"
        else -> START_URL
    }
}
