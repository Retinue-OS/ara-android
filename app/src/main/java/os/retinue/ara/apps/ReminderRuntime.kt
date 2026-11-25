package os.retinue.ara.apps

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.InstanceState
import os.retinue.ara.ui.MainActivity

/**
 * Chamber-app reminders on the phone (Retinue's docs/chamber-apps.md,
 * "Reminders", "Android"). Web Push does not reach the WebView, so the shell
 * fetches each endpoint's reminders (`GET /apps/api/schedule?arm=1`) and arms
 * every occurrence of the next two days as an Android alarm: they fire offline
 * too. When one fires, the shell shows the notification (with *In 15 min* and
 * *Skip today*) and confirms it delivered, so the server does not push it a
 * second time; a tap opens the app's deep link in the shell.
 *
 * Endpoints shown or working in the background are armed; a disabled one is
 * not, and its alarms are cancelled. The plan is fetched again when the app
 * comes to the front, when a page says it changed (`remindersChanged`), after
 * each alarm, on boot and every few hours. A request that cannot be sent now
 * (the phone is offline) waits in an outbox and goes with the next fetch.
 */
object ReminderRuntime {

    private const val TAG = "ReminderRuntime"
    private const val PREFS = "chamber_app_reminders"
    private const val K_ARMED = "armed"          // arm key → encoded occurrence
    private const val K_SNOOZES = "snoozes"      // arm keys of snoozes the phone armed itself
    private const val K_OUTBOX = "outbox"        // [{endpoint, path, body}]
    private const val K_SNOOZE_MIN = "snooze_minutes"  // per endpoint: "snooze_minutes:<id>"
    private const val MAX_OUTBOX = 100
    private const val REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L

    const val CHANNEL_RING = "chamber-app-reminders"
    const val CHANNEL_QUIET = "chamber-app-reminders-quiet"
    const val CHANNEL_APP = "chamber-app-notices"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Alarms and their stored plan; held only for local work, never across a request.
    private val lock = Mutex()
    // The outbox; likewise.
    private val outboxLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** At process start: the channels, the periodic refresh, and one fetch. */
    fun init(context: Context) {
        val app = context.applicationContext
        createChannels(app)
        scheduleRefresh(app)
        refreshSoon(app)
    }

    private const val RESUME_REFRESH_MS = 5 * 60 * 1000L
    @Volatile private var lastResumeRefresh = 0L

