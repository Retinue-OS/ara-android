package os.retinue.ara.health

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.health.connect.client.HealthConnectClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import os.retinue.ara.BuildConfig
import os.retinue.ara.data.AraStore
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.UrlRouter

/**
 * Asks Health Connect for read permissions on the runtime's behalf: the window
 * in front, which has the activity-result launcher. Returns once the dialog
 * is done (or could not be shown); the runtime reads what was granted itself.
 */
fun interface HealthPermissionRequester {
    suspend fun request(permissions: Set<String>)
}

/** The page side of sync: [os.retinue.ara.ui.ShellPage] forwards both to the page. */
interface HealthSyncListener {
    fun onHealthStatus(status: HealthSyncStatus)
    fun onHealthEvent(event: HealthSyncEvent)
}

/**
 * Process-wide owner of Health Connect sync (contract §3). A sync reads what
 * changed since the stored Changes token (or, without a usable token, the
 * last [HealthSyncLogic.WINDOW_DAYS] days) and writes it, in parts of at most
 * [HealthSyncLogic.MAX_PART] changes, as files into the configured origin's
 * letterbox (`<origin>/inbox/`, a WebDAV share: one plain `PUT` per part),
 * authenticated as the WebView is (the client certificate and the WebView's
 * cookies for that origin). Before Health Connect is read, the letterbox is
 * probed: a server without one costs one small request, and automatic syncs
 * then back off (doubling from the interval up to six hours; so after any
 * upload error) until a sync succeeds. A round's parts are written to the
 * device first and uploaded one by one ([HealthRoundStore]); the token
 * advances only once every part was stored, and a round that failed halfway
 * is resumed by the next sync from the first unsent part, under the same
 * batch id, before anything new is read.
 *
 * Syncs run when Ara comes to the front (auto-sync, at most every
 * [HealthSyncState.intervalMinutes]) and when the page asks
 * (`RetinueShell.healthSync()`; always, a repeat within
 * [HealthSyncLogic.COLLAPSE_MS] is the same request). One runs at a time.
 * The main thread coordinates; reading records, building the JSON and the
 * HTTP calls run on IO. No Google Play services: the client binds to the
 * platform's Health Connect (Android 14+) or the Health Connect app.
 */
object HealthSyncRuntime {

    private const val TAG = "HealthSyncRuntime"
    private const val TIMEOUT_S = 60L
    private const val ROUND_DIR = "health-round"

    /** Records per readRecords page in an initial read (Health Connect allows up to 5000). */
    private const val PAGE_SIZE = 1000

    private lateinit var app: Context
    private lateinit var store: AraStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The stored state has been read. */
    private val loaded = CompletableDeferred<Unit>()
    private var state = HealthSyncState()
    private var available = false
    private var permissions = PermissionState.None

    /** The one sync running. */
    private var job: Job? = null

    /** When a deliberate sync was last asked for (epoch ms), for collapsing repeats. */
    private var lastDeliberateAt = 0L

    /** The round on its way to the letterbox, if any (see [HealthRoundStore]). */
    private lateinit var rounds: HealthRoundStore

    private val _status = MutableStateFlow(HealthSyncStatus())
    val status: StateFlow<HealthSyncStatus> = _status.asStateFlow()

    /** Set by the page while it exists; told the current status at once. Main thread. */
    var listener: HealthSyncListener? = null
        set(value) {
            field = value
            value?.onHealthStatus(_status.value)
        }

    fun init(context: Context) {
        app = context.applicationContext
        store = AraStore(app)
        rounds = HealthRoundStore(File(app.filesDir, ROUND_DIR))
        scope.launch {
            state = store.healthSync.first()
            loaded.complete(Unit)
            refresh()
        }
        // A round on the device is for one origin: when the base URL is changed
        // or cleared, a round for the old one goes at once, not at the next sync.
        scope.launch {
            store.settings.map { UrlRouter(it.base).origin?.toString() }.distinctUntilChanged().collect { origin ->
                withContext(Dispatchers.IO) { rounds.discardUnless(origin) }
            }
        }
    }

    /**
     * Ara came to the front: what Health Connect offers and grants may have
     * changed in the system settings meanwhile, and an automatic sync may be
     * due.
     */
    fun onShellResumed() {
        scope.launch {
            loaded.await()
            refresh()
            start(HealthTrigger.Resume, requester = null)
        }
    }

