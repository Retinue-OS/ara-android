package os.retinue.ara.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A launchable activity of an installed app. [category] is the app's Android
 * category ([ApplicationInfo.category]), which folder types suggest apps by.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
)

/**
 * The installed launchable apps (visible through the manifest's `<queries>` for
 * MAIN/LAUNCHER, without QUERY_ALL_PACKAGES; Chrome's WebAPKs are such apps) and
 * their icons, and the pinned shortcuts the bar holds.
 */
object AppCatalog {

    private const val TAG = "AppCatalog"
    private const val ICON_PX = 128
    private val icons = LruCache<String, ImageBitmap>(200)

    suspend fun launchableApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        // The flags-object overload is API 33+; the int one works on every level.
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(intent, 0)
        found
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName) return@mapNotNull null
                AppInfo(ri.loadLabel(pm).toString(), ai.packageName, ai.name, categoryOf(ai.applicationInfo))
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** The app's category; a game that sets only the old `isGame` flag counts as a game, as in the system settings. */
    @Suppress("DEPRECATION")
    private fun categoryOf(info: ApplicationInfo?): Int = when {
        info == null -> ApplicationInfo.CATEGORY_UNDEFINED
        info.category == ApplicationInfo.CATEGORY_UNDEFINED && (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ->
            ApplicationInfo.CATEGORY_GAME
        else -> info.category
    }

    /** The app's icon as a bitmap, cached; null when the app is gone. */
    suspend fun icon(context: Context, packageName: String, activityName: String): ImageBitmap? {
        val key = "$packageName/$activityName"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getActivityIcon(ComponentName(packageName, activityName))
                drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { icons.put(key, it) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * A pinned shortcut's icon, cached; else its app's icon; null when neither is
     * found. Reading shortcuts needs the shortcut host permission, which only the
     * default home app has, so outside that role the app's icon stands in.
     */
    suspend fun shortcutIcon(context: Context, packageName: String, shortcutId: String): ImageBitmap? {
        val key = "shortcut:$packageName/$shortcutId"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val own = findShortcut(context, packageName, shortcutId)?.let { shortcutDrawable(context, it) }
            if (own != null) {
                own.toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { icons.put(key, it) }
            } else {
                try {
                    context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    }

    /** The icon of [info] (e.g. one in a pin request) as a bitmap; null when it has none. */
    suspend fun shortcutIcon(context: Context, info: ShortcutInfo): ImageBitmap? = withContext(Dispatchers.IO) {
        shortcutDrawable(context, info)?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
    }

    /**
     * Keep exactly [shortcutIds] of [packageName] pinned by Ara (the shortcuts of
     * that package still on the bar), so a removed entry is unpinned for Android
     * and the app that made it. Without the host permission nothing can be done.
     */
    suspend fun syncPinnedShortcuts(context: Context, packageName: String, shortcutIds: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                context.getSystemService(LauncherApps::class.java)
                    ?.pinShortcuts(packageName, shortcutIds, Process.myUserHandle())
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot unpin shortcuts of $packageName (not the home app)", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot unpin shortcuts of $packageName (user locked)", e)
            }
        }
    }

    private fun findShortcut(context: Context, packageName: String, shortcutId: String): ShortcutInfo? {
        val apps = context.getSystemService(LauncherApps::class.java) ?: return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(shortcutId))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        return try {
            apps.getShortcuts(query, Process.myUserHandle())?.firstOrNull()
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun shortcutDrawable(context: Context, info: ShortcutInfo): Drawable? = try {
        context.getSystemService(LauncherApps::class.java)
            ?.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    } catch (e: Exception) {
        // SecurityException without the host permission, IllegalStateException while locked.
        null
    }
}
