package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.shell.ShellDecisions.BackAction
import os.retinue.ara.shell.ShellDecisions.IntentAction
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
        assertEquals(WakeDelivery.DispatchEvent, ShellDecisions.wakeDelivery(baseConfigured = true, pageLoadedOnOrigin = true))
        assertEquals(WakeDelivery.LoadWakeUrl, ShellDecisions.wakeDelivery(baseConfigured = true, pageLoadedOnOrigin = false))
        assertEquals(WakeDelivery.OpenSettings, ShellDecisions.wakeDelivery(baseConfigured = false, pageLoadedOnOrigin = false))
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
            "window.dispatchEvent(new CustomEvent('retinue-shell',{detail:{\"type\":\"wake\"}}));",
            ShellMessages.eventScript("wake"),
        )
    }

    @Test
    fun receiveScriptsEscapeText() {
        val s = ShellMessages.speechError(3, "it's \"bad\"\n")
        assertTrue(s.startsWith("window.__retinueShellReceive&&window.__retinueShellReceive({"))
        assertTrue(s.contains("\"error\":\"it's \\\"bad\\\"\\n\""))
        val v = ShellMessages.voices(listOf(ShellVoice("de-de-x-nfh-local", "de-DE", localService = true, isDefault = false)))
        assertTrue(v.contains("\"voices\":[{\"name\":\"de-de-x-nfh-local\",\"lang\":\"de-DE\",\"localService\":true,\"default\":false}]"))
    }
}
