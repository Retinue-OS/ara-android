package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.shell.ShellDecisions.BackAction
import os.retinue.ara.shell.ShellDecisions.IntentAction
import os.retinue.ara.shell.ShellDecisions.LaunchTarget
import os.retinue.ara.shell.ShellDecisions.PendingWake
import os.retinue.ara.shell.ShellDecisions.SplitRequest
import os.retinue.ara.shell.ShellDecisions.UnhandledEvent
import os.retinue.ara.shell.ShellDecisions.WakeDelivery
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.ShellVoice
import os.retinue.ara.shell.WebMessage

class ShellDecisionsTest {

    @Test
    fun backPrefersOverlayThenHistory() {
        assertEquals(BackAction.CloseOverlay, ShellDecisions.onBack(overlayOpen = true, webCanGoBack = true, isHome = true))
        assertEquals(BackAction.WebBack, ShellDecisions.onBack(overlayOpen = false, webCanGoBack = true, isHome = true))
        assertEquals(BackAction.WebBack, ShellDecisions.onBack(overlayOpen = false, webCanGoBack = true, isHome = false))
    }

    @Test
    fun homeNeverLeaves() {
        assertEquals(BackAction.Ignore, ShellDecisions.onBack(overlayOpen = false, webCanGoBack = false, isHome = true))
        assertEquals(BackAction.Leave, ShellDecisions.onBack(overlayOpen = false, webCanGoBack = false, isHome = false))
    }

    @Test
    fun newIntents() {
        assertEquals(IntentAction.Wake, ShellDecisions.onNewIntent(isWake = true, isHomeCategory = false, alreadyInFront = true))
        assertEquals(IntentAction.HomeEvent, ShellDecisions.onNewIntent(isWake = false, isHomeCategory = true, alreadyInFront = true))
        assertEquals(IntentAction.Show, ShellDecisions.onNewIntent(isWake = false, isHomeCategory = true, alreadyInFront = false))
        assertEquals(IntentAction.Show, ShellDecisions.onNewIntent(isWake = false, isHomeCategory = false, alreadyInFront = true))
    }

    @Test
    fun wakeDelivery() {
        fun d(base: Boolean = true, loaded: Boolean = false, loading: Boolean = false) =
            ShellDecisions.wakeDelivery(baseConfigured = base, pageLoadedOnOrigin = loaded, startPageLoading = loading)
        assertEquals(WakeDelivery.DispatchEvent, d(loaded = true))
        assertEquals(WakeDelivery.DispatchEvent, d(loaded = true, loading = true))
        // Not loaded: the start page (no wake flag in the history), once.
        assertEquals(WakeDelivery.LoadStartPage, d())
        assertEquals(WakeDelivery.WaitForPage, d(loading = true))
        assertEquals(WakeDelivery.OpenSettings, d(base = false))
        assertEquals(WakeDelivery.OpenSettings, d(base = false, loaded = true, loading = true))
    }

    @Test
    fun wakeWaitsForThePageItLoadedThenGoesOut() {
        // The page-load path: accepted, start page loaded, waiting, then the event.
        assertEquals(PendingWake.Deliver, ShellDecisions.pendingWake(1_000, true, true, true, false))
        assertEquals(WakeDelivery.LoadStartPage, ShellDecisions.wakeDelivery(true, false, false))
        assertEquals(WakeDelivery.WaitForPage, ShellDecisions.wakeDelivery(true, false, true))
        assertEquals(WakeDelivery.DispatchEvent, ShellDecisions.wakeDelivery(true, true, true))
        // A page that never comes up within the window: the wake is dropped.
        assertEquals(PendingWake.Drop, ShellDecisions.pendingWake(ShellDecisions.PENDING_WAKE_MAX_MS + 1, true, true, true, false))
    }
}

class ShellMessagesTest {

    @Test
    fun parsesBridgeCalls() {
        assertEquals(WebMessage.VoiceActive(true), ShellMessages.parse("""{"type":"voiceActive","active":true}"""))
        assertEquals(WebMessage.VoiceActive(false), ShellMessages.parse("""{"type":"voiceActive","active":false}"""))
        assertEquals(WebMessage.OpenSettings, ShellMessages.parse("""{"type":"openSettings"}"""))
        assertEquals(WebMessage.StopSpeaking, ShellMessages.parse("""{"type":"tts.cancel"}"""))
        assertEquals(WebMessage.StopSpeaking, ShellMessages.parse("""{"type":"tts.pause"}"""))
        assertEquals(WebMessage.Voices, ShellMessages.parse("""{"type":"tts.voices"}"""))
    }

