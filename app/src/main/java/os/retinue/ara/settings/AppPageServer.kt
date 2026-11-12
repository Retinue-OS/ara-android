package os.retinue.ara.settings

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Answers the WebViews for the app's reserved address (see [AppPage]). The
 * settings page's WebView gets the page's files from the APK, with its
 * Content-Security-Policy; anything else on that host is a 404, in every
 * WebView, so no request for it ever reaches the network. Called on the
 * WebView's background thread.
 */
object AppPageServer {

    /**
     * The settings page's WebView: a file of the page, or a 404 for anything
     * else on the reserved host; null (the network) for any other host, such as
     * the instance section's frame. [frameOrigin] is the only origin the page
     * may frame (the current endpoint's).
     */
    fun respond(context: Context, url: Uri, frameOrigin: String?): WebResourceResponse? {
        if (!AppPage.HOST.equals(url.host, ignoreCase = true)) return null
        val https = url.scheme == "https" && (url.port == -1 || url.port == 443)
        val asset = if (https) AppPage.assetFor(url.encodedPath) else null
        asset ?: return notFound()
        val stream = try {
            context.assets.open(asset)
        } catch (_: IOException) {
            return notFound()
        }
        val mime = AppPage.mimeFor(asset)
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Referrer-Policy"] = "no-referrer"
        if (mime == "text/html") headers["Content-Security-Policy"] = AppPage.contentSecurityPolicy(frameOrigin)
        return WebResourceResponse(mime, AppPage.encodingFor(mime), 200, "OK", headers, stream)
    }

    /** Every other WebView: nothing on the reserved host; null (unchanged) for any other. */
    fun refuse(url: Uri): WebResourceResponse? =
        if (AppPage.HOST.equals(url.host, ignoreCase = true)) notFound() else null

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
    )
}
