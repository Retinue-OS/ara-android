package os.retinue.ara.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import os.retinue.ara.BuildConfig
import os.retinue.ara.net.AraHttp
import os.retinue.ara.net.BasicCredentials
import os.retinue.ara.settings.AppPage
import os.retinue.ara.settings.AppPageServer
import os.retinue.ara.shell.PageFailure
import os.retinue.ara.shell.PageStatusTracker
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.UrlRouter

/** What the WebView clients need from the activity hosting them. */
interface ShellHost {
    val router: UrlRouter
    val certificateAlias: String?

    /** The current endpoint's stored user name and password, opened for use; null when none. */
    fun storedCredentials(): BasicCredentials?

    fun onPageStarted(url: String?)
    fun onPageFinished(url: String?)
    fun onHttpAuth(host: String, realm: String, handler: HttpAuthHandler)
    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onAudioPermission(request: PermissionRequest)
    fun onRenderProcessGone()

    /** The main frame failed to load (see [PageFailure]); the shell shows why. */
    fun onPageFailed(failure: PageFailure)

    /** The main frame's load progress, 0–100. */
    fun onPageProgress(progress: Int)

    /** An ERROR-level console message of the page, kept for the diagnostics in settings. */
    fun onConsoleError(message: String, source: String, line: Int)
}

/**
 * The user agent suffix of the contract (plan §4); 5: the keyboard for other
 * devices (keyboardStatus/Type/Key/Secret/Open); 4: the app has a settings
 * page of its own and holds the dashboard's per-device preferences; 3: the
 * endpoint members of the bridge exist.
 */
const val SHELL_UA_SUFFIX = " RetinueShell/5 (Android)"

/** WebView configuration for the shell (plan §1). */
@SuppressLint("SetJavaScriptEnabled")
fun configureShellWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // A wake arrives without a tap: the page's chime and its Web Audio
        // voice-activity detector must be able to start without a gesture.
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = (userAgentString ?: WebSettings.getDefaultUserAgent(webView.context)) + SHELL_UA_SUFFIX
    }
    // Service workers are allowed by WebView by default; make sure nothing has
    // blocked network access for them (the web app's offline shell needs it).
    android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
        allowContentAccess = false
        allowFileAccess = false
        blockNetworkLoads = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
}

/** One thread for KeyChain lookups, shared by every WebView the shell creates. */
private val keyExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "client-cert").apply { isDaemon = true }
}

/**
 * mTLS: present the KeyChain certificate [alias] the user selected, only to
 * [router]'s configured origin; any other host is refused. Loading the key
 * blocks, so it happens off the main thread. Without a selected certificate
 * the request goes on without one, unremembered, so selecting one later takes
 * effect. Both the dashboard's WebView and the settings page's (for the
 * instance section it embeds) answer this way.
 */
fun answerClientCertRequest(context: Context, router: UrlRouter, alias: String?, request: ClientCertRequest) {
    if (!router.isConfiguredHost(request.host, request.port)) {
        request.cancel()
        return
    }
    if (alias == null) {
        request.ignore()
        return
    }
    keyExecutor.execute {
        try {
            val (key, chain) = AraHttp.clientKey(context, alias)
            request.proceed(key, chain)
        } catch (e: Exception) {
            request.ignore()
        }
    }
}

class ShellWebViewClient(private val context: Context, private val host: ShellHost) : WebViewClient() {

    /**
     * The stored password has answered a challenge of this page load: a second
     * challenge means it was wrong, and the dialog takes over rather than loop.
     */
    private var storedAuthUsed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // The app's own settings page lives in its own WebView, never in the dashboard's.
        if (AppPage.isAppHost(url)) return true
        return when (val route = host.router.route(url, request.isForMainFrame)) {
            Route.Stay -> false
            Route.Block -> true
            is Route.Browser -> {
                ShellIntents.openInBrowser(context, route.url)
                true
            }
            is Route.External -> {
                ShellIntents.openExternal(context, route.url)
                true
            }
            is Route.AndroidIntent -> {
                when (val fallback = ShellIntents.openIntentUrl(context, route.url, host.router)) {
                    // A same-origin fallback loads here.
                    is Route.Load -> view.post { view.loadUrl(fallback.url) }
                    is Route.Browser -> ShellIntents.openInBrowser(context, fallback.url)
                    else -> Unit
                }
                true
            }
            is Route.Load -> {
                view.post { view.loadUrl(route.url) }
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        storedAuthUsed = false
        host.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        host.onPageFinished(url)
    }

    /** A network-level failure of the main frame (DNS, connection, timeout); subresources do not count. */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        host.onPageFailed(
            PageFailure.Network(error.errorCode, error.description?.toString().orEmpty(), request.url.toString()),
        )
    }

    /** An error status for the main frame: a 403 with an empty body would otherwise just be white. */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame || errorResponse.statusCode < 400) return
        val status = errorResponse.statusCode
        host.onPageFailed(
            PageFailure.Http(status, PageStatusTracker.reasonPhrase(status, errorResponse.reasonPhrase), request.url.toString()),
        )
    }

    /**
     * A certificate WebView does not trust is never accepted. The configured
     * origin's own failure is reported, so the shell says why the page is blank.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        if (host.router.isConfiguredOrigin(error.url)) {
            host.onPageFailed(PageFailure.Ssl(error.primaryError, error.url))
        }
    }


    /**
     * Nothing is served on the app's reserved address here, and no request for
     * it leaves the phone: the dashboard's pages cannot load or frame the app's
     * settings page (AppPageServer).
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        AppPageServer.refuse(request.url)

    /** mTLS for the configured origin (see [answerClientCertRequest]). */
    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        answerClientCertRequest(context, host.router, host.certificateAlias, request)
    }

    /**
     * HTTP authentication, only for the configured host (a frame of another
     * origin must not ask for the user's password in the shell's name; the
     * callback carries no port, so the host decides): the endpoint's stored
     * user name and password answer the first challenge of a page load by
     * themselves, the sign-in dialog any other.
     */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
        val router = this.host.router
        if (!router.isConfiguredHost(host, router.origin?.port ?: -1)) {
            handler.cancel()
            return
        }
        if (!storedAuthUsed) {
            val stored = this.host.storedCredentials()
            if (stored != null) {
                storedAuthUsed = true
                handler.proceed(stored.username, stored.password)
                return
            }
        }
        this.host.onHttpAuth(host.orEmpty(), realm.orEmpty(), handler)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The renderer crashed or was killed for memory: the home screen must
        // survive that, so the activity replaces the WebView.
        host.onRenderProcessGone()
        return true
    }
}

class ShellChromeClient(private val host: ShellHost) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = host.onFileChooser(filePathCallback, fileChooserParams)

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.onPageProgress(newProgress)
    }

    /** Page errors are recorded for the diagnostics; the default logging stays (false). */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            host.onConsoleError(
                consoleMessage.message().orEmpty(),
                consoleMessage.sourceId().orEmpty(),
                consoleMessage.lineNumber(),
            )
        }
        return false
    }

    /** Only the microphone, only for the configured origin (decided by the host). */
    override fun onPermissionRequest(request: PermissionRequest) {
        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (wantsAudio && host.router.isConfiguredOrigin(request.origin.toString())) {
            host.onAudioPermission(request)
        } else {
            request.deny()
        }
    }
}
