package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointSet
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
        assertEquals("+41791234567", CallLogic.normalize("TEL:+41791234567"))
        // No-break and narrow no-break spaces, as formatted numbers come.
        assertEquals("+41791234567", CallLogic.normalize("+41\u00A079\u202F123\u00A045\u00A067"))
        // The trunk zero beside a country code is not dialled; without one the parentheses just go.
        assertEquals("+442079460958", CallLogic.normalize("+44 (0)20 7946 0958"))
        assertEquals("+442079460958", CallLogic.normalize("tel: +44 ( 0 ) 20 7946 0958"))
        assertEquals("0041791234567", CallLogic.normalize("(0041) 79 123 45 67"))
        assertEquals("0791234567", CallLogic.normalize("(0)79 123 45 67"))
    }

    @Test
    fun refusesWhatIsNotANumber() {
        assertNull(CallLogic.normalize(null))
        assertNull(CallLogic.normalize(""))
        assertNull(CallLogic.normalize("  "))
        assertNull(CallLogic.normalize("Anna"))
        assertNull(CallLogic.normalize("12"))
        assertNull(CallLogic.normalize("1".repeat(21)))
        assertNull(CallLogic.normalize("41+79"))
        assertNull(CallLogic.normalize("++41791234567"))
        // Pauses and extensions would dial on by themselves.
        assertNull(CallLogic.normalize("0441234567,123"))
        assertNull(CallLogic.normalize("0441234567;ext=1"))
        assertNull(CallLogic.normalize("sms:0441234567"))
        // Only ASCII digits, and nothing endless around them.
        assertNull(CallLogic.normalize("\u0660\u0661\u0662\u0663"))
        assertNull(CallLogic.normalize("\uFF0B41791234567"))
        assertNull(CallLogic.normalize("*".repeat(30) + "123"))
        assertNull(CallLogic.normalize("123" + " ".repeat(100)))
        assertNull(CallLogic.normalize("+"))
        assertNull(CallLogic.normalize("*#06#"))
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
    fun serviceCodesAreNeverPlacedDirectly() {
        // Call forwarding, a USSD request: placed directly the phone would carry them out unseen.
        for (code in listOf("*21*0791234567#", "**21*0791234567#", "##002#", "*123#", "#31#0791234567", "0791234567#")) {
            assertEquals(code, CallLogic.normalize(code))
            assertEquals(CallOutcome.Dial(code, CallLogic.SERVICE_CODE), decide(number = code), code)
        }
        assertEquals(CallOutcome.Dial("*123#", CallLogic.SERVICE_CODE), decide(number = "*123#", enabled = false))
        // Still only inside a spoken conversation, and an emergency number is named as one.
        assertEquals(CallOutcome.Refused(CallLogic.NOT_IN_VOICE), decide(number = "*123#", voice = false))
        assertEquals(CallOutcome.Dial("112#", CallLogic.EMERGENCY), decide(number = "112#", emergency = true))
    }

    @Test
    fun theSwitchIsEachEndpointsOwn() {
        val home = Endpoint("home", "https://agents.example.com", directCalls = true)
        val admin = Endpoint("admin", "https://admin.example.com")
        fun shown(current: String?) = EndpointSet(listOf(home, admin), current)
        // On for home only: home's page calls at once, admin's page gets the dialer.
        assertTrue(CallLogic.directCalls(shown("home"), pageEndpointId = "home"))
        assertFalse(CallLogic.directCalls(shown("admin"), pageEndpointId = "admin"))
        assertEquals(CallOutcome.Call("+41791234567"), decide(enabled = CallLogic.directCalls(shown("home"), "home")))
        assertEquals(
            CallOutcome.Dial("+41791234567", CallLogic.DISABLED),
            decide(enabled = CallLogic.directCalls(shown("admin"), "admin")),
        )
        // The page is not the shown endpoint's (a switch between them under way): off, either way round.
        assertFalse(CallLogic.directCalls(shown("home"), pageEndpointId = "admin"))
        assertFalse(CallLogic.directCalls(shown("admin"), pageEndpointId = "home"))
        // No page loaded yet; no endpoint at all (or the settings not read yet); home removed meanwhile.
        assertFalse(CallLogic.directCalls(shown("home"), pageEndpointId = null))
        assertFalse(CallLogic.directCalls(EndpointSet.EMPTY, pageEndpointId = null))
        assertFalse(CallLogic.directCalls(shown("home").remove("home"), pageEndpointId = "home"))
        // Switched off again, it is off for home's own page too.
        assertFalse(CallLogic.directCalls(shown("home").update("home") { it.copy(directCalls = false) }, pageEndpointId = "home"))
    }

    @Test
    fun bridgeMessageAndEvent() {
        assertEquals(WebMessage.PlaceCall("+41 79 123 45 67"), ShellMessages.parse("""{"type":"call.place","number":"+41 79 123 45 67"}"""))
        // An empty or blank number is passed on like any other string that is no number: the page is told so.
        assertEquals(WebMessage.PlaceCall("  "), ShellMessages.parse("""{"type":"call.place","number":"  "}"""))
        assertEquals(WebMessage.PlaceCall(""), ShellMessages.parse("""{"type":"call.place","number":""}"""))
        // Without a number, or with one that is no string, the message is malformed and ignored.
        assertNull(ShellMessages.parse("""{"type":"call.place"}"""))
        assertNull(ShellMessages.parse("""{"type":"call.place","number":null}"""))
        assertNull(ShellMessages.parse("""{"type":"call.place","number":112}"""))

        val placed = ShellMessages.callEvent(CallOutcome.Call("+41791234567"))
        assertTrue(placed.contains("""{detail:{"type":"call","state":"placed","number":"+41791234567"},cancelable:true}"""), placed)
        val dialer = ShellMessages.callEvent(CallOutcome.Dial("112", CallLogic.EMERGENCY))
        assertTrue(dialer.contains("""{"type":"call","state":"dialer","reason":"emergency","number":"112"}"""), dialer)
        val refused = ShellMessages.callEvent(CallOutcome.Refused(CallLogic.NOT_IN_VOICE))
        assertTrue(refused.contains("""{"type":"call","state":"refused","reason":"not-in-voice"}"""), refused)
        // The answer to a blank number.
        val blank = ShellMessages.callEvent(decide(number = CallLogic.normalize("  ")))
        assertTrue(blank.contains("""{"type":"call","state":"refused","reason":"invalid-number"}"""), blank)
        val code = ShellMessages.callEvent(CallOutcome.Dial("*21*0791234567#", CallLogic.SERVICE_CODE))
        assertTrue(code.contains("""{"type":"call","state":"dialer","reason":"service-code","number":"*21*0791234567#"}"""), code)
        val failed = ShellMessages.callEvent(CallOutcome.Call("+41791234567"), failed = true)
        assertTrue(failed.contains("""{"type":"call","state":"refused","reason":"failed","number":"+41791234567"}"""), failed)
    }
}
