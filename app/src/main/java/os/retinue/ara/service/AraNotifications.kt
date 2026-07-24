package os.retinue.ara.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import os.retinue.ara.R
import os.retinue.ara.ui.MainActivity

/**
 * Notification channel and builders for Ara job activity. All content is
 * privacy-safe: neither the prompt nor the answer is included, and lock-screen
 * visibility is private so nothing sensitive is shown on a locked device.
 */
object AraNotifications {

    const val CHANNEL_ID = "ara_job_activity"
    const val ACTIVE_JOB_ID = 1001
    const val ANSWER_READY_ID = 1002

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPollingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PollingService::class.java).apply {
            action = PollingService.ACTION_CANCEL
        }
        return PendingIntent.getService(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Ongoing notification shown while a job is polling in the background. */
    fun activeJob(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(context.getString(R.string.notif_waiting_title))
            .setContentText(context.getString(R.string.notif_waiting_body))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.notif_cancel), cancelPollingIntent(context))
            .build()

    /** Privacy-safe alert shown when a job completes while backgrounded. */
    fun answerReady(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(context.getString(R.string.notif_answer_title))
            .setContentText(context.getString(R.string.notif_answer_body))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

    fun showAnswerReady(context: Context) {
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(ACTIVE_JOB_ID)
        // Posting requires POST_NOTIFICATIONS on 33+. If denied this is a no-op;
        // the app never claims the user will be alerted.
        if (mgr.areNotificationsEnabled()) {
            mgr.notify(ANSWER_READY_ID, answerReady(context))
        }
    }

    fun cancelAll(context: Context) {
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(ACTIVE_JOB_ID)
        mgr.cancel(ANSWER_READY_ID)
    }
}
