package os.retinue.ara.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import os.retinue.ara.BuildConfig
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.shell.WebMessage

/**
 * The shell ↔ web bridge (plan §4), restricted to the configured origin.
 *
 * Preferred path (current WebViews): `addWebMessageListener` injects
 * `window.RetinueShellNative` into pages of the configured origin only, and
 * `addDocumentStartJavaScript` runs assets/retinue-shell.js there before any
 * page script, installing `window.RetinueShell` and the speech stand-in.
 *
 * Fallback (old WebViews without those features): `addJavascriptInterface`
 * (which every frame of every origin can see — so each call is checked against
 * the main frame's URL) and the script injected when a page starts and again
 * when it finishes loading (the script is idempotent).
 */
class ShellBridge(
    context: Context,
    private val webView: WebView,
    private val router: () -> UrlRouter,
    private val onMessage: (WebMessage) -> Unit,
) {
    private val script: String = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        .replace("__RETINUE_SHELL_VERSION__", BuildConfig.VERSION_NAME)

    private val main = Handler(Looper.getMainLooper())
    private val listenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false
    private var interfaceInstalled = false

    /** The main frame's current URL, readable from the JavaScript-interface thread. */
    @Volatile private var pageUrl: String? = null

    /**
     * (Re)install for the configured origin. Call before loading the first page
     * and whenever the base URL changes. With no origin configured nothing is
     * exposed.
     */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface", "RequiresFeature", "RestrictedApi")
    fun install() {
        val origin = router().origin?.toString()
        if (listenerSupported) {
            if (listenerInstalled) {
                WebViewCompat.removeWebMessageListener(webView, NATIVE)
                listenerInstalled = false
            }
            if (origin != null) {
                try {
                    WebViewCompat.addWebMessageListener(webView, NATIVE, setOf(origin)) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (isMainFrame && router().isConfiguredOrigin(sourceOrigin.toString())) {
                            ShellMessages.parse(message.data)?.let(onMessage)
                        }
                    }
                    listenerInstalled = true
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "origin rule refused: $origin", e)
                }
            }
        } else if (!interfaceInstalled) {
            webView.addJavascriptInterface(JsInterface(), NATIVE)
            interfaceInstalled = true
        }

        if (documentStartSupported && listenerInstalled) {
            scriptHandler?.remove()
            scriptHandler = null
            if (origin != null) {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
            }
        }
    }

    private val injectsOnLoad: Boolean get() = !(documentStartSupported && listenerInstalled)

    /** From the WebView client: a main-frame page started loading. */
    fun onPageStarted(url: String?) {
        pageUrl = url
        if (injectsOnLoad && router().isConfiguredOrigin(url)) webView.evaluateJavascript(script, null)
    }

    /** From the WebView client: a main-frame page finished loading. */
    fun onPageFinished(url: String?) {
        pageUrl = url
        if (injectsOnLoad && router().isConfiguredOrigin(url)) webView.evaluateJavascript(script, null)
    }

    /** The history changed without a load (pushState, hash change). */
    fun onUrlChanged(url: String?) {
        pageUrl = url
    }

    /**
     * Shell → web: `retinue-shell` event of [type] (`wake`, `home`).
     * [onUnhandled] runs (on the main thread) when the main frame is not on the
     * configured origin or the page has no listener for the event.
     */
    fun dispatchEvent(type: String, onUnhandled: (() -> Unit)? = null) {
        if (!router().isConfiguredOrigin(webView.url)) {
            onUnhandled?.invoke()
            return
        }
        webView.evaluateJavascript(ShellMessages.eventScript(type)) { result ->
            if (!ShellMessages.eventHandled(result)) onUnhandled?.invoke()
        }
    }

    /** Run [js] in the page — only while the main frame is on the configured origin. */
    fun run(js: String) {
        if (router().isConfiguredOrigin(webView.url)) webView.evaluateJavascript(js, null)
    }

    private inner class JsInterface {
        @JavascriptInterface
        fun postMessage(json: String?) {
            val parsed = ShellMessages.parse(json) ?: return
            main.post {
                // Any frame can reach this interface: honour only the main frame on the configured origin.
                if (router().isConfiguredOrigin(webView.url ?: pageUrl)) onMessage(parsed)
            }
        }
    }

    private companion object {
        const val TAG = "ShellBridge"
        const val ASSET = "retinue-shell.js"
        const val NATIVE = "RetinueShellNative"
    }
}
