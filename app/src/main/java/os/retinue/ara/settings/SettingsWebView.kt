package os.retinue.ara.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import os.retinue.ara.net.BasicCredentials
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.web.SHELL_UA_SUFFIX
import os.retinue.ara.web.ShellIntents
import os.retinue.ara.web.ShellWebView
import os.retinue.ara.web.answerClientCertRequest

/** What the settings page's WebView needs from the window showing it. */
interface SettingsPageHost {
    /** The current endpoint's: the instance section comes from its origin. */
    val router: UrlRouter

    /** The current endpoint's client certificate, for the instance section. */
    val certificateAlias: String?

    /** The current endpoint's stored user name and password, opened for use; null when none. */
    fun storedCredentials(): BasicCredentials?

    /** What the user did on the page (see [SettingsMessages]). */
    fun onSettingsAction(action: SettingsAction)

    /** A link of the page (its instance section) to a page of the dashboard ([url], on the configured origin). */
    fun openInDashboard(url: String)

    /** The page's renderer is gone: this page is done, and the window puts a new one in its place. */
    fun onSettingsPageGone()
}

/**
 * The settings page's own WebView, one per window while its settings are up:
 * the app's page ([AppPage], served from the APK by [AppPageServer]) and, in a
 * frame, the current endpoint's instance section. Nothing else loads in it: a
 * link that leaves the page goes to the dashboard (the configured origin), to
 * the browser, or to the app it is for, as in the dashboard's WebView. The
 * frame is signed in as the dashboard is (client certificate, stored
 * password); a sign-in it cannot answer leaves the section to its link into
 * the dashboard, where the sign-in dialog is. Main thread, except
 * [AppPageServer]'s answers.
 */
class SettingsWebView(private val context: Context, private val host: SettingsPageHost) {

    val view: WebView = ShellWebView(context)
    val bridge = SettingsBridge(view, host::onSettingsAction)

    /** The origin the page was loaded for: the only one its policy lets it frame. Read on WebView's thread. */
    @Volatile
    private var frameOrigin: String? = null
    private var loaded = false
    private var destroyed = false

    init {
        configure()
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        bridge.install()
    }

    /**
     * Show [state] (a [SettingsMessages.state] message). The page loads at
     * [startUrl] the first time; when the current endpoint's origin changes,
     * it loads afresh, so its policy lets it frame the new one.
     */
    fun show(state: String, origin: String?, startUrl: String) {
        if (destroyed) return
        if (!loaded || origin != frameOrigin) {
            frameOrigin = origin
            val url = if (loaded) AppPage.START_URL else startUrl
            loaded = true
            view.loadUrl(url)
        }
        bridge.pushState(state)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        view.settings.apply {
            javaScriptEnabled = true
            // For the instance section: the dashboard's pages keep a few things in local storage.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = (userAgentString ?: WebSettings.getDefaultUserAgent(context)) + SHELL_UA_SUFFIX
        }
        // The instance section is a frame of another site here: cookies its
        // server (or a sign-in proxy in front of it) relies on must still work.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // The page's own background, so nothing flashes white while it loads.
        view.setBackgroundColor(BACKGROUND)
    }

    private inner class Client : WebViewClient() {

        /** The stored password answered a challenge since the page loaded: a second one gets no answer. */
        private var storedAuthUsed = false

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            AppPageServer.respond(context, request.url, frameOrigin)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) {
                // The instance section's own navigations stay in its frame; the
                // page's policy decides which origin it may show there.
                return request.url.scheme?.lowercase() !in FRAME_SCHEMES
            }
            if (AppPage.isAppPage(url)) return false
            if (AppPage.isAppHost(url)) return true
            when (val route = host.router.route(url, isMainFrame = true)) {
                Route.Stay -> host.openInDashboard(url)
                is Route.Load -> host.openInDashboard(route.url)
                Route.Block -> Unit
                is Route.Browser -> ShellIntents.openInBrowser(context, route.url)
                is Route.External -> ShellIntents.openExternal(context, route.url)
                is Route.AndroidIntent -> when (val fallback = ShellIntents.openIntentUrl(context, route.url, host.router)) {
                    is Route.Load -> host.openInDashboard(fallback.url)
                    is Route.Browser -> ShellIntents.openInBrowser(context, fallback.url)
                    else -> Unit
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            storedAuthUsed = false
        }

        /** mTLS for the configured origin, as in the dashboard's WebView. */
        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            answerClientCertRequest(context, host.router, host.certificateAlias, request)
        }

        /** The endpoint's stored password, once, for the configured host only; nothing else signs in here. */
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            val router = this@SettingsWebView.host.router
            if (!storedAuthUsed && router.isConfiguredHost(host, router.origin?.port ?: -1)) {
                val stored = this@SettingsWebView.host.storedCredentials()
                if (stored != null) {
                    storedAuthUsed = true
                    handler.proceed(stored.username, stored.password)
                    return
                }
            }
            handler.cancel()
        }

        /** A certificate WebView does not trust is never accepted. */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            host.onSettingsPageGone()
            return true
        }
    }

    /** Neither the page nor its frame needs the microphone, the camera or anything else. */
    private class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    private companion object {
        /** The page's background (assets/settings/settings.css, `--bg`). */
        const val BACKGROUND = 0xFF0B0D12.toInt()

        /** What the instance section's frame may navigate to by itself. */
        val FRAME_SCHEMES = setOf("https", "http", "about", "blob", "data")
    }
}
