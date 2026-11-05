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
import kotlinx.coroutines.cancelAndJoin
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
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.credentials
import os.retinue.ara.net.AraHttp
import os.retinue.ara.net.BasicCredentials
import os.retinue.ara.shell.Origin

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
 * changed since the current endpoint's server's own Changes token (each server
 * has one, [HealthSyncState.tokens]; without a usable one, the last
 * [HealthSyncLogic.WINDOW_DAYS] days) and writes it, in parts of at most
 * [HealthSyncLogic.MAX_PART] changes, as files into the current endpoint's
 * letterbox (`<origin>/inbox/`, a WebDAV share: one plain `PUT` per part),
 * authenticated as the WebView is (the endpoint's client certificate and
 * stored password, and the WebView's cookies for that origin). Only an
 * endpoint whose health upload is switched on gets any of this: for another
 * one nothing is read and nothing is sent, and a sync asked for is reported
 * as skipped (`disabled`). Before Health Connect is read, the letterbox is
 * probed: a server without one costs one small request, and automatic syncs
 * then back off (doubling from the interval up to six hours; so after any
 * upload error) until a sync succeeds. A round's parts are written to the
 * device first and uploaded one by one ([HealthRounds], one round per
 * server); the token advances only once every part was stored, and a round
 * that failed halfway — or was left when another endpoint was shown — is
 * resumed by the next sync to its server from the first unsent part, under
 * the same batch id, before anything new is read.
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

    /** The current endpoint's origin (`https://host[:port]`), and whether it takes health data at all. */
    private var endpointOrigin: String? = null
    private var uploadEnabled = false

    /** The one sync running. */
    private var job: Job? = null

    /** When a deliberate sync was last asked for (epoch ms), for collapsing repeats. */
    private var lastDeliberateAt = 0L

    /** The rounds on their way to the letterboxes, one per server (see [HealthRounds]). */
    private lateinit var rounds: HealthRounds

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
        rounds = HealthRounds(File(app.filesDir, ROUND_DIR))
        scope.launch {
            state = store.healthSync.first()
            loaded.complete(Unit)
            refresh()
        }
        // The endpoints as health sync sees them. One sync runs at a time, for
        // the current endpoint: one still running when another is shown, or
        // when this one's switch is turned off, is stopped (its round, if any,
        // stays on the device for its server; its token has not moved). Each
        // server's round waits for it while another is shown, and goes only
        // when its endpoint is removed or its switch turned off; each server's
        // token is forgotten only when no endpoint points at it any more.
        scope.launch {
            // An older build's one round moves into its server's folder before anything syncs.
            withContext(Dispatchers.IO) { rounds.migrateLegacy() }
            store.settings
                .map { s -> HealthTargets.of(s.current, s.endpoints) }
                .distinctUntilChanged()
                .collect { targets ->
                    val stale = targets.current != endpointOrigin || !targets.enabled
                    endpointOrigin = targets.current
                    uploadEnabled = targets.enabled
                    val running = job?.takeIf { it.isActive }
                    if (stale && running != null) {
                        running.cancelAndJoin()
                        if (!targets.enabled) emit(HealthSyncEvent.Skipped(SkipReason.Disabled))
                    }
                    publish()
                    withContext(Dispatchers.IO) { rounds.retainOnly(targets.enabledOrigins) }
                    loaded.await()
                    if (state.tokens.keys.any { it !in targets.configuredOrigins }) {
                        save { it.retainingTokens(targets.configuredOrigins) }
                    }
                }
        }
    }

    /**
     * What health sync needs to know of the endpoints: the current one's origin
     * and switch, the origins whose switch is on (their rounds are kept), and
     * every configured origin (their tokens are kept).
     */
    private data class HealthTargets(
        val current: String?,
        val enabled: Boolean,
        val enabledOrigins: Set<String>,
        val configuredOrigins: Set<String>,
    ) {
        companion object {
            fun of(current: Endpoint?, endpoints: List<Endpoint>) = HealthTargets(
                current = current?.origin?.toString(),
                enabled = current?.healthUpload == true,
                enabledOrigins = endpoints.filter { it.healthUpload }.mapNotNull { it.origin?.toString() }.toSet(),
                configuredOrigins = endpoints.mapNotNull { it.origin?.toString() }.toSet(),
            )
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
            enabled = uploadEnabled,
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
        // The endpoint's own switch comes first: off, nothing is read for it.
        if (!uploadEnabled) {
            if (trigger.deliberate) emit(HealthSyncEvent.Skipped(SkipReason.Disabled))
            return
        }
        val now = System.currentTimeMillis()
        // A back-off is the failing server's, not the next one's.
        val backoffUntil = if (state.backoffOrigin == endpointOrigin) state.backoffUntil else 0L
        val decision = HealthSyncLogic.decide(
            trigger = trigger,
            now = now,
            running = job?.isActive == true,
            lastAttemptAt = state.lastAttemptAt,
            lastDeliberateAt = lastDeliberateAt,
            intervalMinutes = state.intervalMinutes,
            autoOnResume = state.autoOnResume,
            backoffUntil = backoffUntil,
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
        // The endpoint this sync is for, read once: [start] checked the switch,
        // but the settings may have changed while the attempt was saved. Its own
        // switch decides here, and the outcome is booked against its origin.
        val endpoint = store.settingsNow().current
        val origin = endpoint?.origin?.toString()
        val outcome = try {
            if (endpoint == null || !endpoint.healthUpload) SyncOutcome.Skipped(SkipReason.Disabled) else sync(endpoint, trigger, requester)
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
                    ).withToken(outcome.origin, outcome.token, outcome.types).let { s ->
                        // This server's back-off ends; another server's stays its own.
                        if (s.backoffOrigin == null || s.backoffOrigin == outcome.origin) {
                            s.copy(failures = 0, backoffUntil = 0L, backoffOrigin = null)
                        } else {
                            s
                        }
                    }
                }
                emit(HealthSyncEvent.Done(outcome.count))
            }
            is SyncOutcome.Skipped -> {
                save {
                    val s = it.copy(lastResult = SyncResult.Skipped, lastError = outcome.reason.key, lastCount = null)
                    if (outcome.reason == SkipReason.NoBackendSupport) s.backedOff(origin) else s
                }
                emit(HealthSyncEvent.Skipped(outcome.reason))
            }
            is SyncOutcome.Failed -> {
                save { it.copy(lastResult = SyncResult.Error, lastError = outcome.message, lastCount = null).backedOff(origin) }
                emit(HealthSyncEvent.Error(outcome.message))
            }
        }
    }

    /** One more failure in a row for [origin]: automatic syncs to it wait longer (see [HealthSyncLogic.backoffMs]). */
    private fun HealthSyncState.backedOff(origin: String?): HealthSyncState {
        val n = if (backoffOrigin == origin) failures + 1 else 1
        return copy(
            failures = n,
            backoffUntil = System.currentTimeMillis() + HealthSyncLogic.backoffMs(n, intervalMinutes),
            backoffOrigin = origin,
        )
    }

    /** One sync (see [HealthSyncer]) against Health Connect and the current endpoint's letterbox. */
    private suspend fun sync(endpoint: Endpoint, trigger: HealthTrigger, requester: HealthPermissionRequester?): SyncOutcome {
        val origin = endpoint.origin?.toString()
        // The stored password is opened off the main thread (the Keystore).
        val credentials = withContext(Dispatchers.IO) { endpoint.credentials() }
        // Nothing touches HealthConnectClient.getOrCreate (which throws without
        // Health Connect) before the syncer's availability gate: the client is
        // made on first use, after it.
        val syncer = HealthSyncer(
            source = HealthConnectSource(available = ::sdkAvailable, create = ::client),
            letterbox = OkHttpLetterbox(endpoint.certificateAlias, credentials, endpoint.origin),
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
     * endpoint's client certificate ([alias]) and stored password
     * ([credentials], answered to its host only), and the WebView's cookies,
     * read on the main thread per request (the session may be renewed
     * meanwhile). Network and the KeyChain on the IO dispatcher.
     */
    private class OkHttpLetterbox(
        private val alias: String?,
        private val credentials: BasicCredentials?,
        private val origin: Origin?,
    ) : Letterbox {

        private var http: OkHttpClient? = null

        private suspend fun client(): OkHttpClient =
            http ?: withContext(Dispatchers.IO) { httpClient(alias, credentials, origin) }.also { http = it }

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

    /** The client certificate and password as the WebView presents them; neither when none is set. No redirects. */
    private fun httpClient(alias: String?, credentials: BasicCredentials?, origin: Origin?): OkHttpClient =
        AraHttp.build(app, alias, TimeUnit.SECONDS.toMillis(TIMEOUT_S), credentials, origin)
}
