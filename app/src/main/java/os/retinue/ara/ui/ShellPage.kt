package os.retinue.ara.ui

import android.annotation.SuppressLint
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.health.HealthSyncEvent
import os.retinue.ara.health.HealthSyncListener
import os.retinue.ara.health.HealthSyncRuntime
import os.retinue.ara.health.HealthSyncStatus
import os.retinue.ara.shell.PageState
import os.retinue.ara.shell.PageStatusTracker
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.web.NativeSpeech
import os.retinue.ara.web.ShellBridge
import os.retinue.ara.web.ShellChromeClient
import os.retinue.ara.web.ShellDownloads
import os.retinue.ara.web.ShellWebView
import os.retinue.ara.web.ShellWebViewClient
import os.retinue.ara.web.configureShellWebView

/**
 * The shell's one page, shared by its windows: the home screen
 * ([MainActivity]) and the split-screen window ([SplitShellActivity]). There
 * is a single WebView, with its bridge and speech, and what the shell knows
 * about the page; it moves to the window that comes to the front (its
 * [owner]), so both windows show the same page in the same state: the same
 * place, a voice conversation that keeps going, one connection. A window that
 * does not hold it shows a placeholder until it takes the page back.
 *
 * Each window has its own container for the page ([MainActivity.pageContainer],
 * in its Compose layout); the WebView is moved between the containers here,
 * never handed to Compose, so a window that lost it cannot lay it out or add
 * it back. It lives on a [MutableContextWrapper] whose base is the owner, as
 * its dialogs and the `<select>` popup need an activity; the WebView clients
 * are the owner's too. It is destroyed with the last window, as before there
 * were two. Main thread only.
 */
// Holding the WebView beyond one activity is the point; the last window to close destroys it.
@SuppressLint("StaticFieldLeak")
object ShellPage {

    /** The WebView (replaced when its renderer is gone); null while no window exists. */
    var webView by mutableStateOf<WebView?>(null)
        private set

    /** The window showing the page, and so the host of the WebView's callbacks. */
    var owner by mutableStateOf<MainActivity?>(null)
        private set

    lateinit var bridge: ShellBridge
        private set

    /** Null while no window exists. */
    var speech: NativeSpeech? = null
        private set

    /** The settings the page follows; applied by whichever window reads them first. */
    var settings = ShellSettings()
    var router = UrlRouter(null)

    /** The base URL the start page was loaded for. */
    var loadedBase: String? = null

    /** The main frame finished loading a page of the configured origin. */
    var pageReady = false

    /** Between onPageStarted and onPageFinished of the main frame. */
    var pageLoading = false

    /** The main frame's load status (progress bar, failure card, diagnostics). */
    private var tracker = PageStatusTracker()
    var state by mutableStateOf(PageState())
        private set

    private val windows = mutableListOf<MainActivity>()
    private val started = mutableListOf<MainActivity>()
    private val resumed = mutableListOf<MainActivity>()
    private var reportedVisible = false
    private var reportedResumed = false

    /**
     * A wake heard while a window is visible goes to the window showing the
     * page, or else to the visible one that came up last.
     */
    private val onWake: () -> Unit = {
        (owner?.takeIf { it in started } ?: started.lastOrNull { !it.parking })?.onDirectWake()
    }

    /**
     * Apply [change] to the tracker and publish its state to Compose. Inside
     * [change] the tracker is the innermost receiver, so `onPageStarted(url)`
     * there is the tracker's.
     */
    fun updateState(change: PageStatusTracker.() -> Unit) {
        tracker.change()
        state = tracker.state
    }

    /** Health sync status and events go to the page, whichever window shows it. */
    private val healthListener = object : HealthSyncListener {
        override fun onHealthStatus(status: HealthSyncStatus) {
            if (webView != null) bridge.healthStatusChanged(status)
        }

        override fun onHealthEvent(event: HealthSyncEvent) {
            if (webView != null) bridge.healthEvent(event)
        }
    }

    /** [window] was created: the first one creates the page and shows it. */
    fun open(window: MainActivity) {
        windows += window
        if (speech == null) speech = NativeSpeech(window) { js -> bridge.run(js) }
        if (webView == null) create(window)
        WakeRuntime.onWake = onWake
        HealthSyncRuntime.listener = healthListener
    }