    @Test
    fun parsesSpeak() {
        val m = ShellMessages.parse(
            """{"type":"tts.speak","id":7,"text":"Grüezi","lang":"de-CH","voice":"","rate":1.5,"pitch":1,"volume":0.5}""",
        )
        assertEquals(WebMessage.Speak(7, "Grüezi", "de-CH", null, 1.5f, 1f, 0.5f), m)
        val clamped = ShellMessages.parse("""{"type":"tts.speak","id":1,"text":"x","rate":99,"volume":-1}""")
        assertEquals(WebMessage.Speak(1, "x", null, null, 10f, 1f, 0f), clamped)
    }

    @Test
    fun ignoresGarbage() {
        assertNull(ShellMessages.parse(null))
        assertNull(ShellMessages.parse(""))
        assertNull(ShellMessages.parse("nope"))
        assertNull(ShellMessages.parse("[1,2]"))
        assertNull(ShellMessages.parse("""{"type":"unknown"}"""))
        assertNull(ShellMessages.parse("""{"type":"tts.speak","text":"no id"}"""))
        assertNull(ShellMessages.parse("""{"type":{"nested":1}}"""))
    }

    @Test
    fun eventScriptMatchesTheContract() {
        assertEquals(
            "(function(){var e=new CustomEvent('retinue-shell',{detail:{\"type\":\"wake\"},cancelable:true});" +
                "window.dispatchEvent(e);" +
                "return e.defaultPrevented||!!(window.__retinueShellTaken&&window.__retinueShellTaken(e));})();",
            ShellMessages.eventScript("wake"),
        )
    }

    @Test
    fun eventHandledResult() {
        assertTrue(ShellMessages.eventHandled("true"))
        assertFalse(ShellMessages.eventHandled("false"))
        assertFalse(ShellMessages.eventHandled("null"))
        assertFalse(ShellMessages.eventHandled(null))
    }

    @Test
    fun receiveScriptsEscapeText() {
        val s = ShellMessages.speechError(3, "it's \"bad\"\n")
        assertTrue(s.startsWith("window.__retinueShellReceive&&window.__retinueShellReceive({"))
        assertTrue(s.contains("\"error\":\"it's \\\"bad\\\"\\n\""))
        val v = ShellMessages.voices(listOf(ShellVoice("de-de-x-nfh-local", "de-DE", localService = true, isDefault = false)))
        assertTrue(v.contains("\"voices\":[{\"name\":\"de-de-x-nfh-local\",\"lang\":\"de-DE\",\"localService\":true,\"default\":false}]"))
    }

    @Test
    fun windowFocusLossIsKeptFromTheWebViewWhileTheKeyboardIsUp() {
        assertFalse(ShellDecisions.passWindowFocusChange(hasFocus = false, keyboardUp = true))
        assertTrue(ShellDecisions.passWindowFocusChange(hasFocus = false, keyboardUp = false))
        assertTrue(ShellDecisions.passWindowFocusChange(hasFocus = true, keyboardUp = true))
        assertTrue(ShellDecisions.passWindowFocusChange(hasFocus = true, keyboardUp = false))
    }
}

class WakeTimingTest {

    private fun decide(
        age: Long = 0,
        loaded: Boolean = true,
        resumed: Boolean = true,
        focused: Boolean = true,
        locked: Boolean = false,
        multiWindow: Boolean = false,
    ) = ShellDecisions.pendingWake(age, loaded, resumed, focused, locked, multiWindow)

    @Test
    fun deliversOnlyToAVisibleFocusedShell() {
        assertEquals(PendingWake.Deliver, decide())
        assertEquals(PendingWake.Wait, decide(resumed = false))
        assertEquals(PendingWake.Wait, decide(focused = false))
        assertEquals(PendingWake.Wait, decide(locked = true))
        assertEquals(PendingWake.Wait, decide(loaded = false))
    }

    @Test
    fun inSplitScreenThePagesHalfNeedsNoFocus() {
        // The other app has focus; Ara's half is resumed and visible, and can take the mic.
        assertEquals(PendingWake.Deliver, decide(focused = false, multiWindow = true))
        assertEquals(PendingWake.Wait, decide(focused = false, multiWindow = true, resumed = false))
        assertEquals(PendingWake.Wait, decide(focused = false, multiWindow = true, locked = true))
    }

