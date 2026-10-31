package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.LauncherLinks
import os.retinue.ara.launcher.LinkAction

class LauncherConfigTest {

    private val app = LauncherEntry.App("a1", "Camera", "com.example.camera", "com.example.camera.Main")
    private val web = LauncherEntry.WebPage("w1", "Contacts", "/contacts")
    private val pwa = LauncherEntry.Shortcut("s1", "Weather", "com.android.chrome", "webapp-1")

    private fun ids(c: LauncherConfig) = c.entries.map { it.id }

    @Test
    fun defaultsAreTheFourOsActions() {
        assertEquals(listOf("phone", "messages", "mail", "maps"), ids(LauncherConfig.DEFAULT))
        val uris = LauncherConfig.DEFAULT.entries.map { (it as LauncherEntry.Link).uri }
        assertEquals(listOf("tel:", "sms:", "mailto:", "geo:0,0?q="), uris)
    }

    @Test
    fun roundTrip() {
        val c = LauncherConfig.DEFAULT.add(app).add(web).add(pwa)
        val json = LauncherCodec.encode(c)
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"type\":\"app\""))
        assertTrue(json.contains("\"type\":\"shortcut\""))
        assertTrue(json.contains("\"shortcutId\":\"webapp-1\""))
        assertTrue(json.contains("\"type\":\"web\""))
        assertTrue(json.contains("\"type\":\"uri\""))
        assertEquals(c, LauncherCodec.decode(json))
    }

    @Test
    fun decodeFallsBackAndForgives() {
        assertEquals(LauncherConfig.DEFAULT, LauncherCodec.decode(null))
        assertEquals(LauncherConfig.DEFAULT, LauncherCodec.decode(""))
        assertEquals(LauncherConfig.DEFAULT, LauncherCodec.decode("{not json"))
        // An emptied bar stays empty.
        assertEquals(LauncherConfig(emptyList()), LauncherCodec.decode("""{"version":1,"entries":[]}"""))
        // Unknown entry types and broken entries are skipped, the rest kept; duplicate ids dropped.
        val mixed = """{"version":2,"entries":[
            {"type":"widget","id":"x","label":"?"},
            {"type":"uri","id":"p","label":"Phone","uri":"tel:","extra":1},
            {"type":"app","id":"b","label":"broken"},
            {"type":"uri","id":"p","label":"Dup","uri":"sms:"},
            {"type":"web","id":"w","label":"Home","path":"/"}
        ]}"""
        assertEquals(listOf("p", "w"), ids(LauncherCodec.decode(mixed)))
        // A bare array is accepted too.
        assertEquals(listOf("w"), ids(LauncherCodec.decode("""[{"type":"web","id":"w","label":"H","path":"/"}]""")))
    }

    @Test
    fun addRefusesDuplicates() {
        val c = LauncherConfig.DEFAULT.add(app)
        assertSame(c, c.add(app.copy(id = "other")))
        assertSame(c, c.add(web.copy(id = "phone")))
        assertTrue(c.containsApp("com.example.camera", "com.example.camera.Main"))
    }

    @Test
    fun shortcutsAreAddedOncePerPackageAndId() {
        val c = LauncherConfig.DEFAULT.add(pwa)
        assertSame(c, c.add(pwa.copy(id = "other", label = "Again")))
        assertTrue(c.containsShortcut("com.android.chrome", "webapp-1"))
        assertFalse(c.containsShortcut("com.android.chrome", "webapp-2"))
        // Another shortcut of the same package, and the same id in another package, are distinct.
        val more = c.add(pwa.copy(id = "s2", shortcutId = "webapp-2")).add(pwa.copy(id = "s3", packageName = "org.example"))
        assertEquals(listOf("webapp-1", "webapp-2"), more.shortcutIds("com.android.chrome"))
        assertEquals(listOf("webapp-2"), more.remove("s1").shortcutIds("com.android.chrome"))
        assertEquals(emptyList(), more.shortcutIds("com.example.camera"))
        // A decoded shortcut missing its id is skipped like any broken entry.
        val raw = """{"version":1,"entries":[{"type":"shortcut","id":"x","label":"?","packageName":"p"}]}"""
        assertEquals(emptyList(), ids(LauncherCodec.decode(raw)))
    }

    @Test
    fun removeAndReplace() {
        val c = LauncherConfig.DEFAULT.add(web)
        assertEquals(listOf("phone", "messages", "maps", "w1"), ids(c.remove("mail")))
        val renamed = c.replace(web.copy(label = "People"))
        assertEquals("People", renamed.entries.last().label)
        assertSame(c, c.replace(web.copy(id = "missing")))
    }

    @Test
    fun moves() {
        val c = LauncherConfig.DEFAULT
        assertEquals(listOf("messages", "phone", "mail", "maps"), ids(c.moveLeft("messages")))
        assertEquals(listOf("phone", "messages", "maps", "mail"), ids(c.moveRight("mail")))
        // Clamped at the ends.
        assertSame(c, c.moveLeft("phone"))
        assertSame(c, c.moveRight("maps"))
        assertSame(c, c.move("missing", 1))
        assertEquals(listOf("messages", "mail", "maps", "phone"), ids(c.moveTo(0, 99)))
        assertEquals(listOf("maps", "phone", "messages", "mail"), ids(c.moveTo(3, 0)))
        assertSame(c, c.moveTo(7, 0))
        assertFalse(c.canMoveLeft("phone"))
        assertTrue(c.canMoveRight("phone"))
        assertFalse(c.canMoveRight("maps"))
        assertFalse(c.canMoveLeft("missing"))
    }

    @Test
    fun linkActions() {
        assertEquals(LinkAction.Dial, LauncherLinks.actionFor("tel:"))
        assertEquals(LinkAction.Dial, LauncherLinks.actionFor("TEL:+41"))
        assertEquals(LinkAction.SendTo, LauncherLinks.actionFor("sms:"))
        assertEquals(LinkAction.SendTo, LauncherLinks.actionFor("mailto:a@b.c"))
        assertEquals(LinkAction.ParseIntent, LauncherLinks.actionFor("intent://x#Intent;end"))
        assertEquals(LinkAction.View, LauncherLinks.actionFor("geo:0,0?q="))
        assertEquals(LinkAction.View, LauncherLinks.actionFor("https://example.org"))
        assertTrue(LauncherLinks.isValid("sms:"))
        assertTrue(LauncherLinks.isValid("https://example.org"))
        assertFalse(LauncherLinks.isValid("example.org"))
        assertFalse(LauncherLinks.isValid(":x"))
        assertFalse(LauncherLinks.isValid("a b:c"))
    }

    @Test
    fun oneEntryTravelsToTheSplitWindowAndBack() {
        val entries = listOf(
            LauncherEntry.App("a", "Signal", "org.thoughtcrime.securesms", "org.thoughtcrime.securesms.RoutingActivity"),
            LauncherEntry.Shortcut("s", "Retinue", "com.android.chrome", "webapk-1"),
            LauncherEntry.Link("l", "Phone", "tel:"),
            LauncherEntry.WebPage("w", "Threads", "/threads"),
        )
        for (entry in entries) {
            assertEquals(entry, LauncherCodec.decodeEntry(LauncherCodec.encodeEntry(entry)))
        }
    }

    @Test
    fun unreadableEntriesAreNull() {
        assertNull(LauncherCodec.decodeEntry(null))
        assertNull(LauncherCodec.decodeEntry(""))
        assertNull(LauncherCodec.decodeEntry("{"))
        assertNull(LauncherCodec.decodeEntry("{\"type\":\"nonsense\",\"id\":\"x\"}"))
    }

    @Test
    fun aHandedOverFolderIsNormalizedLikeAStoredOne() {
        val raw = """{"type":"folder","id":"f","label":"","icon":"../x.png","apps":[]}"""
        val folder = LauncherCodec.decodeEntry(raw) as LauncherEntry.Folder
        assertNull(folder.icon)
    }
}