    /**
     * [window] is destroyed. The page goes to a window that is left; with the
     * last one it is destroyed, and the next window starts afresh.
     */
    fun close(window: MainActivity) {
        windows -= window
        started -= window
        resumed -= window
        if (windows.isEmpty()) {
            if (WakeRuntime.onWake === onWake) WakeRuntime.onWake = null
            if (HealthSyncRuntime.listener === healthListener) HealthSyncRuntime.listener = null
            speech?.shutdown()
            speech = null
            webView?.let(::destroy)
            webView = null
            owner = null
            settings = ShellSettings()
            router = UrlRouter(null)
            loadedBase = null
            pageReady = false
            pageLoading = false
            tracker = PageStatusTracker()
            state = tracker.state
        } else if (owner === window) {
            take(windows.last())
        }
        reportWindows()
    }

    /**
     * [window] shows the page from now on: the WebView moves from the other
     * window's container to its own (that window shows the placeholder) and
     * its callbacks go to [window]. A sign-in dialog the other window had open
     * is cancelled, so the page there offers a retry.
     */
    fun take(window: MainActivity) {
        val view = webView ?: return
        if (owner === window) return
        val previous = owner
        // Out of the old window while the wrapper is still that window: the
        // WebView unregisters its callbacks from the context it registered with.
        view.clearFocus()
        (view.parent as? ViewGroup)?.removeView(view)
        bind(view, window)
        owner = window
        window.pageContainer.addView(view)
        if (window in resumed) view.onResume() else view.onPause()
        previous?.onPageLeft()
    }

    /** The renderer is gone: a fresh WebView for the owner, which loads the page again. */
    fun replaceWebView() {
        val window = owner ?: windows.lastOrNull() ?: return
        webView?.let(::destroy)
        create(window)
    }

    fun windowStarted(window: MainActivity) {
        if (window !in started) started += window
        reportWindows()
    }

    /**
     * A window that stops while the other one stays visible (a pop-up or
     * desktop window closed over the home screen) hands it the page; in a
     * plain switch the other one has taken it already. Not while it waits for
     * a file or the microphone for the page: that answer comes back to it.
     */
    fun windowStopped(window: MainActivity) {
        started -= window
        if (owner === window && !window.awaitsResult) started.lastOrNull { !it.parking }?.let(::take)
        reportWindows()
    }

    fun windowResumed(window: MainActivity) {
        if (window !in resumed) resumed += window
        if (owner === window) webView?.onResume()
        reportWindows()
    }

    fun windowPaused(window: MainActivity) {
        resumed -= window
        if (owner === window) webView?.onPause()
        reportWindows()
    }

    /**
     * Wake detection sees the windows as one shell: visible while any window
     * is, resumed while any is. Only changes are reported, as one window
     * stopping while the other starts is no change.
     */
    private fun reportWindows() {
        val visible = started.isNotEmpty()
        if (visible != reportedVisible) {
            reportedVisible = visible
            WakeRuntime.setShellVisible(visible)
        }
        val anyResumed = resumed.isNotEmpty()
        if (anyResumed != reportedResumed) {
            reportedResumed = anyResumed
            WakeRuntime.setShellResumed(anyResumed)
        }
    }

    private fun create(window: MainActivity) {
        val view = ShellWebView(MutableContextWrapper(window))
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        configureShellWebView(view)
        bind(view, window)
        bridge = ShellBridge(window, view, { router }, { HealthSyncRuntime.status.value }) { message ->
            owner?.onWebMessage(message)
        }
        owner = window
        webView = view
        window.pageContainer.addView(view)
    }

    private fun bind(view: WebView, window: MainActivity) {
        (view.context as MutableContextWrapper).baseContext = window
        view.webViewClient = ShellWebViewClient(window, window)
        view.webChromeClient = ShellChromeClient(window)
        view.setDownloadListener(ShellDownloads(window, window))
    }

    private fun destroy(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
        // Nothing of the last window stays reachable through the destroyed view.
        (view.context as MutableContextWrapper).baseContext = view.context.applicationContext
    }
}