    @Test
    fun staleWakesAreDropped() {
        assertEquals(PendingWake.Deliver, decide(age = ShellDecisions.PENDING_WAKE_MAX_MS))
        assertEquals(PendingWake.Drop, decide(age = ShellDecisions.PENDING_WAKE_MAX_MS + 1))
        assertEquals(PendingWake.Drop, decide(age = ShellDecisions.PENDING_WAKE_MAX_MS + 1, resumed = false))
    }

    @Test
    fun replayedIntents() {
        assertFalse(ShellDecisions.isReplayedIntent(restored = false, launchedFromHistory = false))
        assertTrue(ShellDecisions.isReplayedIntent(restored = true, launchedFromHistory = false))
        assertTrue(ShellDecisions.isReplayedIntent(restored = false, launchedFromHistory = true))
    }

    @Test
    fun unhandledEvents() {
        assertEquals(UnhandledEvent.LoadWake, ShellDecisions.onUnhandledEvent(isWake = true, onStartPage = true))
        assertEquals(UnhandledEvent.LoadWake, ShellDecisions.onUnhandledEvent(isWake = true, onStartPage = false))
        assertEquals(UnhandledEvent.LoadStart, ShellDecisions.onUnhandledEvent(isWake = false, onStartPage = false))
        assertEquals(UnhandledEvent.Nothing, ShellDecisions.onUnhandledEvent(isWake = false, onStartPage = true))
    }
}

class SplitScreenDecisionsTest {

    @Test
    fun theSplitWindowStartsEverythingBesideItself() {
        for (splitLaunch in listOf(true, false)) {
            for (alternate in listOf(true, false)) {
                assertEquals(LaunchTarget.Beside, ShellDecisions.launchTarget(splitWindow = true, splitLaunch, alternate))
            }
        }
    }

    @Test
    fun theHomeScreenFollowsTheSettingAndTheLongPressFlipsIt() {
        fun home(splitLaunch: Boolean, alternate: Boolean) =
            ShellDecisions.launchTarget(splitWindow = false, splitLaunch = splitLaunch, alternate = alternate)
        assertEquals(LaunchTarget.Here, home(splitLaunch = false, alternate = false))
        assertEquals(LaunchTarget.ThroughSplitWindow, home(splitLaunch = false, alternate = true))
        assertEquals(LaunchTarget.ThroughSplitWindow, home(splitLaunch = true, alternate = false))
        assertEquals(LaunchTarget.Here, home(splitLaunch = true, alternate = true))
    }

    @Test
    fun splitWindowRequests() {
        assertEquals(SplitRequest.Open, ShellDecisions.splitRequest(hasEntry = true, park = false, replayed = false))
        assertEquals(SplitRequest.Park, ShellDecisions.splitRequest(hasEntry = false, park = true, replayed = false))
        assertEquals(SplitRequest.Show, ShellDecisions.splitRequest(hasEntry = false, park = false, replayed = false))
        // A relaunch from Recents repeats the creating intent: it neither opens the app again nor parks.
        assertEquals(SplitRequest.Show, ShellDecisions.splitRequest(hasEntry = true, park = false, replayed = true))
        assertEquals(SplitRequest.Show, ShellDecisions.splitRequest(hasEntry = false, park = true, replayed = true))
    }

    @Test
    fun theSplitWindowIsPutBackInRecentsOnlyWhenMissingAndNotTooOften() {
        assertTrue(ShellDecisions.parkSplitWindow(keepInRecents = true, inRecents = false, sinceLastAttemptMs = null))
        assertFalse(ShellDecisions.parkSplitWindow(keepInRecents = false, inRecents = false, sinceLastAttemptMs = null))
        assertFalse(ShellDecisions.parkSplitWindow(keepInRecents = true, inRecents = true, sinceLastAttemptMs = null))
        // An attempt that did not take is not repeated on every resume.
        assertFalse(ShellDecisions.parkSplitWindow(true, false, sinceLastAttemptMs = ShellDecisions.PARK_RETRY_MS - 1))
        assertTrue(ShellDecisions.parkSplitWindow(true, false, sinceLastAttemptMs = ShellDecisions.PARK_RETRY_MS))
    }
}
