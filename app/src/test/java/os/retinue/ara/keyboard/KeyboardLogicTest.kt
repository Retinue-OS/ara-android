package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointSet

class KeyboardLogicTest {

    private val ready = KeyboardStatus(available = true, enabled = true, registered = true, connected = true, host = KeyboardHost("Quest", "AA"))

    @Test
    fun precheckOrder() {
        assertEquals(KeyboardError.NotEnabled, KeyboardLogic.precheck(ready.copy(enabled = false), 1))
        assertEquals(KeyboardError.NotEnabled, KeyboardLogic.precheck(ready.copy(available = false), 1))
        assertEquals(KeyboardError.NotConnected, KeyboardLogic.precheck(ready.copy(connected = false), 1))
        assertEquals(KeyboardError.Busy, KeyboardLogic.precheck(ready.copy(busy = true), 1))
        assertEquals(KeyboardError.TooLong, KeyboardLogic.precheck(ready, KeyboardLogic.MAX_TEXT + 1))
        assertNull(KeyboardLogic.precheck(ready, KeyboardLogic.MAX_TEXT))
    }

    @Test
    fun liveDiff() {
        assertEquals(0 to "abc", KeyboardLogic.liveDiff("", "abc"))
        assertEquals(1 to "", KeyboardLogic.liveDiff("abc", "ab"))
        assertEquals(2 to "Xc", KeyboardLogic.liveDiff("abc", "aXc"))
        assertEquals(0 to "", KeyboardLogic.liveDiff("same", "same"))
    }

    @Test
    fun onlyAnActualLayoutChangeOfTheFieldsDeviceStartsTheLiveFieldAfresh() {
        // de-CH "aüb" in the field, then US: an edit would retype "üb" without the "ü".
        assertTrue(KeyboardLogic.layoutChangeResetsLive("AA", "AA", KeyLayouts.DE_CH, KeyLayouts.US))
        assertFalse(KeyboardLogic.layoutChangeResetsLive("AA", "AA", KeyLayouts.US, KeyLayouts.US), "the same layout chosen again")
        assertFalse(KeyboardLogic.layoutChangeResetsLive("AA", "BB", KeyLayouts.DE_CH, KeyLayouts.US), "another device's layout")
        assertFalse(KeyboardLogic.layoutChangeResetsLive(null, "AA", KeyLayouts.DE_CH, KeyLayouts.US), "no field without a device")
        assertFalse(KeyboardLogic.layoutChangeResetsLive("AA", "AA", KeyLayouts.US, "xx"), "an unknown layout is not stored")
    }

    @Test
    fun idsAndLabels() {
        assertEquals("msg-1", KeyboardLogic.cleanId("msg-1"))
        assertNull(KeyboardLogic.cleanId("bad id"))
        assertNull(KeyboardLogic.cleanId("x".repeat(65)))
        assertEquals("Meta account", KeyboardLogic.cleanLabel("  Meta\naccount ")?.replace("  ", " "))
        assertNull(KeyboardLogic.cleanLabel("   "))
        assertEquals(KeyboardLogic.MAX_LABEL, KeyboardLogic.cleanLabel("y".repeat(500))!!.length)
        // Not only ASCII controls: C1, line/paragraph separators and bidi
        // overrides would break or spoof the dialog's title.
        assertEquals("Meta account x", KeyboardLogic.cleanLabel("Meta\u202E account\u2028\u0085x"))
        assertEquals("a b", KeyboardLogic.cleanLabel("a\u200Bb"))
    }

    @Test
    fun onlyTheChosenHostMayStay() {
        // While switching: only the device asked for — not the old one coming back.
        assertTrue(KeyboardLogic.acceptsHost("BB", pending = "BB", held = true, recentlyBonded = false, lastHost = "AA"))
        assertFalse(KeyboardLogic.acceptsHost("AA", pending = "BB", held = false, recentlyBonded = false, lastHost = "AA"))
        // One paired just now (the user paired it), even after a Disconnect.
        assertTrue(KeyboardLogic.acceptsHost("CC", pending = null, held = true, recentlyBonded = true, lastHost = "AA"))
        // Held off by a Disconnect: no other, not even the last one.
        assertFalse(KeyboardLogic.acceptsHost("AA", pending = null, held = true, recentlyBonded = false, lastHost = "AA"))
        assertFalse(KeyboardLogic.acceptsHost("BB", pending = null, held = true, recentlyBonded = false, lastHost = null))
        // Otherwise the last one, or the first ever.
        assertTrue(KeyboardLogic.acceptsHost("AA", pending = null, held = false, recentlyBonded = false, lastHost = "AA"))
        assertFalse(KeyboardLogic.acceptsHost("BB", pending = null, held = false, recentlyBonded = false, lastHost = "AA"))
        assertTrue(KeyboardLogic.acceptsHost("BB", pending = null, held = false, recentlyBonded = false, lastHost = null))
    }

