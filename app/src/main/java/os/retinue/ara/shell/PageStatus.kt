package os.retinue.ara.shell

/** Why the main frame did not load. [url] is the page that failed, when known. */
sealed interface PageFailure {
    val url: String?

    /** The request never got a response: DNS, connection, timeout (WebViewClient.ERROR_* code). */
    data class Network(val code: Int, val description: String, override val url: String?) : PageFailure

    /** The server answered with an error status (400 and up). */
    data class Http(val status: Int, val reason: String, override val url: String?) : PageFailure

    /** The server's TLS certificate was refused ([primaryError] is an `SslError.SSL_*` constant). */
    data class Ssl(val primaryError: Int, override val url: String?) : PageFailure

    /**
     * The user cancelled the HTTP sign-in dialog: the page is left blank (the
     * 401 body), so it counts as a failure the user can retry from.
     */
    data class SignInCancelled(override val url: String?) : PageFailure
}

/** What the web area shows. */
sealed interface PageStatus {
    /** Nothing loaded yet. */
    data object Idle : PageStatus

    /** The main frame is loading; [progress] is 0–100. */
    data class Loading(val progress: Int) : PageStatus

    /** The main frame has finished loading. */
    data object Ready : PageStatus

    data class Failed(val failure: PageFailure) : PageStatus
}

/** An ERROR-level console message of the page, for the diagnostics in settings. */
data class ConsoleError(val message: String, val source: String, val line: Int)

/** A snapshot of [PageStatusTracker] for the UI. */
data class PageState(
    val status: PageStatus = PageStatus.Idle,
    /** The page last started or finished (or asked for), for display. */
    val url: String? = null,
    /** The last few console errors of the current document, oldest first. */
    val consoleErrors: List<ConsoleError> = emptyList(),
)

/**
 * The main frame's load status, driven by the WebView callbacks and
 * framework-free so its ordering rules are unit-tested.
 *
 * WebView reports an HTTP error (and often a network or TLS error) when the
 * response arrives, which is *before* `onPageStarted` of the document that
 * shows it. So a failure recorded before its page started survives that one
 * `onPageStarted` for the same URL; any other start clears it. A failure is
 * kept through `onPageFinished` (the error page "finishes" too), and the first
 * failure of a load wins — except a cancelled sign-in, which explains the 401
 * it leads to better than the 401 does.
 */
class PageStatusTracker(private val maxConsoleErrors: Int = 5) {

    private var loading = false
    private var progress = 0
    private var started = false
    private var url: String? = null

    /** The URL of the page between onPageStarted and onPageFinished, else null. */
    private var startedUrl: String? = null
    private var failure: PageFailure? = null

    /** The failure's own onPageStarted has been seen (the next start is a new page). */
    private var failureSeen = false
    private val console = ArrayDeque<ConsoleError>()

    val state: PageState
        get() = PageState(
            status = when {
                failure != null -> PageStatus.Failed(failure!!)
                loading -> PageStatus.Loading(progress)
                started -> PageStatus.Ready
                else -> PageStatus.Idle
            },
            url = url,
            consoleErrors = console.toList(),
        )

    /** The shell asks for [url] afresh (a retry): any failure is forgotten. */
    fun onLoadRequested(url: String) {
        failure = null
        failureSeen = false
        loading = true
        progress = 0
        started = true
        startedUrl = null
        this.url = url
    }

    fun onPageStarted(url: String?) {
        val f = failure
        if (f != null && !failureSeen && (f.url == null || f.url == url)) {
            // The document that shows this failure.
            failureSeen = true
        } else {
            failure = null
            failureSeen = false
        }
        loading = true
        progress = 0
        started = true
        startedUrl = url
        this.url = url
        // A new document: the previous one's errors no longer apply.
        console.clear()
    }

    fun onPageFinished(url: String?) {
        loading = false
        started = true
        startedUrl = null
        if (url != null) this.url = url
    }

    fun onProgress(progress: Int) {
        this.progress = progress.coerceIn(0, 100)
    }

    /** A main-frame failure (the caller filters out subresources). */
    fun onMainFrameFailure(failure: PageFailure) {
        if (this.failure != null) return
        record(failure)
    }

    /** The user cancelled the sign-in dialog for the configured host. */
    fun onSignInCancelled() {
        record(PageFailure.SignInCancelled(null))
    }

    fun onConsoleError(message: String, source: String, line: Int) {
        console.addLast(ConsoleError(message, source, line))
        while (console.size > maxConsoleErrors) console.removeFirst()
    }

    private fun record(failure: PageFailure) {
        this.failure = failure
        // Arrived during its own page (between start and finish), or ahead of it.
        failureSeen = failure.url != null && failure.url == startedUrl
        started = true
        if (failure.url != null) url = failure.url
    }

    companion object {
        /**
         * The standard reason phrase for [status], for an HTTP/2 response (which
         * carries none) or an empty one; "" when there is no common phrase.
         */
        fun reasonPhrase(status: Int, given: String?): String {
            if (!given.isNullOrBlank()) return given.trim()
            return when (status) {
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                408 -> "Request Timeout"
                429 -> "Too Many Requests"
                495 -> "SSL Certificate Error"
                496 -> "SSL Certificate Required"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> ""
            }
        }
    }
}
