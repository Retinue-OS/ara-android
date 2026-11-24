package os.retinue.ara.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The alarms and notification actions of chamber-app reminders
 * ([ReminderRuntime]): an occurrence fires, *In 15 min*, *Skip today*, the
 * periodic refresh, and the reboot (alarms do not survive one: they are armed
 * again from the server's plan). Not exported: only the system's alarm and the
 * app's own PendingIntents reach it, besides the boot broadcast.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val occ = AppsLogic.decode(intent.getStringExtra(EXTRA_OCCURRENCE))
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    when (action) {
                        ACTION_FIRE -> occ?.let { ReminderRuntime.fired(app, it) }
                        ACTION_SNOOZE -> occ?.let { ReminderRuntime.snooze(app, it) }
                        ACTION_SKIP -> occ?.let { ReminderRuntime.skip(app, it) }
                        ACTION_REFRESH -> ReminderRuntime.refresh(app)
                        // Alarms die with a reboot or an update: the stored
                        // plan is armed again at once (offline too), then fetched.
                        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                            ReminderRuntime.rearm(app)
                            ReminderRuntime.refresh(app)
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        // A broadcast may work for about ten seconds after goAsync.
        private const val WORK_TIMEOUT_MS = 9_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val ACTION_FIRE = "os.retinue.ara.reminder.FIRE"
        const val ACTION_SNOOZE = "os.retinue.ara.reminder.SNOOZE"
        const val ACTION_SKIP = "os.retinue.ara.reminder.SKIP"
        const val ACTION_REFRESH = "os.retinue.ara.reminder.REFRESH"
        const val EXTRA_OCCURRENCE = "occurrence"
    }
}