    @Test
    fun typedCountsCharacters() {
        assertEquals(5, KeyboardLogic.charCount("hello"))
        assertEquals(3, KeyboardLogic.charCount("a\r\nb"), "a CRLF line break is one")
        assertEquals(1, KeyboardLogic.charCount("😀"), "a code point, not two UTF-16 units")
    }

    @Test
    fun secretTimingIsBucketed() {
        assertEquals(3000L, KeyboardLogic.secretPad(0, 3000))
        assertEquals(2600L, KeyboardLogic.secretPad(400, 3000))
        assertEquals(0L, KeyboardLogic.secretPad(3000, 3000))
        assertEquals(2999L, KeyboardLogic.secretPad(3001, 3000))
        // Whatever the length within a bucket, it ends at the same moment.
        assertEquals(400 + KeyboardLogic.secretPad(400, 3000), 2900 + KeyboardLogic.secretPad(2900, 3000))
        assertEquals(KeyboardError.Failed, KeyboardLogic.secretErrorForPage(KeyboardError.UnsupportedChar))
        assertEquals(KeyboardError.Failed, KeyboardLogic.secretErrorForPage(KeyboardError.TooLong))
        assertEquals(KeyboardError.NotConnected, KeyboardLogic.secretErrorForPage(KeyboardError.NotConnected))
    }

    @Test
    fun aWindowAskedForCountsAsOpenForAWhile() {
        assertFalse(KeyboardLogic.windowOpening(askedAt = 0, now = 10))
        assertTrue(KeyboardLogic.windowOpening(askedAt = 1000, now = 1000))
        assertTrue(KeyboardLogic.windowOpening(askedAt = 1000, now = 1000 + KeyboardLogic.WINDOW_OPENING_MS - 1))
        // One that never came up does not shut the page out for good.
        assertFalse(KeyboardLogic.windowOpening(askedAt = 1000, now = 1000 + KeyboardLogic.WINDOW_OPENING_MS))
    }

    @Test
    fun statusJsonIsTheContract() {
        val j = ready.copy(layout = "de-CH").toJson()
        assertEquals(setOf("available", "enabled", "connected", "host", "layout"), j.keys)
        assertEquals("de-CH", j["layout"]!!.jsonPrimitive.content)
        // The host by name only: its Bluetooth address stays in the app.
        assertEquals(setOf("name"), j["host"]!!.jsonObject.keys)
        assertEquals(JsonNull, KeyboardStatus().toJson()["host"])
    }

