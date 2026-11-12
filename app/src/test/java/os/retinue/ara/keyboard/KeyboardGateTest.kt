package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The gate every keyboard request of a page goes through (KeyboardLogic.decide),
 * the decision from the device's mode, and the parts of the consent that keep
 * state: the pause after confirmations not approved, the count of what was
 * typed unasked, the one window at a time.
 */
class KeyboardGateTest {

    private val ready = KeyboardStatus(available = true, enabled = true, registered = true, connected = true, host = KeyboardHost("Quest", "AA"))

    private val plain = PageRequest.Type("hello", enter = false, id = "t1")
    private val withEnter = PageRequest.Type("hello", enter = true, id = "t2")
    private val enterKey = PageRequest.Key("enter", id = "k1")
    private val secret = PageRequest.Secret("Wi-Fi", enter = false, id = "s1")

    private fun decide(
        request: PageRequest,
        serverAllows: Boolean = true,
        status: KeyboardStatus = ready,
        inFront: Boolean = true,
        windowOpen: Boolean = false,
        mode: ConfirmMode = ConfirmMode.OneLine,
        stopPending: Boolean = false,
        confirmPaused: Boolean = false,
    ) = KeyboardLogic.decide(request, serverAllows, status, inFront, windowOpen, mode, stopPending, confirmPaused)

    private fun refused(error: KeyboardError) = PageDecision.Refuse(error)

    @Test
    fun theServersSwitchComesFirstForEveryRequest() {
        for (r in listOf(plain, withEnter, enterKey, secret, PageRequest.Open, PageRequest.Cancel)) {
            // Whatever else holds: off for the page's server, every request is not-enabled.
            assertEquals(refused(KeyboardError.NotEnabled), decide(r, serverAllows = false), r.toString())
            assertEquals(
                refused(KeyboardError.NotEnabled),
                decide(r, serverAllows = false, status = KeyboardStatus(), inFront = false, windowOpen = true, mode = ConfirmMode.Never),
                r.toString(),
            )
        }
    }

    @Test
    fun thenTheChecksThatStoodBeforeInTheirOrder() {
        for (r in listOf(plain, enterKey, secret)) {
            assertEquals(refused(KeyboardError.NotEnabled), decide(r, status = ready.copy(enabled = false), inFront = false), r.toString())
            assertEquals(refused(KeyboardError.NotEnabled), decide(r, status = ready.copy(available = false)), r.toString())
            assertEquals(refused(KeyboardError.NotConnected), decide(r, status = ready.copy(connected = false), inFront = false), r.toString())
            assertEquals(refused(KeyboardError.NotInFront), decide(r, inFront = false, windowOpen = true), r.toString())
            // A confirmation or the password dialog open, or typing going on: busy.
            assertEquals(refused(KeyboardError.Busy), decide(r, windowOpen = true), r.toString())
            assertEquals(refused(KeyboardError.Busy), decide(r, status = ready.copy(busy = true)), r.toString())
        }
        assertEquals(refused(KeyboardError.TooLong), decide(PageRequest.Type("x".repeat(KeyboardLogic.MAX_TEXT + 1), false, null)))
        // Busy before too long, as the precheck had it.
        assertEquals(refused(KeyboardError.Busy), decide(PageRequest.Type("x".repeat(KeyboardLogic.MAX_TEXT + 1), false, null), windowOpen = true))
        // The checks come before the rule: a text that would be confirmed is refused before any window.
        assertEquals(refused(KeyboardError.NotInFront), decide(withEnter, inFront = false, mode = ConfirmMode.Always))
    }

    @Test
    fun theScreenAndStopNeedLess() {
        // The screen is where the keyboard is switched on: only the page in front is asked for.
        assertEquals(PageDecision.OpenScreen, decide(PageRequest.Open, status = KeyboardStatus(), windowOpen = true))
        assertEquals(refused(KeyboardError.NotInFront), decide(PageRequest.Open, inFront = false))
        // Stopping is always the page's, wherever it is.
        assertEquals(PageDecision.Cancel, decide(PageRequest.Cancel, status = KeyboardStatus(), inFront = false, windowOpen = true))
    }

    @Test
    fun thePasswordDialogIsItsOwnQuestion() {
        for (mode in ConfirmMode.entries) {
            assertEquals(PageDecision.OpenSecret, decide(secret, mode = mode, stopPending = true, confirmPaused = true), mode.toString())
        }
    }

    @Test
    fun theModeDecidesWhatIsAskedAbout() {
        // Ask every time.
        assertTrue(KeyboardLogic.needsConfirmation(ConfirmMode.Always, onlyWrites = true, stopPending = false))
        assertTrue(KeyboardLogic.needsConfirmation(ConfirmMode.Always, onlyWrites = false, stopPending = false))
        // One-line text by itself: only what acts.
        assertFalse(KeyboardLogic.needsConfirmation(ConfirmMode.OneLine, onlyWrites = true, stopPending = false))
        assertTrue(KeyboardLogic.needsConfirmation(ConfirmMode.OneLine, onlyWrites = false, stopPending = false))
        // Never ask.
        assertFalse(KeyboardLogic.needsConfirmation(ConfirmMode.Never, onlyWrites = true, stopPending = false))
        assertFalse(KeyboardLogic.needsConfirmation(ConfirmMode.Never, onlyWrites = false, stopPending = false))
        // After the strip's Stop, the next request asks in every mode.
        for (mode in ConfirmMode.entries) {
            for (writes in listOf(true, false)) assertTrue(KeyboardLogic.needsConfirmation(mode, writes, stopPending = true), "$mode $writes")
        }

        assertEquals(PageDecision.TypeNow, decide(plain))
        assertEquals(PageDecision.Confirm, decide(withEnter))
        assertEquals(PageDecision.Confirm, decide(enterKey))
        assertEquals(PageDecision.Confirm, decide(PageRequest.Type("a\nb", false, null)))
        assertEquals(PageDecision.Confirm, decide(PageRequest.Type("a ", false, null)))
        assertEquals(PageDecision.Confirm, decide(plain, mode = ConfirmMode.Always))
        assertEquals(PageDecision.TypeNow, decide(enterKey, mode = ConfirmMode.Never))
        assertEquals(PageDecision.TypeNow, decide(withEnter, mode = ConfirmMode.Never))
        assertEquals(PageDecision.Confirm, decide(plain, mode = ConfirmMode.Never, stopPending = true))
        // Every device starts at one-line text by itself.
        assertEquals(ConfirmMode.OneLine, ConfirmMode.DEFAULT)
        assertEquals(ConfirmMode.OneLine, ConfirmMode.fromKey(null))
        assertEquals(ConfirmMode.OneLine, ConfirmMode.fromKey("sometimes"))
        for (m in ConfirmMode.entries) assertEquals(m, ConfirmMode.fromKey(m.key))
    }