    /** The page asked for a sync ([trigger] manual or link); [requester] may ask for permissions first. */
    fun syncNow(trigger: HealthTrigger, requester: HealthPermissionRequester?) {
        scope.launch {
            loaded.await()
            start(trigger, requester)
        }
    }

    fun setInterval(minutes: Int) {
        if (minutes < 1) return
        scope.launch {
            loaded.await()
            save { it.copy(intervalMinutes = minutes) }
        }
    }

    fun setAutoSync(on: Boolean) {
        scope.launch {
            loaded.await()
            save { it.copy(autoOnResume = on) }
        }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(app)

    private fun sdkAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE

    private suspend fun refresh() {
        available = sdkAvailable()
        permissions = if (available) {
            try {
                HealthSyncLogic.permissionState(client().permissionController.getGrantedPermissions(), HealthRecords.READ_PERMISSIONS)
            } catch (e: Exception) {
                Log.w(TAG, "permissions unreadable", e)
                PermissionState.None
            }
        } else {
            PermissionState.None
        }
        publish()
    }

    private fun publish() {
        val s = HealthSyncStatus(
            available = available,
            permissions = permissions,
            lastSyncAt = state.lastSyncAt,
            lastResult = state.lastResult,
            lastError = state.lastError,
            lastCount = state.lastCount,
            minIntervalMinutes = state.intervalMinutes,
            autoOnResume = state.autoOnResume,
        )
        if (s == _status.value) return
        _status.value = s
        listener?.onHealthStatus(s)
    }

    private suspend fun save(change: (HealthSyncState) -> HealthSyncState) {
        state = store.updateHealthSync(change)
        publish()
    }

    private fun emit(event: HealthSyncEvent) {
        listener?.onHealthEvent(event)
    }

    /** Decide (see [HealthSyncLogic.decide]) and start a sync. Main thread, no suspension between check and start. */
    private fun start(trigger: HealthTrigger, requester: HealthPermissionRequester?) {
        val now = System.currentTimeMillis()
        val decision = HealthSyncLogic.decide(
            trigger = trigger,
            now = now,
            running = job?.isActive == true,
            lastAttemptAt = state.lastAttemptAt,
            lastDeliberateAt = lastDeliberateAt,
            intervalMinutes = state.intervalMinutes,
            autoOnResume = state.autoOnResume,
            backoffUntil = state.backoffUntil,
            backoffReason = HealthSyncLogic.backoffReason(state.lastResult, state.lastError),
        )
        when (decision) {
            SyncDecision.Ignore -> return
            is SyncDecision.Skip -> {
                emit(HealthSyncEvent.Skipped(decision.reason))
                return
            }
            SyncDecision.Run -> Unit
        }
        if (trigger.deliberate) {
            lastDeliberateAt = now
        } else if (!available || permissions == PermissionState.None) {
            // Nothing can come of an automatic sync here (no Health Connect,
            // or never set up): stay silent rather than report it on every resume.
            return
        }
        job = scope.launch { run(trigger, requester) }
    }

    private suspend fun run(trigger: HealthTrigger, requester: HealthPermissionRequester?) {
        emit(HealthSyncEvent.Started(trigger))
        save { it.copy(lastAttemptAt = System.currentTimeMillis()) }
        val outcome = try {
            sync(trigger, requester)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health sync failed", e)
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        when (outcome) {
            is SyncOutcome.Done -> {
                save {
                    it.copy(
                        lastSyncAt = System.currentTimeMillis(),
                        lastResult = SyncResult.Ok,
                        lastError = null,
                        lastCount = outcome.count,
                        token = outcome.token,
                        tokenTypes = outcome.types,
                        tokenOrigin = outcome.origin,
                        failures = 0,
                        backoffUntil = 0L,
                    )
                }
                emit(HealthSyncEvent.Done(outcome.count))
            }
            is SyncOutcome.Skipped -> {
                save {
                    val s = it.copy(lastResult = SyncResult.Skipped, lastError = outcome.reason.key, lastCount = null)
                    if (outcome.reason == SkipReason.NoBackendSupport) s.backedOff() else s
                }
                emit(HealthSyncEvent.Skipped(outcome.reason))
            }
            is SyncOutcome.Failed -> {
                save { it.copy(lastResult = SyncResult.Error, lastError = outcome.message, lastCount = null).backedOff() }
                emit(HealthSyncEvent.Error(outcome.message))
            }
        }
    }

    /** One more failure in a row: automatic syncs wait longer (see [HealthSyncLogic.backoffMs]). */
    private fun HealthSyncState.backedOff(): HealthSyncState {
        val n = failures + 1
        return copy(failures = n, backoffUntil = System.currentTimeMillis() + HealthSyncLogic.backoffMs(n, intervalMinutes))
    }

    /** One sync (see [HealthSyncer]) against Health Connect and the configured origin's letterbox. */
    private suspend fun sync(trigger: HealthTrigger, requester: HealthPermissionRequester?): SyncOutcome {
        val settings = store.settingsNow()
        val origin = UrlRouter(settings.base).origin?.toString()
        // Nothing touches HealthConnectClient.getOrCreate (which throws without
        // Health Connect) before the syncer's availability gate: the client is
        // made on first use, after it.
        val syncer = HealthSyncer(
            source = HealthConnectSource(available = ::sdkAvailable, create = ::client),
            letterbox = OkHttpLetterbox(settings.certificateAlias),
            rounds = rounds,
            client = ClientInfo(app.packageName, BuildConfig.VERSION_NAME),
        )
        return syncer.sync(
            trigger = trigger,
            requester = requester,
            origin = origin,
            state = { state },
            update = { change -> save(change) },
            onGates = { a, p ->
                available = a
                permissions = p
                publish()
            },
        )
    }

    /**
     * The letterbox over OkHttp, as the WebView reaches the origin: the
     * selected client certificate ([alias]) and the WebView's cookies, read
     * on the main thread per request (the session may be renewed meanwhile).
     * Network and the KeyChain on the IO dispatcher.
     */
    private class OkHttpLetterbox(private val alias: String?) : Letterbox {

        private var http: OkHttpClient? = null

        private suspend fun client(): OkHttpClient =
            http ?: withContext(Dispatchers.IO) { httpClient(alias) }.also { http = it }

        /** Is there a letterbox? A `PROPFIND` of depth 0 on it, then an `OPTIONS` if that is a 404 (see [HealthSyncLogic.probe]). */
        override suspend fun probe(origin: String): ProbeResult {
            val inbox = HealthSyncLogic.inboxUrl(origin)
            val cookies = cookies(inbox)
            val client = client()
            return withContext(Dispatchers.IO) {
                HealthSyncLogic.probe { method ->
                    send(client, inbox, cookies) {
                        when (method) {
                            "PROPFIND" -> method(
                                method,
                                HealthSyncLogic.PROBE_BODY.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType()),
                            ).header("Depth", "0")
                            else -> method(method, null)
                        }
                    }.use { resp -> resp.code to resp.message }
                }
            }
        }

        /**
         * One part as a file. The body is a byte array of known length, so it
         * goes with a Content-Length (WebDAV servers refuse chunked uploads).
         */
        override suspend fun put(url: String, body: ByteArray): UploadResult {
            val cookies = cookies(url)
            val client = client()
            return withContext(Dispatchers.IO) {
                send(client, url, cookies) { put(body.toRequestBody("application/json".toMediaType())) }
                    .use { resp -> HealthSyncLogic.parseResponse(resp.code, resp.message) }
            }
        }

        /** The WebView's cookies for [url]. Main thread (CookieManager is not for the IO dispatcher). */
        private fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)

        private fun send(client: OkHttpClient, url: String, cookies: String?, configure: Request.Builder.() -> Request.Builder): Response {
            val request = Request.Builder()
                .url(url)
                .configure()
                .header("User-Agent", "Ara/${BuildConfig.VERSION_NAME} (Android)")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .build()
            return client.newCall(request).execute()
        }
    }

    /** The client certificate as the WebView presents it; none when none is selected. No redirects either way. */
    private fun httpClient(alias: String?): OkHttpClient {
        if (alias != null) return AraHttp.build(app, alias, TimeUnit.SECONDS.toMillis(TIMEOUT_S))
        return OkHttpClient.Builder()
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
