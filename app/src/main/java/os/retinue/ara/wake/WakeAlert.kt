package os.retinue.ara.wake

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import os.retinue.ara.R
import os.retinue.ara.shell.ShellDecisions

/**
 * The notification that carries a wake heard in the background. Android 10+
 * does not let a background app start an activity, so the wake goes out as a
 * high-priority notification with a full-screen intent: on a locked or sleeping
 * phone the system opens the shell at once, otherwise it shows a heads-up the
 * user taps. Cancelled as soon as the shell has the wake.
 */
object WakeAlert {

    private const val TAG = "WakeAlert"
    private const val CHANNEL = "wake_alert"
    private const val NOTIFICATION_ID = 2
    private const val REQUEST_CODE = 1

    /** How long the heads-up stays: as long as its wake token is good (the pending-wake window). */
    private const val TIMEOUT_MS = ShellDecisions.PENDING_WAKE_MAX_MS

    fun show(context: Context, wakeIntent: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notifications not allowed: the wake cannot reach the shell")
            return
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.wake_alert_channel_name))
                .setDescription(context.getString(R.string.wake_alert_channel_desc))
                .setShowBadge(false)
                // The chime has already played.
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            wakeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(context.getString(R.string.wake_alert_title))
            .setContentText(context.getString(R.string.wake_alert_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not post the wake notification", e)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Whether the full-screen intent may open the shell. Android 14 grants it by
     * default only to calling and alarm apps; others need the user's approval.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.canUseFullScreenIntent()
    }

    /** The system page where the user allows full-screen intents (Android 14+), or null. */
    fun fullScreenIntentSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:" + context.packageName))
    }
}
