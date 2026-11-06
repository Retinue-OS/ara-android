package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
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
    }

    @Test
    fun statusJsonIsTheContract() {
        val j = ready.copy(layout = "de-CH").toJson()
        assertEquals(setOf("available", "enabled", "connected", "host", "layout"), j.keys)
        assertEquals("de-CH", j["layout"]!!.jsonPrimitive.content)
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
