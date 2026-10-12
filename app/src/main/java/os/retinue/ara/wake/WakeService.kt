package os.retinue.ara.wake

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import os.retinue.ara.R
import os.retinue.ara.ui.MainActivity

/**
 * The foreground service of wake mode "always" (type microphone, with its
 * ongoing notification). It does no work itself: while it runs, [WakeRuntime]
 * may keep the mic open when the shell is not visible. Started only while the
 * shell is resumed and the mic permission granted, as Android 14 requires for
 * a microphone service.
 *
 * Started with startForegroundService, it must enter the foreground even when
 * it cannot stay (no permission, refused): otherwise Android kills the app
 * with "did not then call startForeground()". It then leaves at once and
 * [WakeRuntime] waits before the next attempt.
 */
class WakeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notification = notification()
        if (granted) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                WakeRuntime.serviceStarted()
                return
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException / SecurityException (started from background).
                Log.w(TAG, "could not enter the foreground", e)
            }
        }
        leave(notification)
    }

    /**
     * Satisfy startForegroundService's promise without the microphone type, then
     * stop. A short service needs no permission (Android 14+); before that the
     * type is not checked when entering the foreground.
     */
    private fun leave(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not enter the foreground to stop", e)
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "could not leave the foreground", e)
        }
        WakeRuntime.serviceFailed()
        stopSelf()
    }

    // Not sticky: a restart by the system would happen in the background, where
    // Android 14 does not allow a microphone service to start.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        WakeRuntime.serviceStopped()
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.wake_channel_name))
                .setDescription(getString(R.string.wake_channel_desc))
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val TAG = "WakeService"
        const val CHANNEL = "wake"
        const val NOTIFICATION_ID = 1
    }
}
