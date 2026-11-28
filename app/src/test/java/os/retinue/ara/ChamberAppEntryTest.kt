package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.apps.AppsLogic
import os.retinue.ara.apps.ChamberApp
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.FolderKind
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import os.retinue.ara.shell.LinkTarget
import os.retinue.ara.shell.ShellLinks
import os.retinue.ara.ui.DrawerItem
import os.retinue.ara.ui.drawerItems
import os.retinue.ara.ui.toEntry
import os.retinue.ara.ui.trayItems

/** Chamber apps as launcher entries: in All apps by name, on the bar, in folders. */
class ChamberAppEntryTest {

    private val base = "https://agents.example.com"
    private val routine = ChamberApp("routines", "routine", "Routine timer", "", null, "$base/apps/routines/routine/?view=today")
    private val entry = routine.toEntry("ep1", id = "c1")

    @Test
    fun anIndexAppBecomesAnEntryOfItsEndpoint() {
        assertEquals(LauncherEntry.ChamberApp("c1", "Routine timer", "ep1", "routines", "routine", "/apps/routines/routine/?view=today"), entry)
        assertEquals("chamber:ep1/routines/routine", entry.key)
        // The same app of another endpoint is another app.
        assertFalse(entry.key == routine.toEntry("ep2").key)
    }

    @Test
    fun itRoundTripsOnTheBarAndInAFolder() {
        val folder = LauncherEntry.Folder("f1", "Daily", FolderKind.General, apps = listOf(entry.copy(id = "c2")))
        val c = LauncherConfig.DEFAULT.add(entry).add(folder)
        val json = LauncherCodec.encode(c)
        assertTrue(json.contains("\"type\":\"chamber\""))
        assertEquals(c, LauncherCodec.decode(json))
        assertEquals(listOf<LauncherEntry.Launchable>(entry.copy(id = "c2")), LauncherCodec.decode(json).folder("f1")!!.apps)
    }

    @Test
    fun theBarHoldsItOnceAndItMovesIntoFolders() {
        val c = LauncherConfig(emptyList()).add(entry)
        assertSame(c, c.add(entry.copy(id = "other")))
        assertTrue(c.containsKey(entry.key))
        val withFolder = c.add(LauncherEntry.Folder("f1", "", FolderKind.General))
        val moved = withFolder.moveIntoFolder("c1", "f1")
        assertEquals(listOf("f1"), moved.entries.map { it.id })
        assertTrue(moved.folder("f1")!!.containsKey(entry.key))
        // Uninstalling a package leaves it alone.
        assertSame(moved, moved.withoutPackages(setOf("com.example.anything")))
    }

    @Test
    fun allAppsListsItByNameAmongTheOtherApps() {
        val apps = listOf(
            AppInfo("Camera", "com.example.camera", "Main"),
            AppInfo("Settings", "com.android.settings", "Main"),
        )
        val shortcuts = listOf(PinnedShortcut("Weather", "com.example.browser", "pwa-1"))
        val items = drawerItems(apps, shortcuts, listOf(routine.toEntry("ep1", id = "")))
        assertEquals(listOf("Camera", "Routine timer", "Settings", "Weather"), items.map { it.label })
        val chamber = items[1] as DrawerItem.Chamber
        assertTrue(chamber.opensInAra)
        assertTrue(chamber.matches("routines"))
        // Pinned or added to a folder, it gets an id of its own.
        assertTrue(chamber.toEntry().id.isNotEmpty())
        // The tray lists it too, unless it is on the bar.
        val onBar = LauncherConfig(listOf(entry))
        assertEquals(
            listOf("Camera", "Settings", "Weather"),
            trayItems(apps, shortcuts, onBar, emptyMap(), emptyMap(), listOf(routine.toEntry("ep1", id = ""))).map { it.label },
        )
    }

    @Test
    fun itOpensOnlyUnderItsOwnPathOnItsEndpoint() {
        assertEquals("$base/apps/routines/routine/?view=today", AppsLogic.appUrl(base, "routines", "routine", entry.path))
        assertNull(AppsLogic.appUrl(base, "routines", "routine", "/"))
        assertNull(AppsLogic.appUrl(base, "routines", "routine", "/apps/routines/other/"))
        assertNull(AppsLogic.appUrl(base, "routines", "routine", "/apps/routines/routine/../../../settings"))
        assertNull(AppsLogic.appUrl(base, "routines", "routine", "https://evil.example/apps/routines/routine/"))
        assertNull(AppsLogic.appUrl(base, "../x", "routine", "/apps/../x/routine/"))
        assertEquals("/apps/routines/routine/", AppsLogic.startPath("$base/apps/routines/routine/"))
    }

    @Test
    fun theOpenLinkCarriesTheUrlUnchanged() {
        val url = "$base/apps/routines/routine/?view=today&n=%C3%A4#top"
        val link = ShellLinks.openLink(url)
        assertEquals(url, ShellLinks.targetUrl(link))
        assertEquals(LinkTarget.OtherEndpoint("ep2", url), ShellLinks.target(link, "https://other.example", mapOf("ep2" to base)))
    }
}
