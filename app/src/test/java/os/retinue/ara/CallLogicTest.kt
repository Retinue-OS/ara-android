package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.CallLogic
import os.retinue.ara.shell.CallOutcome
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.WebMessage

class CallLogicTest {

    @Test
    fun normalizesWhatPeopleWrite() {
        assertEquals("+41791234567", CallLogic.normalize("+41 79 123 45 67"))
        assertEquals("+41791234567", CallLogic.normalize("tel:+41-79-123-45-67"))
        assertEquals("0441234567", CallLogic.normalize(" (044) 123.45.67 "))
        assertEquals("0791234567", CallLogic.normalize("079/123 45 67"))
        assertEquals("*123#", CallLogic.normalize("*123#"))
        assertEquals("112", CallLogic.normalize("112"))
    }

    @Test
    fun refusesWhatIsNotANumber() {
        assertNull(CallLogic.normalize(null))
        assertNull(CallLogic.normalize(""))
        assertNull(CallLogic.normalize("Anna"))
        assertNull(CallLogic.normalize("12"))
        assertNull(CallLogic.normalize("1".repeat(21)))
        assertNull(CallLogic.normalize("41+79"))
        assertNull(CallLogic.normalize("++41791234567"))
        // Pauses and extensions would dial on by themselves.
        assertNull(CallLogic.normalize("0441234567,123"))
        assertNull(CallLogic.normalize("0441234567;ext=1"))
        assertNull(CallLogic.normalize("sms:0441234567"))
    }

    private fun decide(
        number: String? = "+41791234567",
        voice: Boolean = true,
        enabled: Boolean = true,
        permission: Boolean = true,
        emergency: Boolean = false,
        telephony: Boolean = true,
    ) = CallLogic.decide(number, voice, enabled, permission, emergency, telephony)

    @Test
    fun placesTheCallWhenEverythingAllowsIt() {
        assertEquals(CallOutcome.Call("+41791234567"), decide())
    }

    @Test
    fun refusesBeforeAnythingElse() {
        assertEquals(CallOutcome.Refused(CallLogic.INVALID_NUMBER), decide(number = null, telephony = false, voice = false))
        assertEquals(CallOutcome.Refused(CallLogic.NO_TELEPHONY), decide(telephony = false, voice = false))
        // Outside a spoken conversation nothing starts, not even the dialer.
        assertEquals(CallOutcome.Refused(CallLogic.NOT_IN_VOICE), decide(voice = false, emergency = true))
        assertEquals(CallOutcome.Refused(CallLogic.NOT_IN_VOICE), decide(voice = false, enabled = false))
    }

    @Test
    fun fallsBackToTheDialer() {
        // Emergency numbers first: Android allows no direct call to them, whatever the setting.
        assertEquals(CallOutcome.Dial("112", CallLogic.EMERGENCY), decide(number = "112", emergency = true))
        assertEquals(CallOutcome.Dial("112", CallLogic.EMERGENCY), decide(number = "112", emergency = true, enabled = false))
        assertEquals(CallOutcome.Dial("+41791234567", CallLogic.DISABLED), decide(enabled = false, permission = false))
        assertEquals(CallOutcome.Dial("+41791234567", CallLogic.NO_PERMISSION), decide(permission = false))
    }

    @Test
    fun bridgeMessageAndEvent() {
        assertEquals(WebMessage.PlaceCall("+41 79 123 45 67"), ShellMessages.parse("""{"type":"call.place","number":"+41 79 123 45 67"}"""))
        assertNull(ShellMessages.parse("""{"type":"call.place","number":"  "}"""))
        assertNull(ShellMessages.parse("""{"type":"call.place"}"""))
        assertNull(ShellMessages.parse("""{"type":"call.place","number":112}"""))

        val placed = ShellMessages.callEvent(CallOutcome.Call("+41791234567"))
        assertTrue(placed.contains("""{detail:{"type":"call","state":"placed","number":"+41791234567"},cancelable:true}"""), placed)
        val dialer = ShellMessages.callEvent(CallOutcome.Dial("112", CallLogic.EMERGENCY))
        assertTrue(dialer.contains("""{"type":"call","state":"dialer","reason":"emergency","number":"112"}"""), dialer)
        val refused = ShellMessages.callEvent(CallOutcome.Refused(CallLogic.NOT_IN_VOICE))
        assertTrue(refused.contains("""{"type":"call","state":"refused","reason":"not-in-voice"}"""), refused)
        val failed = ShellMessages.callEvent(CallOutcome.Call("+41791234567"), failed = true)
        assertTrue(failed.contains("""{"type":"call","state":"refused","reason":"failed","number":"+41791234567"}"""), failed)
    }
}
