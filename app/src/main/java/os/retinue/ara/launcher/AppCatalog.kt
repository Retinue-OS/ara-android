package os.retinue.ara.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/** A launchable activity of an installed app. */
data class AppInfo(val label: String, val packageName: String, val activityName: String)

/** A shortcut Ara holds pinned: an installed web app, a browser's home-screen link. */
data class PinnedShortcut(val label: String, val packageName: String, val shortcutId: String)

/**
 * The installed launchable apps (visible through the manifest's `<queries>` for
 * MAIN/LAUNCHER, without QUERY_ALL_PACKAGES; Chrome's WebAPKs are such apps) and
 * their icons, and the shortcuts pinned to Ara (listed with the apps, and on
 * the bar where the user put them).
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
                AppInfo(ri.loadLabel(pm).toString(), ai.packageName, ai.name)
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
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
     * Every shortcut pinned to Ara, sorted by label, so installed web apps show
     * up with the apps. Reading them needs the shortcut host permission, which
     * only the default home app has; outside that role the list is empty.
     */
    suspend fun pinnedShortcuts(context: Context): List<PinnedShortcut> = withContext(Dispatchers.IO) {
        val apps = context.getSystemService(LauncherApps::class.java) ?: return@withContext emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val found = try {
            apps.getShortcuts(query, Process.myUserHandle()).orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: IllegalStateException) {
            emptyList()
        }
        val collator = Collator.getInstance()
        found
            .filter { it.isPinned && it.isEnabled }
            .map { PinnedShortcut(labelOf(it), it.`package`, it.id) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /**
     * Unpin one shortcut, keeping the package's other pinned shortcuts, so
     * Android and the app that made it know it is gone. Without the host
     * permission nothing can be done.
     */
    suspend fun unpinShortcut(context: Context, packageName: String, shortcutId: String) {
        withContext(Dispatchers.IO) {
            val apps = context.getSystemService(LauncherApps::class.java) ?: return@withContext
            try {
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                val keep = apps.getShortcuts(query, Process.myUserHandle()).orEmpty()
                    .filter { it.isPinned && it.id != shortcutId }
                    .map { it.id }
                apps.pinShortcuts(packageName, keep, Process.myUserHandle())
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot unpin shortcuts of $packageName (not the home app)", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot unpin shortcuts of $packageName (user locked)", e)
            }
        }
    }

    /** The short label, else the long one, else the package name. */
    fun labelOf(info: ShortcutInfo): String {
        info.shortLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        info.longLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return info.`package`
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
