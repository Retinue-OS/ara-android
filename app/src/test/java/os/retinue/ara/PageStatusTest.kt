package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import os.retinue.ara.shell.PageFailure
import os.retinue.ara.shell.PageStatus
import os.retinue.ara.shell.PageStatusTracker

class PageStatusTest {

    private val home = "https://agents.example.com/"
    private val other = "https://agents.example.com/contacts"

    @Test
    fun idleThenLoadingThenReady() {
        val t = PageStatusTracker()
        assertEquals(PageStatus.Idle, t.state.status)
        t.onPageStarted(home)
        t.onProgress(40)
        assertEquals(PageStatus.Loading(40), t.state.status)
        t.onProgress(140)
        assertEquals(PageStatus.Loading(100), t.state.status)
        t.onPageFinished(home)
        assertEquals(PageStatus.Ready, t.state.status)
        assertEquals(home, t.state.url)
    }

    @Test
    fun errorAfterStartWinsOverFinish() {
        val t = PageStatusTracker()
        t.onPageStarted(home)
        t.onMainFrameFailure(PageFailure.Network(-2, "net::ERR_NAME_NOT_RESOLVED", home))
        t.onPageFinished(home)
        assertEquals(PageStatus.Failed(PageFailure.Network(-2, "net::ERR_NAME_NOT_RESOLVED", home)), t.state.status)
    }

    @Test
    fun httpErrorAheadOfItsPageStartSurvivesThatStart() {
        val t = PageStatusTracker()
        t.onPageStarted(home)
        t.onPageFinished(home)
        // WebView reports the status when the response arrives, before the document commits.
        t.onMainFrameFailure(PageFailure.Http(403, "Forbidden", other))
        t.onPageStarted(other)
        t.onPageFinished(other)
        assertEquals(PageStatus.Failed(PageFailure.Http(403, "Forbidden", other)), t.state.status)
        // The next page clears it.
        t.onPageStarted(home)
        assertEquals(PageStatus.Loading(0), t.state.status)
    }

    @Test
    fun newPageStartClearsFailure() {
        val t = PageStatusTracker()
        t.onPageStarted(home)
        t.onMainFrameFailure(PageFailure.Ssl(3, home))
        t.onPageFinished(home)
        assertIs<PageStatus.Failed>(t.state.status)
        // Same URL again, but the failure's own start was already seen: a new load.
        t.onPageStarted(home)
        t.onPageFinished(home)
        assertEquals(PageStatus.Ready, t.state.status)
    }

    @Test
    fun startOfAnotherUrlClearsAnEarlyFailure() {
        val t = PageStatusTracker()
        t.onMainFrameFailure(PageFailure.Http(500, "Internal Server Error", other))
        t.onPageStarted(home)
        assertEquals(PageStatus.Loading(0), t.state.status)
    }

    @Test
    fun firstFailureWinsButSignInCancelledOverrides() {
        val t = PageStatusTracker()
        t.onPageStarted(home)
        t.onMainFrameFailure(PageFailure.Ssl(3, home))
        t.onMainFrameFailure(PageFailure.Network(-11, "net::ERR_SSL_PROTOCOL_ERROR", home))
        assertEquals(PageStatus.Failed(PageFailure.Ssl(3, home)), t.state.status)

        val u = PageStatusTracker()
        u.onSignInCancelled()
        // The 401 body that follows does not replace the clearer reason, and its start keeps it.
        u.onMainFrameFailure(PageFailure.Http(401, "Unauthorized", home))
        u.onPageStarted(home)
        u.onPageFinished(home)
        assertEquals(PageStatus.Failed(PageFailure.SignInCancelled(null)), u.state.status)
    }

    @Test
    fun retryForgetsFailure() {
        val t = PageStatusTracker()
        t.onMainFrameFailure(PageFailure.Http(403, "Forbidden", home))
        t.onLoadRequested(home)
        assertEquals(PageStatus.Loading(0), t.state.status)
        t.onPageStarted(home)
        t.onPageFinished(home)
        assertEquals(PageStatus.Ready, t.state.status)
    }

    @Test
    fun consoleErrorsAreBoundedAndClearedPerDocument() {
        val t = PageStatusTracker(maxConsoleErrors = 3)
        t.onPageStarted(home)
        for (i in 1..5) t.onConsoleError("e$i", "app.js", i)
        assertEquals(listOf("e3", "e4", "e5"), t.state.consoleErrors.map { it.message })
        assertEquals(5, t.state.consoleErrors.last().line)
        t.onPageFinished(home)
        assertEquals(PageStatus.Ready, t.state.status)
        assertEquals(3, t.state.consoleErrors.size)
        t.onPageStarted(other)
        assertEquals(emptyList(), t.state.consoleErrors)
    }

    @Test
    fun reasonPhraseFallsBackToTheStandardOne() {
        assertEquals("Forbidden", PageStatusTracker.reasonPhrase(403, ""))
        assertEquals("Forbidden", PageStatusTracker.reasonPhrase(403, null))
        assertEquals("Nope", PageStatusTracker.reasonPhrase(403, " Nope "))
        assertEquals("", PageStatusTracker.reasonPhrase(499, null))
    }
}
