package os.retinue.ara.launcher

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's usage stats: when each app was last on screen, however it was
 * opened (from Ara, a notification, Recents, another app). Optional: it
 * needs usage access, which only the user grants, in the system settings;
 * without it the tray orders apps by Ara's own launches (see [AppRecency]).
 */
object AppUsage {

    private const val TAG = "AppUsage"

    /** How far back the tray looks. */
    private const val SPAN_MS = 365L * 24 * 60 * 60 * 1000

    /** Whether the user has given Ara usage access. */
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * When each package was last on screen (epoch ms), Ara left out; empty
     * without usage access.
     */
    suspend fun lastUsed(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
        if (!hasAccess(context)) return@withContext emptyMap()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val stats = try {
            manager.queryAndAggregateUsageStats(now - SPAN_MS, now)
        } catch (e: Exception) {
            // SecurityException if access was withdrawn meanwhile, IllegalStateException while locked.
            Log.w(TAG, "usage stats unavailable", e)
            return@withContext emptyMap()
        }
        stats.orEmpty()
            .filterKeys { it != context.packageName }
            .mapValues { it.value.lastTimeVisible }
            .filterValues { it > 0 }
    }

    /** Open the system page where the user gives Ara usage access (Ara's own page where the system has one). */
    fun openAccessSettings(context: Context) {
        val own = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        try {
            context.startActivity(own)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no usage access settings", e)
            }
        }
    }
}
