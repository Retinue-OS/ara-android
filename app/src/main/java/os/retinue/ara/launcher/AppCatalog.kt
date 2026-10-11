package os.retinue.ara.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable activity of an installed app. */
data class AppInfo(val label: String, val packageName: String, val activityName: String)

/**
 * The installed launchable apps (visible through the manifest's `<queries>` for
 * MAIN/LAUNCHER, without QUERY_ALL_PACKAGES) and their icons.
 */
object AppCatalog {

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
}
