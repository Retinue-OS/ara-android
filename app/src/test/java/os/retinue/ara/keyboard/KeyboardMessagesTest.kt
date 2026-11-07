package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.WebMessage

class KeyboardMessagesTest {

    @Test
    fun parsesTheKeyboardMessages() {
        assertEquals(
            WebMessage.KeyboardType("Grüezi", true, "m1"),
            ShellMessages.parse("""{"type":"keyboard.type","text":"Grüezi","enter":true,"id":"m1"}"""),
        )
        assertEquals(
            WebMessage.KeyboardType("x", false, null),
            ShellMessages.parse("""{"type":"keyboard.type","text":"x","id":"no spaces allowed"}"""),
        )
        assertNull(ShellMessages.parse("""{"type":"keyboard.type","text":5}"""))
        assertEquals(WebMessage.KeyboardKey("enter"), ShellMessages.parse("""{"type":"keyboard.key","name":"enter"}"""))
        assertEquals(
            WebMessage.KeyboardKey("tab", "k1"),
            ShellMessages.parse("""{"type":"keyboard.key","name":"tab","id":"k1"}"""),
        )
        assertEquals(
            WebMessage.KeyboardKey("tab", null),
            ShellMessages.parse("""{"type":"keyboard.key","name":"tab","id":"bad id"}"""),
        )
        assertNull(ShellMessages.parse("""{"type":"keyboard.key","name":"F13"}"""))
        assertEquals(
            WebMessage.KeyboardSecret("Meta account", true, "s1"),
            ShellMessages.parse("""{"type":"keyboard.secret","label":"Meta account","enter":true,"id":"s1"}"""),
        )
        assertEquals(WebMessage.KeyboardSecret(null, false, null), ShellMessages.parse("""{"type":"keyboard.secret"}"""))
        assertEquals(WebMessage.KeyboardOpen, ShellMessages.parse("""{"type":"keyboard.open"}"""))
    }

    @Test
    fun overLongTextReachesTheRuntimeToBeRefusedThere() {
        val long = "a".repeat(KeyboardLogic.MAX_TEXT + 10)
        val m = ShellMessages.parse("""{"type":"keyboard.type","text":"$long"}""") as WebMessage.KeyboardType
        assertEquals(KeyboardError.TooLong, KeyboardLogic.precheck(KeyboardStatus(available = true, enabled = true, connected = true), m.text.length))
    }

    @Test
    fun statusScriptCarriesTheContractJson() {
        val js = ShellMessages.keyboardStatus(KeyboardStatus(available = true, enabled = true, layout = "de-CH"))
        assertTrue(js.contains("\"type\":\"keyboard.status\""))
        assertTrue(js.contains("\"layout\":\"de-CH\""))
    }
}
