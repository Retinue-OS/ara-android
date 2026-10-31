package os.retinue.ara.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherEntry

/**
 * The shell in a window of its own, for split screen. Android keeps the home
 * activity full screen, behind everything, so the home screen itself can never
 * share the screen with an app; this second activity, in a task of its own,
 * can. It is the same shell: the same page in the same state (the one WebView
 * moves over, see [ShellPage]) and the same bar, and what it starts opens in
 * the other half of the screen ([launchAdjacent]).
 *
 * The home screen comes here to open an app beside Ara ([openIntent]) and to
 * keep this window among the recent apps ([parkIntent]). It is not exported:
 * only Ara starts it, and Recents brings it back.
 */
class SplitShellActivity : MainActivity() {

    override val isSplitWindow: Boolean get() = true

    companion object {
        /** A launcher entry ([LauncherCodec.encodeEntry]) to start beside the window once it is in front. */
        const val EXTRA_ENTRY = "os.retinue.ara.extra.SPLIT_ENTRY"

        /** Go to the back at once: only be among the recent apps. */
        const val EXTRA_PARK = "os.retinue.ara.extra.SPLIT_PARK"

        /** When the home screen last put the window among the recent apps (elapsedRealtime), for the retry pause. */
        var lastParkAt: Long? = null

        fun openIntent(context: Context, entry: LauncherEntry): Intent =
            Intent(context, SplitShellActivity::class.java).putExtra(EXTRA_ENTRY, LauncherCodec.encodeEntry(entry))

        /** No animation for a task that goes straight to the back. */
        fun parkIntent(context: Context): Intent =
            Intent(context, SplitShellActivity::class.java)
                .putExtra(EXTRA_PARK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** Whether the window's task is among the recent apps (Ara may list its own tasks). */
        fun inRecents(context: Context): Boolean {
            val tasks = context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
            return tasks.any { task ->
                val info = try {
                    task.taskInfo
                } catch (_: IllegalArgumentException) {
                    // The task went away meanwhile.
                    null
                }
                info?.baseIntent?.component?.className == SplitShellActivity::class.java.name
            }
        }
    }
}