    @Test
    fun whileConfirmationsArePausedWhatWouldAskIsCancelledWithoutAWindow() {
        assertEquals(refused(KeyboardError.Cancelled), decide(withEnter, confirmPaused = true))
        assertEquals(refused(KeyboardError.Cancelled), decide(plain, mode = ConfirmMode.Always, confirmPaused = true))
        // What types by itself still does.
        assertEquals(PageDecision.TypeNow, decide(plain, confirmPaused = true))
        assertEquals(PageDecision.TypeNow, decide(enterKey, mode = ConfirmMode.Never, confirmPaused = true))
    }

    @Test
    fun threeNotApprovedInARowPauseForAMinute() {
        var now = 1_000L
        val streak = ConfirmStreak { now }
        assertFalse(streak.paused)
        streak.notApproved()
        streak.notApproved()
        assertFalse(streak.paused)
        // An approval breaks the row.
        streak.approved()
        streak.notApproved()
        streak.notApproved()
        assertFalse(streak.paused)
        streak.notApproved()
        assertTrue(streak.paused)
        now += ConfirmStreak.PAUSE_MS - 1
        assertTrue(streak.paused)
        now += 1
        assertFalse(streak.paused)
        // The row starts over after the pause: it takes three more.
        streak.notApproved()
        streak.notApproved()
        assertFalse(streak.paused)
        streak.notApproved()
        assertTrue(streak.paused)
        // A clock at zero (or below) pauses nothing by itself.
        assertFalse(ConfirmStreak { 0L }.paused)
    }

    @Test
    fun theCountIsPerDeviceAndResetByAnApprovalOrADisconnect() {
        assertEquals(5, KeyboardLogic.unconfirmedChars(plain))
        assertEquals(6, KeyboardLogic.unconfirmedChars(withEnter), "Enter after the text is one more")
        assertEquals(3, KeyboardLogic.unconfirmedChars(PageRequest.Type("a\r\nb", false, null)), "a CRLF line break is one, as in typed")
        assertEquals(1, KeyboardLogic.unconfirmedChars(enterKey))
        assertEquals(0, KeyboardLogic.unconfirmedChars(secret))
        val counts = UnconfirmedCounts()
        assertEquals(0, counts.of("AA"))
        assertEquals(0, counts.of(null))
        counts.add("AA", 5)
        counts.add("AA", 6)
        counts.add("BB", 1)
        counts.add("BB", 0)
        assertEquals(11, counts.of("AA"))
        assertEquals(1, counts.of("BB"))
        counts.reset("AA")
        assertEquals(0, counts.of("AA"))
        assertEquals(1, counts.of("BB"))
        counts.add("BB", Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, counts.of("BB"), "it saturates")
        counts.clear()
        assertEquals(0, counts.of("BB"))
    }

    @Test
    fun theConfirmationAndThePasswordDialogExcludeEachOther() {
        var now = 1_000L
        val windows = KeyboardWindows { now }
        assertFalse(windows.open)
        // The confirmation is asked for: from now on, a second one or a password dialog is not.
        assertTrue(windows.ask())
        assertTrue(windows.open)
        assertFalse(windows.ask())
        // Its activity comes up, and stays open however long it takes.
        val confirmation = {}
        windows.opened(confirmation)
        now += 10 * KeyboardLogic.WINDOW_OPENING_MS
        assertTrue(windows.open)
        assertFalse(windows.ask())
        // Another window's closing frees nothing.
        windows.closed {}
        assertTrue(windows.open)
        windows.closed(confirmation)
        assertFalse(windows.open)
        // The password dialog now; one asked for that never comes up frees the page after a while.
        assertTrue(windows.ask())
        now += KeyboardLogic.WINDOW_OPENING_MS - 1
        assertFalse(windows.ask())
        now += 1
        assertFalse(windows.open)
        // One whose request was answered first frees at once.
        assertTrue(windows.ask())
        windows.abandon()
        assertFalse(windows.open)
        // The page's Stop closes the one that is open.
        var closed = 0
        val dialog: () -> Unit = { closed++ }
        assertTrue(windows.ask())
        windows.opened(dialog)
        windows.close()
        assertEquals(1, closed)
        // A clock starting at zero still reserves.
        val early = KeyboardWindows { 0L }
        assertTrue(early.ask())
        assertTrue(early.open)
    }

    @Test
    fun aConfirmationEndsBeforeThePageStopsWaiting() {
        // The page waits 30 s (and 0.1 s a character) for a text, 15 s for a key.
        assertEquals(25_000L, KeyboardLogic.confirmTimeoutMs(plain))
        assertEquals(12_000L, KeyboardLogic.confirmTimeoutMs(enterKey))
    }
}
