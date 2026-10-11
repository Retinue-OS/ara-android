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

    /**
     * Post the wake notification; false when it cannot be shown (see
     * [notificationsAllowed]) — the wake then cannot reach the shell.
     */
    fun show(context: Context, wakeIntent: Intent): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!notificationsAllowed(context)) {
            Log.w(TAG, "notifications not allowed: the wake cannot reach the shell")
            return false
        }
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
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "could not post the wake notification", e)
            false
        }
    }

    /**
     * Whether a wake heard in the background can reach the shell: the app may
     * post notifications (Android 13+ permission), they are not turned off for
     * the app, and the wake channel is not blocked.
     */
    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        // Created here, so the channel is listed (and can be blocked) in the system settings.
        ensureChannel(context)
        val channel = manager.getNotificationChannelCompat(CHANNEL) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** The system page of this app's notifications, where the user turns them (or a channel) on. */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.wake_alert_channel_name))
                .setDescription(context.getString(R.string.wake_alert_channel_desc))
                .setShowBadge(false)
                // The chime has already played.
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
        )
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