    /** The shell came to the front: fetch the plan again, at most every five minutes. */
    fun onShellResumed(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastResumeRefresh != 0L && now - lastResumeRefresh < RESUME_REFRESH_MS) return
        lastResumeRefresh = now
        refreshSoon(context)
    }

    /** Fetch and re-arm, in the background ([endpointId] null: every endpoint that is not disabled). */
    fun refreshSoon(context: Context, endpointId: String? = null) {
        val app = context.applicationContext
        scope.launch { runCatching { refresh(app, endpointId) }.onFailure { Log.w(TAG, "refresh failed", it) } }
    }

    /** Fetch every eligible endpoint's reminders and bring the alarms in line. */
    suspend fun refresh(context: Context, endpointId: String? = null) {
        val settings = AraStore(context).settingsNow()
        val set = settings.endpointSet
        val eligible = set.endpoints.filter { set.stateOf(it) != InstanceState.Disabled }
        val eligibleIds = eligible.map { it.id }.toSet()
        // Alarms of endpoints that are gone or disabled: cancelled.
        lock.withLock {
            for ((key, occ) in armed(context)) {
                if (occ.endpointId !in eligibleIds) cancel(context, key)
            }
        }
        for (endpoint in eligible) {
            if (endpointId != null && endpoint.id != endpointId) continue
            // The network stays outside the lock: a tap on Skip or Snooze
            // must never wait behind a stalled fetch (the receiver has
            // seconds, not minutes).
            flushOutbox(context, endpoint)
            val base = endpoint.base ?: continue
            val document = AppsClient.get(context, endpoint, AppsLogic.SCHEDULE_PATH) ?: continue
            lock.withLock {
                prefs(context).edit().putInt(snoozeKey(endpoint.id), AppsLogic.snoozeMinutes(document)).apply()
                val now = System.currentTimeMillis()
                val wanted = AppsLogic.parseSchedule(document, base, endpoint.id, now)
                val plan = AppsLogic.plan(endpoint.id, armed(context), wanted, snoozes(context))
                plan.cancel.forEach { cancel(context, it) }
                plan.arm.forEach { arm(context, it) }
                // A local snooze the server now lists is an ordinary occurrence.
                val listed = wanted.map { it.armKey }.toSet()
                setSnoozes(context, snoozes(context).filter { it !in listed && it in armed(context) }.toSet())
            }
        }
    }

    // ---- alarms ----------------------------------------------------------------

    /** Arm every stored occurrence again (after a reboot or an update, which drop the alarms). */
    suspend fun rearm(context: Context) = lock.withLock {
        val now = System.currentTimeMillis()
        for ((key, occ) in armed(context)) {
            if (occ.atMs < now - AppsLogic.LATE_MS) cancel(context, key) else arm(context, occ)
        }
    }

    fun armed(context: Context): Map<String, ReminderOccurrence> =
        (prefs(context).getStringSet(K_ARMED, emptySet()) ?: emptySet())
            .mapNotNull { AppsLogic.decode(it) }
            .associateBy { it.armKey }

    private fun putArmed(context: Context, map: Map<String, ReminderOccurrence>) {
        prefs(context).edit().putStringSet(K_ARMED, map.values.map { AppsLogic.encode(it) }.toSet()).apply()
    }

    private fun arm(context: Context, occ: ReminderOccurrence) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePendingIntent(context, occ)
        val at = occ.atMs.coerceAtLeast(System.currentTimeMillis() + 1000)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Without the exact-alarm permission the system may deliver it
                // a few minutes late; the reminder still arrives offline.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        putArmed(context, armed(context) + (occ.armKey to occ))
    }

    private fun cancel(context: Context, armKey: String) {
        val occ = armed(context)[armKey] ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context, occ))
        putArmed(context, armed(context) - armKey)
    }

    private fun firePendingIntent(context: Context, occ: ReminderOccurrence): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            // A distinct data URI per occurrence, so the PendingIntents never collapse.
            .setData(Uri.parse("retinue-reminder://${AppsLogic.requestCode(occ.armKey)}"))
            .putExtra(ReminderReceiver.EXTRA_OCCURRENCE, AppsLogic.encode(occ))
        return PendingIntent.getBroadcast(
            context, AppsLogic.requestCode(occ.armKey), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The periodic refresh: inexact, every few hours, survives the app being closed (not a reboot; see the receiver). */
    private fun scheduleRefresh(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REFRESH)
        val pi = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MS,
            REFRESH_INTERVAL_MS,
            pi,
        )
    }

    // ---- an alarm fired, an action was pressed -----------------------------------

    /** The alarm of [occ] fired: show it, confirm it, and fetch what comes next. */
    suspend fun fired(context: Context, occ: ReminderOccurrence) {
        lock.withLock {
            putArmed(context, armed(context) - occ.armKey)
            setSnoozes(context, snoozes(context) - occ.armKey)
        }
        val endpoint = endpointOf(context, occ.endpointId) ?: return
        // Confirmed only when the user can see it: with notifications off (the
        // app's, or the channel's) the server pushes it to the other devices.
        if (show(context, occ)) send(context, endpoint, occ, "delivered", AppsLogic.deliveredBody(occ.key))
        refresh(context, occ.endpointId)
    }

    /** *In 15 min*: the phone arms the snooze itself (it works offline) and tells the server the instant. */
    suspend fun snooze(context: Context, occ: ReminderOccurrence) {
        NotificationManagerCompat.from(context).cancel(AppsLogic.requestCode(occ.armKey))
        val minutes = snoozeMinutes(context, occ.endpointId)
        val next = AppsLogic.snoozed(occ, System.currentTimeMillis(), minutes)
        lock.withLock {
            arm(context, next)
            setSnoozes(context, snoozes(context) + next.armKey)
        }
        val endpoint = endpointOf(context, occ.endpointId) ?: return
        send(context, endpoint, occ, "snooze", AppsLogic.snoozeBody(next.atMs))
    }

    /** *Skip today*: the server skips the rest of the day; the next fetch drops today's alarms. */
    suspend fun skip(context: Context, occ: ReminderOccurrence) {
        NotificationManagerCompat.from(context).cancel(AppsLogic.requestCode(occ.armKey))
        // The rest of the day's alarms go now, without waiting for the server:
        // a skip tapped offline must not be followed by today's later rings.
        lock.withLock {
            val day = AppsLogic.sameDayArmed(armed(context), occ, java.time.ZoneId.systemDefault())
            day.forEach { cancel(context, it) }
            setSnoozes(context, snoozes(context) - day)
        }
        val endpoint = endpointOf(context, occ.endpointId) ?: return
        // The occurrence names the day, so a skip flushed from the outbox
        // tomorrow still skips today, not tomorrow.
        if (send(context, endpoint, occ, "skip", AppsLogic.skipBody(occ.key))) refresh(context, occ.endpointId)
    }

    private suspend fun endpointOf(context: Context, id: String): Endpoint? =
        AraStore(context).settingsNow().endpoints.firstOrNull { it.id == id }

    /**
     * Post an action, kept in the outbox *first* and taken out once the
     * server has it (or gave an answer a retry would not change). The
     * receiver that calls this may be stopped after a few seconds, while a
     * stalled request can take longer: whatever happens to this call, the
     * action is not lost, and the next fetch sends it. Sending one twice is
     * harmless: `delivered`, `snooze` (with its `at`) and `skip` (with its
     * occurrence) name what they act on. True when it went now.
     */
    private suspend fun send(context: Context, endpoint: Endpoint, occ: ReminderOccurrence, action: String, body: String): Boolean {
        val path = AppsLogic.actionPath(occ.chamber, occ.app, occ.job, action) ?: return true
        val id = outboxLock.withLock { enqueue(context, endpoint.id, path, body) }
        if (!AppsClient.post(context, endpoint, path, body)) return false
        outboxLock.withLock { removeFromOutbox(context, id) }
        return true
    }

    private data class Pending(val id: String, val endpointId: String, val path: String, val body: String)

    private fun enqueue(context: Context, endpointId: String, path: String, body: String): String {
        val id = java.util.UUID.randomUUID().toString()
        writeOutbox(context, (outbox(context) + Pending(id, endpointId, path, body)).takeLast(MAX_OUTBOX))
        return id
    }

    private fun removeFromOutbox(context: Context, id: String) {
        val all = outbox(context)
        val left = all.filter { it.id != id }
        if (left.size != all.size) writeOutbox(context, left)
    }

    /** Send what waits for [endpoint]; each entry leaves the outbox once it went. */
    private suspend fun flushOutbox(context: Context, endpoint: Endpoint) {
        val waiting = outboxLock.withLock { outbox(context).filter { it.endpointId == endpoint.id } }
        for (p in waiting) {
            if (AppsClient.post(context, endpoint, p.path, p.body)) {
                outboxLock.withLock { removeFromOutbox(context, p.id) }
            }
        }
    }

    private fun outbox(context: Context): List<Pending> {
        val raw = prefs(context).getString(K_OUTBOX, null) ?: return emptyList()
        return try {
            (json.parseToJsonElement(raw) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val endpointId = (o["endpoint"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val path = (o["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val body = (o["body"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                // An entry an earlier build wrote has no id: it gets one now.
                val id = (o["id"] as? JsonPrimitive)?.content ?: java.util.UUID.randomUUID().toString()
                Pending(id, endpointId, path, body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeOutbox(context: Context, list: List<Pending>) {
        val raw = buildJsonArray {
            list.forEach { p ->
                add(buildJsonObject { put("id", p.id); put("endpoint", p.endpointId); put("path", p.path); put("body", p.body) })
            }
        }.toString()
        // commit, not apply: the receiver's process may end right after.
        prefs(context).edit().putString(K_OUTBOX, raw).commit()
    }

    private fun snoozes(context: Context): Set<String> =
        prefs(context).getStringSet(K_SNOOZES, emptySet()) ?: emptySet()

    private fun setSnoozes(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(K_SNOOZES, keys).apply()
    }

    /** Each server names its own snooze; one endpoint's never stands in for another's. */
    private fun snoozeKey(endpointId: String) = "$K_SNOOZE_MIN:$endpointId"

    private fun snoozeMinutes(context: Context, endpointId: String): Int =
        prefs(context).getInt(snoozeKey(endpointId), AppsLogic.DEFAULT_SNOOZE_MINUTES)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- notifications ---------------------------------------------------------------

    private fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_RING, NotificationManager.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.reminders_channel)).build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_QUIET, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.reminders_channel_quiet)).build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_APP, NotificationManager.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.app_notices_channel)).build(),
        )
    }

    /**
     * The intent that opens [url] in the shell: the same `retinue-shell:` link
     * a browser hands over, so it loads only on a configured origin (and asks
     * before switching to another endpoint), see ShellLinks.
     */
    fun openIntent(context: Context, url: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("retinue-shell://open?url=" + Uri.encode(url)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Whether a notification on [channel] reaches the user: notifications are
     * allowed for the app (POST_NOTIFICATIONS on Android 13+) and the channel
     * is not switched off.
     */
    private fun deliverable(context: Context, channel: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val importance = manager.getNotificationChannelCompat(channel)?.importance
        return importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Show [occ]'s notification; false when it cannot reach the user. */
    private fun show(context: Context, occ: ReminderOccurrence): Boolean {
        val channel = if (occ.ring) CHANNEL_RING else CHANNEL_QUIET
        if (!deliverable(context, channel)) {
            Log.w(TAG, "reminder ${occ.reminderId} not shown: notifications are off")
            return false
        }
        val id = AppsLogic.requestCode(occ.armKey)
        val content = PendingIntent.getActivity(
            context, id, openIntent(context, occ.url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = snoozeMinutes(context, occ.endpointId)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(occ.title)
            .setContentText(occ.body.ifEmpty { context.getString(R.string.reminder_default_body) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(occ.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (occ.ring) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(content)
            .addAction(0, context.getString(R.string.reminder_snooze, minutes), actionIntent(context, occ, ReminderReceiver.ACTION_SNOOZE))
            .addAction(0, context.getString(R.string.reminder_skip), actionIntent(context, occ, ReminderReceiver.ACTION_SKIP))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not allowed: ${e.message}")
            false
        }
    }

    private fun actionIntent(context: Context, occ: ReminderOccurrence, action: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("retinue-reminder://${AppsLogic.requestCode(occ.armKey)}/$action"))
            .putExtra(ReminderReceiver.EXTRA_OCCURRENCE, AppsLogic.encode(occ))
        return PendingIntent.getBroadcast(
            context, AppsLogic.requestCode(occ.armKey + action), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * `RetinueShell.notify(title, body)`: an immediate notification while an
     * app page is open ("next set in 10 s"), not a reminder. A tap brings the
     * shell to the front.
     */
    fun notifyNow(context: Context, title: String, body: String) {
        val t = title.trim().take(120).ifEmpty { return }
        val b = body.trim().take(500)
        val content = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_stat_ara)
            .setContentTitle(t)
            .setContentText(b)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .setContentIntent(content)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTICE_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not allowed: ${e.message}")
        }
    }

    private const val NOTICE_ID = 0x5e7
}
