package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

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
    fun aSecretDialogAskedForCountsAsOpenForAWhile() {
        assertFalse(KeyboardLogic.secretDialogOpening(askedAt = 0, now = 10))
        assertTrue(KeyboardLogic.secretDialogOpening(askedAt = 1000, now = 1000))
        assertTrue(KeyboardLogic.secretDialogOpening(askedAt = 1000, now = 1000 + KeyboardLogic.SECRET_OPENING_MS - 1))
        // One that never came up does not shut the page out for good.
        assertFalse(KeyboardLogic.secretDialogOpening(askedAt = 1000, now = 1000 + KeyboardLogic.SECRET_OPENING_MS))
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
    fun secretEventsCarryNothingOfTheSecret() {
        val d = KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s1").detail()
        assertEquals(setOf("state", "id"), d.keys)
        val e = KeyboardEvent.error("s1", KeyboardError.NotConnected).detail()
        assertEquals("not-connected", e["error"]!!.jsonPrimitive.content)
        assertFalse("count" in e)
    }
}
