package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.launcher.FolderKind
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry

class LauncherPruneTest {

    private val camera = LauncherEntry.App("a1", "Camera", "com.example.camera", "com.example.camera.Main")
    private val cameraVideo = LauncherEntry.App("a2", "Video", "com.example.camera", "com.example.camera.Video")
    private val chess = LauncherEntry.App("a3", "Chess", "com.example.chess", "com.example.chess.Main")
    private val gallery = LauncherEntry.App("a4", "Gallery", "com.example.gallery", "com.example.gallery.Main")
    private val webApp = LauncherEntry.Shortcut("s1", "Web app", "com.example.browser", "pwa-1")
    private val photos = LauncherEntry.Folder("f1", "Photos", FolderKind.Photos, apps = listOf(cameraVideo, gallery))
    private val games = LauncherEntry.Folder("f2", "", FolderKind.Games, apps = listOf(chess))

    private val bar = LauncherConfig.DEFAULT.add(camera).add(webApp).add(photos).add(games)

    private fun ids(c: LauncherConfig) = c.entries.map { it.id }
    private fun appIds(c: LauncherConfig, folderId: String) = c.folder(folderId)!!.apps.map { it.id }

    @Test
    fun appPackageNames() {
        // Apps on the bar and in folders; a pinned shortcut's package is not an app of the bar.
        assertEquals(setOf("com.example.camera", "com.example.gallery", "com.example.chess"), bar.appPackageNames())
        assertEquals(emptySet(), LauncherConfig.DEFAULT.appPackageNames())
    }

    @Test
    fun uninstalledAppsLeaveTheBarAndFolders() {
        val pruned = bar.withoutPackages(setOf("com.example.camera"))
        // Every activity of the package goes, on the bar and in folders; the rest stays in order.
        assertEquals(listOf("phone", "messages", "mail", "maps", "s1", "f1", "f2"), ids(pruned))
        assertEquals(listOf("a4"), appIds(pruned, "f1"))
        assertSame(bar.folder("f2"), pruned.folder("f2"))
    }

    @Test
    fun foldersStayWhenEmptied() {
        val pruned = bar.withoutPackages(setOf("com.example.chess"))
        assertTrue(pruned.folder("f2")!!.apps.isEmpty())
        assertEquals(ids(bar), ids(pruned))
    }

    @Test
    fun pinnedShortcutsOfAnUninstalledAppGoToo() {
        val pruned = bar.withoutPackages(setOf("com.example.browser"))
        assertEquals(listOf("phone", "messages", "mail", "maps", "a1", "f1", "f2"), ids(pruned))
    }

    @Test
    fun nothingToPruneChangesNothing() {
        assertSame(bar, bar.withoutPackages(emptySet()))
        assertSame(bar, bar.withoutPackages(setOf("com.example.unknown")))
        // Links and web pages have no package.
        assertSame(LauncherConfig.DEFAULT, LauncherConfig.DEFAULT.withoutPackages(setOf("com.android.dialer")))
    }
}
