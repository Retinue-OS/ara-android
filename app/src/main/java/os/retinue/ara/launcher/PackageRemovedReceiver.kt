package os.retinue.ara.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import os.retinue.ara.data.AraStore

/**
 * Takes an uninstalled app off the launcher bar and out of its folders, with
 * the web apps it installed there (its pinned shortcuts); folders stay, also
 * when that empties them. Android sends ACTION_PACKAGE_FULLY_REMOVED only for
 * an app uninstalled together with its data, and starts Ara for it when Ara
 * is not running. An update, a disabled app, an app on storage that is not
 * mounted and (Android 15+) an archived app send none, so they keep their
 * place: Ara could not tell those from an uninstalled app by asking the
 * package manager, which hides an app whose code is not loaded from apps
 * without QUERY_ALL_PACKAGES.
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val store = AraStore(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                store.updateLauncher { it.withoutPackages(setOf(packageName)) }
            } catch (e: Exception) {
                // The tiles stay; tapping one says that no app can open it.
                Log.w(TAG, "could not take $packageName off the bar", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PackageRemoved"
    }
}
