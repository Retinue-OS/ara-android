package os.retinue.ara.keyboard

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * The foreground service (type connectedDevice) that keeps Ara registered as
 * a Bluetooth keyboard while it is in the background, with its ongoing
 * notification. It does no work itself: [KeyboardRuntime] holds the
 * registration; this only keeps the process alive and says so. It runs only
 * while the keyboard is on and Bluetooth is on.
 *
 * Started with startForegroundService, it must enter the foreground even when
 * it cannot stay (the permissions were taken back, started from the
 * background): otherwise Android kills the app with "did not then call
 * startForeground()". It then leaves at once — like WakeService.
 */
class KeyboardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        starting = false
        val notification = notification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            // Asked to stop before it got here (Bluetooth on and off at once):
            // the start's promise is kept, then it goes.
            if (stopRequested) {
                stopRequested = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            // Started from the background, or the permissions were taken back:
            // the keyboard then works while Ara is in front only.
            Log.w(TAG, "could not enter the foreground", e)
            leave(notification)
        }
    }

    /**
     * Satisfy startForegroundService's promise without the connectedDevice
     * type, then stop. A short service needs no permission (Android 14+);
     * before that the type is not checked when entering the foreground.
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
            Log.w(TAG, "stopForeground failed", e)
        }
        stopSelf()
    }

    // Not sticky: a restart by the system would happen in the background,
    // where a connectedDevice service may not start; the app's next start
    // (KeyboardRuntime.init) brings it back.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun notification(): android.app.Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.keyboard_channel_name))
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, os.retinue.ara.ui.KeyboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(getString(R.string.keyboard_notification_title))
            .setContentText(getString(R.string.keyboard_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "KeyboardService"
        private const val CHANNEL = "keyboard"
        private const val NOTIFICATION_ID = 7

        /** Started, and onCreate not yet run: a stop then waits for it. */
        @Volatile
        private var starting = false

        @Volatile
        private var stopRequested = false

        fun start(context: Context) {
            try {
                stopRequested = false
                starting = true
                ContextCompat.startForegroundService(context, Intent(context, KeyboardService::class.java))
            } catch (e: Exception) {
                starting = false
                Log.w(TAG, "could not start", e)
            }
        }

        /**
         * Stop the service. Before its onCreate has run, stopping it would break
         * startForegroundService's promise (Android kills the app for that):
         * onCreate then enters the foreground and leaves at once instead.
         */
        fun stop(context: Context) {
            if (starting) {
                stopRequested = true
                return
            }
            context.stopService(Intent(context, KeyboardService::class.java))
        }
    }
}
