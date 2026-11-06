package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.DeviceWebSettings
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.WebMessage

/** The dashboard's per-device preferences the app holds for every endpoint. */
class DeviceWebSettingsTest {

    @Test
    fun onlyTheDashboardsOwnKeysAreKept() {
        for (k in listOf(
            "retinue-voice-silence-ms", "retinue-voice-nospeech-ms", "retinue.shelf.size",
            "retinue.layout.v1", "retinue.chatpage.v1", "retinue.view.projects",
        )) {
            assertTrue(DeviceWebSettings.isKey(k), k)
        }
        for (k in listOf(
            null, "", "retinue.view.", "retinue.shelf.v1", "retinue-draft:x", "retinue.view.a b",
            "retinue.view." + "x".repeat(80), "anything",
        )) {
            assertFalse(DeviceWebSettings.isKey(k), k.toString())
        }
    }

    @Test
    fun applySetsRemovesAndRefuses() {
        val one = DeviceWebSettings.apply(emptyMap(), "retinue.shelf.size", "8")!!
        assertEquals(mapOf("retinue.shelf.size" to "8"), one)
        assertEquals(emptyMap(), DeviceWebSettings.apply(one, "retinue.shelf.size", null))
        // A key the app does not keep, or a value too long: refused, nothing changes.
        assertNull(DeviceWebSettings.apply(one, "retinue.attention.held", "1"))
        assertNull(DeviceWebSettings.apply(one, "retinue.layout.v1", "x".repeat(DeviceWebSettings.MAX_VALUE_LENGTH + 1)))
        // Full: a new key is refused, an existing one can still change.
        val full = (1..DeviceWebSettings.MAX_ENTRIES).associate { "retinue.view.card$it" to "list" }
        assertNull(DeviceWebSettings.apply(full, "retinue.shelf.size", "3"))
        assertEquals("cards", DeviceWebSettings.apply(full, "retinue.view.card1", "cards")!!["retinue.view.card1"])
    }

    @Test
    fun storedFormRoundTripsAndForgivesGarbage() {
        val values = mapOf("retinue-voice-silence-ms" to "1500", "retinue.layout.v1" to "{\"side\":320}")
        assertEquals(values, DeviceWebSettings.decode(DeviceWebSettings.encode(values)))
        assertEquals(emptyMap(), DeviceWebSettings.decode(null))
        assertEquals(emptyMap(), DeviceWebSettings.decode("not json"))
        assertEquals(emptyMap(), DeviceWebSettings.decode("[1,2]"))
        // Unknown keys, non-string values and oversize values are dropped.
        assertEquals(
            mapOf("retinue.shelf.size" to "4"),
            DeviceWebSettings.decode(
                """{"retinue.shelf.size":"4","retinue.shelf.v1":"x","retinue-voice-silence-ms":1500,"retinue.layout.v1":"${"y".repeat(5000)}"}""",
            ),
        )
        assertEquals(
            listOf("retinue.shelf.size", "retinue.view.news"),
            DeviceWebSettings.changedKeys(mapOf("retinue.shelf.size" to "4", "retinue.view.x" to "list"), mapOf("retinue.shelf.size" to "5", "retinue.view.x" to "list", "retinue.view.news" to "cards")),
        )
    }

    @Test
    fun bridgeMessages() {
        assertEquals(WebMessage.DeviceSet("retinue.shelf.size", "6"), ShellMessages.parse("""{"type":"device.set","key":"retinue.shelf.size","value":"6"}"""))
        assertEquals(WebMessage.DeviceSet("retinue.shelf.size", null), ShellMessages.parse("""{"type":"device.set","key":"retinue.shelf.size","value":null}"""))
        assertEquals(WebMessage.DeviceSet("retinue.shelf.size", null), ShellMessages.parse("""{"type":"device.set","key":"retinue.shelf.size"}"""))
        // A value that is no string is no message; nor is a missing key.
        assertNull(ShellMessages.parse("""{"type":"device.set","key":"retinue.shelf.size","value":6}"""))
        assertNull(ShellMessages.parse("""{"type":"device.set","value":"6"}"""))
        val push = ShellMessages.deviceStatus(mapOf("retinue.shelf.size" to "6"))
        assertTrue(push.contains("\"type\":\"device.status\",\"settings\":{\"retinue.shelf.size\":\"6\"}"), push)
    }
}
