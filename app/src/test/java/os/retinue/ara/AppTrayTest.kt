package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.launcher.AppRecency
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.TrayLogic

class AppTrayTest {

    private val camera = LauncherEntry.Launchable.appKey("com.example.camera", "com.example.camera.Main")
    private val chess = LauncherEntry.Launchable.appKey("com.example.chess", "com.example.chess.Main")
    private val webApp = LauncherEntry.Launchable.shortcutKey("com.example.browser", "pwa-1")

    // --- Recency -------------------------------------------------------------

    @Test
    fun mostRecentFirstNeverUsedKeepTheirOrder() {
        val items = listOf("Atlas", "Camera", "Chess", "Notes", "Zoo")
        val used = mapOf("Chess" to 300L, "Zoo" to 500L, "Camera" to 100L)
        assertEquals(
            listOf("Zoo", "Chess", "Camera", "Atlas", "Notes"),
            AppRecency.recentFirst(items) { used[it] ?: 0L },
        )
    }

    @Test
    fun equalTimesKeepTheirOrder() {
        val items = listOf("A", "B", "C")
        assertEquals(items, AppRecency.recentFirst(items) { 42L })
        assertEquals(items, AppRecency.recentFirst(items) { 0L })
    }

    @Test
    fun lastUseIsTheLaterOfAraAndAndroid() {
        val launches = mapOf(camera to 1_000L)
        val usage = mapOf("com.example.camera" to 2_000L, "com.example.chess" to 500L)
        assertEquals(2_000L, AppRecency.lastUsed(camera, "com.example.camera", launches, usage))
        assertEquals(500L, AppRecency.lastUsed(chess, "com.example.chess", launches, usage))
        assertEquals(1_000L, AppRecency.lastUsed(camera, "com.example.camera", launches, emptyMap()))
        assertEquals(0L, AppRecency.lastUsed(chess, "com.example.chess", emptyMap(), emptyMap()))
    }

    @Test
    fun aWebAppCountsOnlyItsOwnLaunches() {
        // Its package is the browser: the browser's use says nothing about the web app.
        val usage = mapOf("com.example.browser" to 9_000L)
        assertEquals(0L, AppRecency.lastUsed(webApp, null, emptyMap(), usage))
        assertEquals(10L, AppRecency.lastUsed(webApp, null, mapOf(webApp to 10L), usage))
    }

    @Test
    fun aLaunchReplacesTheOlderOne() {
        val once = AppRecency.recorded(emptyMap(), camera, 100L)
        val twice = AppRecency.recorded(once, camera, 200L)
        assertEquals(mapOf(camera to 200L), twice)
    }

    @Test
    fun onlyTheMostRecentLaunchesAreKept() {
        var launches = emptyMap<String, Long>()
        for (i in 1..5) launches = AppRecency.recorded(launches, "app:p$i/A", i * 10L, limit = 3)
        assertEquals(setOf("app:p3/A", "app:p4/A", "app:p5/A"), launches.keys)
    }

    @Test
    fun launchesRoundTrip() {
        val launches = mapOf(camera to 1_700_000_000_000L, webApp to 1_700_000_000_123L)
        assertEquals(launches, AppRecency.decode(AppRecency.encode(launches)))
    }

    @Test
    fun unreadableLaunchesCountAsNone() {
        assertEquals(emptyMap(), AppRecency.decode(null))
        assertEquals(emptyMap(), AppRecency.decode(""))
        assertEquals(emptyMap(), AppRecency.decode("not json"))
        assertEquals(emptyMap(), AppRecency.decode("[1,2]"))
        // Entries that are not times are left out, the rest kept.
        assertEquals(mapOf(camera to 5L), AppRecency.decode("""{"$camera":5,"x":"soon","y":-3,"z":{"a":1}}"""))
    }

    // --- Tray height ---------------------------------------------------------

    private val line = 100f
    private val fling = 1_000f

    @Test
    fun aSlowReleaseSettlesOnTheNearestLine() {
        assertEquals(0f, TrayLogic.settle(40f, 0f, line, 450f, fling))
        assertEquals(100f, TrayLogic.settle(60f, 0f, line, 450f, fling))
        assertEquals(200f, TrayLogic.settle(240f, 200f, line, 450f, fling))
        assertEquals(300f, TrayLogic.settle(251f, -200f, line, 450f, fling))
    }

    @Test
    fun theLastStopIsTheTop() {
        // 450 is below the top menu, not a whole line: near it, the tray goes there.
        assertEquals(450f, TrayLogic.settle(430f, 0f, line, 450f, fling))
        assertEquals(400f, TrayLogic.settle(420f, 0f, line, 450f, fling))
        assertEquals(450f, TrayLogic.settle(450f, 0f, line, 450f, fling))
        // Beyond the top (a stale reach) it comes back to it.
        assertEquals(450f, TrayLogic.settle(600f, 0f, line, 450f, fling))
    }

    @Test
    fun aFlingGoesAllTheWay() {
        assertEquals(450f, TrayLogic.settle(30f, 1_500f, line, 450f, fling))
        assertEquals(0f, TrayLogic.settle(420f, -1_500f, line, 450f, fling))
    }

    @Test
    fun nothingToShowMeansNoTray() {
        assertEquals(0f, TrayLogic.settle(30f, 1_500f, line, 0f, fling))
        assertEquals(0f, TrayLogic.maxExpansion(800f, 0, line))
    }

    @Test
    fun theTrayGoesNoFurtherThanItsApps() {
        assertEquals(0, TrayLogic.lines(0, 5))
        assertEquals(1, TrayLogic.lines(5, 5))
        assertEquals(2, TrayLogic.lines(6, 5))
        assertEquals(3, TrayLogic.lines(3, 1))
        // Up to the top menu …
        assertEquals(800f, TrayLogic.maxExpansion(800f, 20, line))
        // … or where the apps end.
        assertEquals(300f, TrayLogic.maxExpansion(800f, 3, line))
        // No room at all.
        assertEquals(0f, TrayLogic.maxExpansion(-20f, 3, line))
    }

    @Test
    fun topMenuResult() {
        assertEquals(126f, TrayLogic.topMenuPx("126"))
        assertEquals(126.5f, TrayLogic.topMenuPx(" 126.5 "))
        // None found, or nothing usable: the shell's default.
        assertNull(TrayLogic.topMenuPx("0"))
        assertNull(TrayLogic.topMenuPx("null"))
        assertNull(TrayLogic.topMenuPx(null))
        assertNull(TrayLogic.topMenuPx("\"x\""))
        assertTrue(TrayLogic.TOP_MENU_SCRIPT.contains("retinue-nav"))
    }
}
