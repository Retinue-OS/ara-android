package os.retinue.ara.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.net.ssl.SSLException
import os.retinue.ara.data.AraSettings
import os.retinue.ara.data.AraStore
import os.retinue.ara.net.AraHttp
import os.retinue.ara.net.CertificateUnavailableException
import os.retinue.ara.net.MissingCertificateException
import os.retinue.ara.net.RetinueClient
import os.retinue.ara.protocol.PollingPolicy
import os.retinue.ara.protocol.RetinueProtocol
import os.retinue.ara.state.JobRecord
import os.retinue.ara.state.JobStatus

/**
 * Foreground service (type dataSync) that owns the single active Retinue job:
 * it performs the one POST, then polls the job URI to completion. It survives
 * the Activity going to the background, publishes state via [JobBus], persists a
 * durable [JobRecord] for recovery, shows the required ongoing notification, and
 * stops on completion, terminal failure, timeout, or explicit cancellation.
 *
 * It never touches the microphone or text-to-speech — those are foreground-only
 * concerns handled by the ViewModel.
 */
class PollingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null
    private lateinit var store: AraStore
    private val client = RetinueClient()
    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        store = AraStore(applicationContext)
        AraNotifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelLocalPolling()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                enterForeground()
                startWork(prompt = null)
            }
            else -> { // ACTION_SUBMIT
                enterForeground()
                startWork(prompt = intent?.getStringExtra(EXTRA_PROMPT))
            }
        }
        // Recovery is driven by the persisted JobRecord when the app reopens, not
        // by sticky redelivery, so we do not ask to be recreated automatically.
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        foregroundOk = try {
            startForeground(
                AraNotifications.ACTIVE_JOB_ID,
                AraNotifications.activeJob(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException. Report and keep
            // polling best-effort (works while Ara stays in the foreground).
            JobBus.publishError(ServiceError(ServiceErrorKind.ServiceStartFailed, e.message))
            false
        }
    }

    private fun startWork(prompt: String?) {
        work?.cancel()
        work = serviceScope.launch {
            val settings = store.settingsNow()
            if (prompt != null) submitThenPoll(prompt, settings) else resumePersisted(settings)
        }
    }

    private suspend fun submitThenPoll(prompt: String, settings: AraSettings) {
        val messageUrl = settings.messageUrl()
        if (messageUrl == null) {
            fail(ServiceErrorKind.NoEndpoint); return
        }
        val alias = store.preferencesNow().selectedCertificateAlias
        val http = try {
            AraHttp.build(applicationContext, alias, settings.requestTimeoutMs)
        } catch (e: MissingCertificateException) {
            fail(ServiceErrorKind.MissingCertificate); return
        } catch (e: CertificateUnavailableException) {
            fail(ServiceErrorKind.CertificateUnavailable); return
        }

        val submit = try {
            client.submit(http, messageUrl, prompt)
        } catch (e: SSLException) {
            fail(ServiceErrorKind.Tls, e.message); return
        } catch (e: IOException) {
            fail(ServiceErrorKind.Submit, e.message); return
        }

        when (submit) {
            is RetinueProtocol.SubmitResult.Accepted -> {
                val now = System.currentTimeMillis()
                val record = JobRecord(
                    jobUri = submit.jobUri,
                    prompt = prompt,
                    submittedAtMs = now,
                    timeoutDeadlineMs = now + settings.overallJobTimeoutMs,
                    status = JobStatus.Pending,
                )
                persistAndPublish(record)
                pollLoop(http, record, settings)
            }
            is RetinueProtocol.SubmitResult.InlineAnswer -> {
                val now = System.currentTimeMillis()
                val record = JobRecord(
                    jobUri = "",
                    prompt = prompt,
                    submittedAtMs = now,
                    timeoutDeadlineMs = now,
                    status = JobStatus.Done,
                    answer = submit.answer,
                    entryUrl = submit.entryUrl,
                )
                finishDone(record)
            }
            is RetinueProtocol.SubmitResult.Blocked -> fail(ServiceErrorKind.Blocked, submit.message)
            is RetinueProtocol.SubmitResult.Malformed -> fail(ServiceErrorKind.BadJobUri, submit.detail)
        }
    }

    private suspend fun resumePersisted(settings: AraSettings) {
        val record = store.jobNow()
        if (record == null || record.cancelled || record.status != JobStatus.Pending) {
            stopEverything(keepAnswerNotification = false)
            return
        }
        if (PollingPolicy.isExpired(record.submittedAtMs, System.currentTimeMillis(), settings.overallJobTimeoutMs)) {
            finishTimeout(record); return
        }
        val alias = store.preferencesNow().selectedCertificateAlias
        val http = try {
            AraHttp.build(applicationContext, alias, settings.requestTimeoutMs)
        } catch (e: Exception) {
            fail(ServiceErrorKind.CertificateUnavailable); return
        }
        pollLoop(http, record, settings)
    }

    private suspend fun pollLoop(http: okhttp3.OkHttpClient, initial: JobRecord, settings: AraSettings) {
        var record = initial
        var interval = settings.pollIntervalMs
        var failures = 0
        while (serviceScope.isActive) {
            if (PollingPolicy.isExpired(record.submittedAtMs, System.currentTimeMillis(), settings.overallJobTimeoutMs)) {
                finishTimeout(record); return
            }
            delay(interval)
            val poll = try {
                client.poll(http, record.jobUri)
            } catch (e: SSLException) {
                finishError(record, "TLS: ${e.message}"); return
            } catch (e: IOException) {
                failures++
                if (!PollingPolicy.shouldRetryAfterNetworkError(failures, settings.maxConsecutivePollFailures)) {
                    finishError(record, e.message); return
                }
                interval = PollingPolicy.nextInterval(interval, settings.pollBackoff, settings.pollIntervalMaxMs)
                continue
            }
            failures = 0
            when (poll) {
                is RetinueProtocol.JobPoll.Pending ->
                    interval = PollingPolicy.nextInterval(interval, settings.pollBackoff, settings.pollIntervalMaxMs)
                is RetinueProtocol.JobPoll.Done -> {
                    finishDone(record.copy(status = JobStatus.Done, answer = poll.answer, entryUrl = poll.entryUrl))
                    return
                }
                is RetinueProtocol.JobPoll.Failed -> {
                    finishError(record, poll.error); return
                }
                is RetinueProtocol.JobPoll.Expired -> {
                    finishError(record, "job expired"); return
                }
                is RetinueProtocol.JobPoll.Malformed -> {
                    failures++
                    if (!PollingPolicy.shouldRetryAfterNetworkError(failures, settings.maxConsecutivePollFailures)) {
                        finishError(record, poll.detail); return
                    }
                    interval = PollingPolicy.nextInterval(interval, settings.pollBackoff, settings.pollIntervalMaxMs)
                }
            }
        }
    }

    private suspend fun persistAndPublish(record: JobRecord) {
        store.saveJob(record)
        JobBus.publish(record)
    }

    private suspend fun finishDone(record: JobRecord) {
        persistAndPublish(record)
        if (!AraForeground.inForeground) {
            AraNotifications.showAnswerReady(this)
            stopEverything(keepAnswerNotification = true)
        } else {
            stopEverything(keepAnswerNotification = false)
        }
    }

    private suspend fun finishError(record: JobRecord, message: String?) {
        persistAndPublish(record.copy(status = JobStatus.Error, errorMessage = message))
        stopEverything(keepAnswerNotification = false)
    }

    private suspend fun finishTimeout(record: JobRecord) {
        persistAndPublish(record.copy(status = JobStatus.TimedOut))
        stopEverything(keepAnswerNotification = false)
    }

    /** Submit-stage failure before any job exists. */
    private fun fail(kind: ServiceErrorKind, message: String? = null) {
        JobBus.publishError(ServiceError(kind, message))
        stopEverything(keepAnswerNotification = false)
    }

    private fun cancelLocalPolling() {
        work?.cancel()
        // Mark the persisted job cancelled so a later process recreation does not
        // resume it (distinguishes explicit cancel from process death).
        runBlocking {
            store.jobNow()?.let { store.saveJob(it.copy(cancelled = true)) }
        }
        JobBus.publish(JobBus.job.value?.copy(cancelled = true))
        AraNotifications.cancelAll(this)
        stopEverything(keepAnswerNotification = false)
    }

    private fun stopEverything(keepAnswerNotification: Boolean) {
        if (!keepAnswerNotification) AraNotifications.cancelAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Explicit task removal (Recents swipe). Cancel polling, mark the job
     * cancelled, drop notifications and stop — but never touch persisted
     * preferences. stopWithTask=true also tears the service down after this.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        work?.cancel()
        runBlocking {
            store.jobNow()?.let { store.saveJob(it.copy(cancelled = true)) }
        }
        AraNotifications.cancelAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SUBMIT = "os.retinue.ara.action.SUBMIT"
        const val ACTION_RESUME = "os.retinue.ara.action.RESUME"
        const val ACTION_CANCEL = "os.retinue.ara.action.CANCEL"
        const val EXTRA_PROMPT = "os.retinue.ara.extra.PROMPT"

        fun submit(context: Context, prompt: String) {
            val intent = Intent(context, PollingService::class.java).apply {
                action = ACTION_SUBMIT
                putExtra(EXTRA_PROMPT, prompt)
            }
            context.startForegroundService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, PollingService::class.java).apply { action = ACTION_RESUME }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, PollingService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }
}