    @Test
    fun aPageTypesOnlyWhereItsServerMayAndOtherwiseLearnsNothingOfTheDevice() {
        val home = Endpoint("home", "https://agents.example.com", typeOnDevices = true)
        val admin = Endpoint("admin", "https://admin.example.com")
        val set = EndpointSet(listOf(home, admin), "home")
        assertTrue(KeyboardLogic.serverMayType(set, "home"))
        // Admin's switch is off; and home's never answers for admin's page, nor for a page left behind.
        assertFalse(KeyboardLogic.serverMayType(set.select("admin"), "admin"))
        assertFalse(KeyboardLogic.serverMayType(set.select("admin"), "home"))
        assertFalse(KeyboardLogic.serverMayType(set, null))
        assertFalse(KeyboardLogic.serverMayType(EndpointSet.EMPTY, null))
        assertFalse(KeyboardLogic.serverMayType(set.update("home") { it.copy(typeOnDevices = false) }, "home"))

        val connected = ready.copy(layout = "de-CH")
        assertEquals(connected, KeyboardLogic.statusForPage(connected, serverAllows = true))
        // Not allowed: not enabled, and nothing of the device, its layout or whether one is connected.
        val hidden = KeyboardLogic.statusForPage(connected, serverAllows = false).toJson()
        assertEquals(KeyboardStatus(available = true).toJson(), hidden)
        assertEquals("false", hidden["enabled"].toString())
        assertEquals("false", hidden["connected"].toString())
        assertEquals(JsonNull, hidden["host"])
        assertEquals(KeyLayouts.US, hidden["layout"]!!.jsonPrimitive.content)
        // The same shape as ever (values only change).
        assertEquals(connected.toJson().keys, hidden.keys)
        assertFalse(hidden.toString().contains("Quest") || hidden.toString().contains("de-CH"))

        // Nor does it hear when the keyboard or a device changes, nor how an earlier page's
        // requests ended (a device is there, so many characters): only its own not-enabled.
        val events = listOf(
            KeyboardEvent.status,
            KeyboardEvent.typed("t1", 3000),
            KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s1"),
            KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, "s1"),
            KeyboardEvent.error("t1", KeyboardError.Cancelled),
            KeyboardEvent.error("t1", KeyboardError.NotConnected),
        )
        for (event in events) {
            assertTrue(KeyboardLogic.eventForPage(event, serverAllows = true, requested = emptySet()), event.toString())
            assertFalse(KeyboardLogic.eventForPage(event, serverAllows = false, requested = emptySet()), event.toString())
        }
        assertTrue(KeyboardLogic.eventForPage(KeyboardEvent.error("t1", KeyboardError.NotEnabled), serverAllows = false, requested = emptySet()))
        assertTrue(KeyboardLogic.eventForPage(KeyboardEvent.error("t1", KeyboardError.NotEnabled), serverAllows = true, requested = emptySet()))
    }

    @Test
    fun aPageHearsTheEndOfItsOwnRequestAfterItsSwitchWasTurnedOff() {
        // Accepted for this page load while its switch was on; the switch is off now.
        val mine = setOf("t1", "s1", "k1")
        val ends = listOf(
            KeyboardEvent.typed("t1", 3000),
            KeyboardEvent.typed("k1", 1),
            KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s1"),
            KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, "s1"),
            KeyboardEvent.error("t1", KeyboardError.Cancelled),
            KeyboardEvent.error("k1", KeyboardError.NotConnected),
        )
        for (event in ends) assertTrue(KeyboardLogic.eventForPage(event, serverAllows = false, requested = mine), event.toString())

        // The end of a request of an earlier page load (its ids were cleared with the load) stays dropped.
        val earlier = listOf(
            KeyboardEvent.typed("t2", 3000),
            KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s2"),
            KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, "s2"),
            KeyboardEvent.error("t2", KeyboardError.Cancelled),
        )
        for (event in earlier) assertFalse(KeyboardLogic.eventForPage(event, serverAllows = false, requested = mine), event.toString())
        // Nor does an end without an id, or a status, pass by an id.
        assertFalse(KeyboardLogic.eventForPage(KeyboardEvent.typed(null, 3), serverAllows = false, requested = mine))
        assertFalse(KeyboardLogic.eventForPage(KeyboardEvent(KeyboardEvent.STATUS, "t1"), serverAllows = false, requested = mine))
    }

    @Test
    fun theConfirmationShowsWhatCannotBeSeen() {
        fun shown(text: String) = KeyboardLogic.shownText(text).joinToString("") { if (it.mark) "[${it.text}]" else it.text }
        assertEquals("hello world", shown("hello world"))
        // Blanks at the ends, a space as ·, another blank as ␣; those inside stay as they are.
        assertEquals("[··]a b[·␣]", shown("  a b \u00a0"))
        // A line break is marked and then breaks the line; CRLF is one, as it is typed.
        assertEquals("ls[↵]\nrm", shown("ls\nrm"))
        assertEquals("a[↵]\nb[↵]\nc", shown("a\r\nb\rc"))
        assertEquals("[↵]\n", shown("\n"))
        // A tab anywhere.
        assertEquals("user[⇥]pass[⇥⇥·]", shown("user\tpass\t\t "))
        // Only blanks: all of them shown.
        assertEquals("[···]", shown("   "))
        assertEquals("", shown(""))
        // The signs themselves, typed as text, are not marks.
        assertEquals(listOf(KeyboardLogic.Shown("a·↵", mark = false)), KeyboardLogic.shownText("a·↵"))
        assertTrue(KeyboardLogic.hasLineBreak("a\rb"))
        assertTrue(KeyboardLogic.hasLineBreak("a\nb"))
        assertFalse(KeyboardLogic.hasLineBreak("a\tb"))
    }

    @Test
    fun secretEventsCarryNothingOfTheSecret() {
        val d = KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s1").detail()
        assertEquals(setOf("state", "id"), d.keys)
        val e = KeyboardEvent.error("s1", KeyboardError.NotConnected).detail()
        assertEquals("not-connected", e["error"]!!.jsonPrimitive.content)
        assertFalse("count" in e)
    }
}
