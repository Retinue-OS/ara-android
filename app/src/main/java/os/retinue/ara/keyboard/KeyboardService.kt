package os.retinue.ara.keyboard

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * registration; this only keeps the process alive and says so.
 */
class KeyboardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Started from the background, or the permissions were taken back:
            // the keyboard then works while Ara is in front only.
            Log.w(TAG, "could not enter the foreground", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeyboardService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeyboardService::class.java))
        }
    }
}
