package os.retinue.ara.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import os.retinue.ara.BuildConfig
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.UrlRouter

/** What the WebView clients need from the activity hosting them. */
interface ShellHost {
    val router: UrlRouter
    val certificateAlias: String?

    fun onPageStarted(url: String?)
    fun onPageFinished(url: String?)
    fun onUrlChanged(url: String?)
    fun onHttpAuth(host: String, realm: String, handler: HttpAuthHandler)
    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onAudioPermission(request: PermissionRequest)
    fun onRenderProcessGone()
}

/** The user agent suffix of the contract (plan §4). */
const val SHELL_UA_SUFFIX = " RetinueShell/1 (Android)"

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

class ShellWebViewClient(private val context: Context, private val host: ShellHost) : WebViewClient() {

    private companion object {
        /** One thread for KeyChain lookups, shared by every WebView the shell creates. */
        val keyExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "client-cert").apply { isDaemon = true }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
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
        host.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        host.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        host.onUrlChanged(url)
    }

    /**
     * mTLS: present the KeyChain certificate the user selected — only to the
     * configured origin. Loading the key blocks, so it happens off the main thread.
     */
    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val alias = host.certificateAlias
        if (!host.router.isConfiguredHost(request.host, request.port)) {
            request.cancel()
            return
        }
        if (alias == null) {
            // Proceed without a certificate this time, without remembering it,
            // so selecting one later takes effect.
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

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
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
