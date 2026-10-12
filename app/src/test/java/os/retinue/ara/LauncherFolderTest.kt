package os.retinue.ara

import android.content.pm.ApplicationInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.launcher.FolderKind
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry

class LauncherFolderTest {

    private val camera = LauncherEntry.App("a1", "Camera", "com.example.camera", "com.example.camera.Main")
    private val gallery = LauncherEntry.App("a2", "Gallery", "com.example.gallery", "com.example.gallery.Main")
    private val chess = LauncherEntry.App("a3", "Chess", "com.example.chess", "com.example.chess.Main")
    private val photos = LauncherEntry.Folder("f1", "Photos", FolderKind.Photos, apps = listOf(camera, gallery))
    private val games = LauncherEntry.Folder("f2", "Games", FolderKind.Games, icon = "0b6c4e8e-1f2a-4d3b-9c8d-7e6f5a4b3c2d.png")

    private fun ids(c: LauncherConfig) = c.entries.map { it.id }
    private fun appIds(c: LauncherConfig, folderId: String) = c.folder(folderId)!!.apps.map { it.id }

    @Test
    fun folderRoundTrip() {
        // An empty label (the type's name, resolved when shown) is kept as such.
        val unnamed = LauncherEntry.Folder("f3", "", FolderKind.Media)
        val c = LauncherConfig.DEFAULT.add(photos).add(games.add(chess)).add(unnamed)
        val json = LauncherCodec.encode(c)
        assertTrue(json.contains("\"type\":\"folder\""))
        assertTrue(json.contains("\"kind\":\"photos\""))
        assertTrue(json.contains("\"kind\":\"games\""))
        assertEquals(c, LauncherCodec.decode(json))
    }

    @Test
    fun folderDecodeKeepsOnlyApps() {
        // Anything but an app inside a folder is dropped, the folder and its other apps kept.
        val raw = """{"version":1,"entries":[{"type":"folder","id":"f","label":"Mixed","kind":"games","apps":[
            {"id":"a1","label":"Camera","packageName":"com.example.camera","activityName":"com.example.camera.Main"},
            {"type":"folder","id":"inner","label":"Nested","apps":[]},
            {"type":"folder","id":"x","label":"Folder posing as app","packageName":"p","activityName":"p.A"},
            {"type":"uri","id":"u","label":"Phone","uri":"tel:"},
            {"type":"web","id":"w","label":"Home","path":"/"},
            {"type":"shortcut","id":"s","label":"Web app","packageName":"com.android.chrome","shortcutId":"x"},
            {"id":"b","label":"Broken"},
            {"type":"app","id":"a2","label":"Gallery","packageName":"com.example.gallery","activityName":"com.example.gallery.Main"},
            {"id":"a9","label":"Camera again","packageName":"com.example.camera","activityName":"com.example.camera.Main"},
            {"id":"a2","label":"Same id","packageName":"com.example.other","activityName":"com.example.other.Main"}
        ]}]}"""
        val folder = LauncherCodec.decode(raw).folder("f")!!
        assertEquals(FolderKind.Games, folder.kind)
        assertEquals(listOf("a1", "a2"), folder.apps.map { it.id })
    }

    @Test
    fun folderDecodeForgives() {
        val raw = """{"version":1,"entries":[
            {"type":"folder","id":"bare","label":"Bare"},
            {"type":"folder","id":"future","label":"Pets","kind":"pets","apps":5},
            {"type":"folder","id":"path","label":"Path","icon":"../../shared_prefs/x.png"},
            {"type":"folder","id":"ok","label":"Ok","icon":"abc-123.png"}
        ]}"""
        val c = LauncherCodec.decode(raw)
        assertEquals(listOf("bare", "future", "path", "ok"), ids(c))
        val bare = c.folder("bare")!!
        assertEquals(FolderKind.General, bare.kind)
        assertNull(bare.icon)
        assertTrue(bare.apps.isEmpty())
        // An unknown (later) type reads as a plain folder; a malformed app list as empty.
        assertEquals(FolderKind.General, c.folder("future")!!.kind)
        assertTrue(c.folder("future")!!.apps.isEmpty())
        // Only an icon name the shell could have made is kept.
        assertNull(c.folder("path")!!.icon)
        assertEquals("abc-123.png", c.folder("ok")!!.icon)
    }

    @Test
    fun foldersAndLookup() {
        val c = LauncherConfig.DEFAULT.add(photos).add(camera).add(games)
        assertEquals(listOf("f1", "f2"), c.folders.map { it.id })
        assertEquals(photos, c.folder("f1"))
        assertNull(c.folder("phone"))
        assertNull(c.folder("missing"))
        // Pinned to the bar means on the bar itself: an app in a folder is not pinned.
        assertTrue(LauncherConfig.DEFAULT.add(photos).folder("f1")!!.containsApp("com.example.camera", "com.example.camera.Main"))
        assertFalse(LauncherConfig.DEFAULT.add(photos).containsApp("com.example.camera", "com.example.camera.Main"))
    }

    @Test
    fun addToFolder() {
        val c = LauncherConfig.DEFAULT.add(photos)
        val added = c.addToFolder("f1", chess)
        assertEquals(listOf("a1", "a2", "a3"), appIds(added, "f1"))
        // The same app (under another id) is not added twice; nor is an id twice.
        assertSame(added, added.addToFolder("f1", chess.copy(id = "other")))
        assertSame(added, added.addToFolder("f1", gallery.copy(id = "a3", packageName = "com.example.new")))
        // Unknown ids and entries that are not folders change nothing.
        assertSame(c, c.addToFolder("missing", chess))
        assertSame(c, c.addToFolder("phone", chess))
        // The same app may be in several folders and on the bar.
        val both = added.add(games).addToFolder("f2", chess).add(chess.copy(id = "bar-chess"))
        assertEquals(listOf("a3"), appIds(both, "f2"))
        assertTrue(both.containsApp("com.example.chess", "com.example.chess.Main"))
    }

    @Test
    fun removeAndMoveInFolder() {
        val c = LauncherConfig.DEFAULT.add(photos.add(chess))
        assertEquals(listOf("a1", "a3"), appIds(c.removeFromFolder("f1", "a2"), "f1"))
        assertSame(c, c.removeFromFolder("f1", "missing"))
        assertSame(c, c.removeFromFolder("missing", "a1"))
        assertEquals(listOf("a2", "a1", "a3"), appIds(c.moveInFolder("f1", "a2", -1), "f1"))
        assertEquals(listOf("a2", "a3", "a1"), appIds(c.moveInFolder("f1", "a1", 99), "f1"))
        // Clamped at the ends; unknown apps change nothing.
        assertSame(c, c.moveInFolder("f1", "a1", -1))
        assertSame(c, c.moveInFolder("f1", "a3", 1))
        assertSame(c, c.moveInFolder("f1", "missing", 1))
        // Removing the folder removes it with its apps.
        assertEquals(listOf("phone", "messages", "mail", "maps"), ids(c.remove("f1")))
    }

    @Test
    fun onlyAppsMoveIntoFolders() {
        val webApp = LauncherEntry.Shortcut("s1", "Web app", "com.android.chrome", "x")
        val c = LauncherConfig.DEFAULT.add(chess).add(photos).add(games).add(webApp)
        val moved = c.moveIntoFolder("a3", "f2")
        assertEquals(listOf("phone", "messages", "mail", "maps", "f1", "f2", "s1"), ids(moved))
        assertEquals(listOf("a3"), appIds(moved, "f2"))
        // Folders do not nest, and pinned shortcuts and links stay on the bar.
        assertSame(c, c.moveIntoFolder("f1", "f2"))
        assertSame(c, c.moveIntoFolder("f2", "f2"))
        assertSame(c, c.moveIntoFolder("s1", "f2"))
        assertSame(c, c.moveIntoFolder("phone", "f2"))
        assertSame(c, c.moveIntoFolder("a3", "missing"))
        assertSame(c, c.moveIntoFolder("a3", "phone"))
        // An app the folder already has just leaves the bar.
        val again = c.addToFolder("f2", chess).moveIntoFolder("a3", "f2")
        assertFalse(again.entries.any { it.id == "a3" })
        assertEquals(listOf("a3"), appIds(again, "f2"))
    }

    @Test
    fun iconNames() {
        assertEquals(emptySet(), LauncherConfig.DEFAULT.iconNames())
        val c = LauncherConfig.DEFAULT.add(photos).add(games)
        assertEquals(setOf("0b6c4e8e-1f2a-4d3b-9c8d-7e6f5a4b3c2d.png"), c.iconNames())
    }

    @Test
    fun iconNameValidation() {
        assertTrue(LauncherEntry.Folder.isIconName("0b6c4e8e-1f2a-4d3b-9c8d-7e6f5a4b3c2d.png"))
        assertFalse(LauncherEntry.Folder.isIconName(""))
        assertFalse(LauncherEntry.Folder.isIconName(".png"))
        assertFalse(LauncherEntry.Folder.isIconName("../a.png"))
        assertFalse(LauncherEntry.Folder.isIconName("a/b.png"))
        assertFalse(LauncherEntry.Folder.isIconName("a b.png"))
        assertFalse(LauncherEntry.Folder.isIconName("a.jpg"))
        assertFalse(LauncherEntry.Folder.isIconName("a.png/.."))
    }

    @Test
    fun folderKinds() {
        FolderKind.entries.forEach { assertEquals(it, FolderKind.fromKey(it.key)) }
        assertEquals(FolderKind.entries.size, FolderKind.entries.map { it.key }.toSet().size)
        assertEquals(FolderKind.General, FolderKind.fromKey(null))
        assertEquals(FolderKind.General, FolderKind.fromKey("pets"))
        assertTrue(FolderKind.Games.suggests(ApplicationInfo.CATEGORY_GAME))
        assertTrue(FolderKind.Media.suggests(ApplicationInfo.CATEGORY_AUDIO))
        assertTrue(FolderKind.Media.suggests(ApplicationInfo.CATEGORY_VIDEO))
        assertFalse(FolderKind.Games.suggests(ApplicationInfo.CATEGORY_SOCIAL))
        // An app without a category fits no type.
        assertFalse(FolderKind.entries.any { it.suggests(ApplicationInfo.CATEGORY_UNDEFINED) })
    }
}
