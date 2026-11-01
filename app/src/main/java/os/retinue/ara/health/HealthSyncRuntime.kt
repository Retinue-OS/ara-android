package os.retinue.ara.health

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private sealed class Outcome {
        class Done(val count: Int, val token: String, val types: String) : Outcome()
        class Skipped(val reason: SkipReason) : Outcome()
        class Failed(val message: String) : Outcome()
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
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        when (outcome) {
            is Outcome.Done -> {
                save {
                    it.copy(
                        lastSyncAt = System.currentTimeMillis(),
                        lastResult = SyncResult.Ok,
                        lastError = null,
                        lastCount = outcome.count,
                        token = outcome.token,
                        tokenTypes = outcome.types,
                        failures = 0,
                        backoffUntil = 0L,
                    )
                }
                emit(HealthSyncEvent.Done(outcome.count))
            }
            is Outcome.Skipped -> {
                save {
                    val s = it.copy(lastResult = SyncResult.Skipped, lastError = outcome.reason.key, lastCount = null)
                    if (outcome.reason == SkipReason.NoBackendSupport) s.backedOff() else s
                }
                emit(HealthSyncEvent.Skipped(outcome.reason))
            }
            is Outcome.Failed -> {
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

    private suspend fun sync(trigger: HealthTrigger, requester: HealthPermissionRequester?): Outcome {
        available = sdkAvailable()
        if (!available) {
            publish()
            return Outcome.Skipped(SkipReason.Unavailable)
        }
        val settings = store.settingsNow()
        val origin = UrlRouter(settings.base).origin?.toString() ?: return Outcome.Failed("No Retinue base URL is set")
        val client = client()
        val wanted = HealthRecords.READ_PERMISSIONS
        var granted = client.permissionController.getGrantedPermissions()
        permissions = HealthSyncLogic.permissionState(granted, wanted)
        if (requester != null && HealthSyncLogic.shouldRequestPermissions(trigger.deliberate, permissions, state.permissionsAsked)) {
            save { it.copy(permissionsAsked = true) }
            requester.request(wanted + backgroundPermission(client))
            granted = client.permissionController.getGrantedPermissions()
            permissions = HealthSyncLogic.permissionState(granted, wanted)
        }
        publish()
        val types = HealthRecords.grantedTypes(granted)
        if (types.isEmpty()) return Outcome.Skipped(SkipReason.NoPermission)

        // Is there a letterbox at all? Asked before Health Connect is read, so
        // a server without one costs one small request, not a 30-day read.
        val http = withContext(Dispatchers.IO) { httpClient(settings.certificateAlias) }
        val inbox = HealthSyncLogic.inboxUrl(origin)
        when (val probe = withContext(Dispatchers.IO) { probe(http, inbox, cookies(inbox)) }) {
            UploadResult.Stored -> Unit
            UploadResult.NoBackend -> return Outcome.Skipped(SkipReason.NoBackendSupport)
            is UploadResult.Failed -> return Outcome.Failed("Letterbox: ${probe.message}")
        }

        // A round an earlier sync left half-uploaded goes first, as it was,
        // under its own batch id; its token is then the one to continue from.
        var count = 0
        withContext(Dispatchers.IO) { rounds.pending() }?.let { pending ->
            upload(http, origin, pending)?.let { return it }
            count += pending.count
            save { it.copy(token = pending.nextToken, tokenTypes = pending.typeKey) }
        }

        val typeKey = HealthSyncLogic.typeKey(types)
        val storedToken = state.token?.takeIf { state.tokenTypes == typeKey }
        val collected = withContext(Dispatchers.IO) { collect(client, types, storedToken) }
        // Nothing new: the token still moves on.
        if (collected.changes.isEmpty()) return Outcome.Done(count, collected.token, typeKey)
        val round = withContext(Dispatchers.IO) { begin(collected, trigger, types, typeKey) }
        upload(http, origin, round)?.let { return it }
        return Outcome.Done(count + round.count, round.nextToken, typeKey)
    }

    /**
     * Write the round [collected] makes to the device, one part at a time
     * (a part's file is built and written, then forgotten, before the next),
     * ready to upload. IO thread.
     */
    private fun begin(collected: Collected, trigger: HealthTrigger, types: List<HealthType>, typeKey: String): HealthRound {
        val parts = HealthSyncLogic.partCount(collected.changes.size)
        val sentAt = Instant.now()
        val round = HealthRound(
            batchId = UUID.randomUUID().toString(),
            sentAt = sentAt.toEpochMilli(),
            kind = collected.kind.key,
            typeKey = typeKey,
            parts = parts,
            partsSent = 0,
            count = collected.changes.size,
            nextToken = collected.token,
        )
        return rounds.begin(round) { part ->
            HealthSyncLogic.envelope(
                batchId = round.batchId,
                kind = collected.kind,
                trigger = trigger,
                requestedTypes = types.map { it.name },
                windowStart = collected.windowStart,
                part = part,
                parts = parts,
                app = app.packageName,
                version = BuildConfig.VERSION_NAME,
                androidSdk = Build.VERSION.SDK_INT,
                sentAt = sentAt,
                changes = HealthSyncLogic.part(collected.changes, part),
            ).toString().toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * PUT the unsent parts of [round], first to last, each marked as sent once
     * the server has it. Null when all are stored (the round is gone from the
     * device); otherwise the outcome that ends the sync, with the round kept
     * for the next one.
     */
    private suspend fun upload(http: OkHttpClient, origin: String, round: HealthRound): Outcome? {
        var current = round
        while (!current.complete) {
            val part = current.partsSent + 1
            val url = HealthSyncLogic.uploadUrl(origin, current.fileName(part))
            // Read per part, on the main thread: the session may be renewed meanwhile.
            val cookies = cookies(url)
            val result = withContext(Dispatchers.IO) { put(http, url, rounds.partFile(part).readBytes(), cookies) }
            when (result) {
                UploadResult.Stored -> current = withContext(Dispatchers.IO) { rounds.sent(current, part) }
                UploadResult.NoBackend -> return Outcome.Skipped(SkipReason.NoBackendSupport)
                is UploadResult.Failed -> return Outcome.Failed("Part $part of ${current.parts}: ${result.message}")
            }
        }
        withContext(Dispatchers.IO) { rounds.clear() }
        return null
    }

    /** The WebView's cookies for [url]. Main thread (CookieManager is not for the IO dispatcher). */
    private fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    /** Asked for with the read permissions where Health Connect supports reading in the background. */
    private fun backgroundPermission(client: HealthConnectClient): Set<String> {
        val supported = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            false
        }
        return if (supported) setOf(HealthRecords.BACKGROUND_PERMISSION) else emptySet()
    }

    private class Collected(val kind: SyncKind, val changes: List<JsonObject>, val token: String, val windowStart: Instant?)

    /**
     * What to send: the changes since [storedToken], or the whole window
     * without a usable one. A token Health Connect no longer knows (it was
     * reset or reinstalled; an invalid-argument error) counts as expired:
     * the window is read again, rather than every sync failing on it.
     */
    private suspend fun collect(client: HealthConnectClient, types: List<HealthType>, storedToken: String?): Collected {
        if (storedToken == null) return readWindow(client, types, SyncKind.Initial)
        val changes = mutableListOf<JsonObject>()
        var token: String = storedToken
        while (true) {
            val response = try {
                client.getChanges(token)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Health Connect rejected the changes token; reading the window again", e)
                return readWindow(client, types, SyncKind.Resync)
            }
            if (response.changesTokenExpired) return readWindow(client, types, SyncKind.Resync)
            response.changes.mapNotNullTo(changes) { HealthRecords.change(it) }
            token = response.nextChangesToken
            if (!response.hasMore) break
        }
        return Collected(SyncKind.Changes, changes, token, null)
    }

    /**
     * Every record of [types] in the window, as upserts, and a fresh token.
     * The token is taken first: what changes while the window is read comes
     * again with the next sync, rather than falling between the two.
     */
    private suspend fun readWindow(client: HealthConnectClient, types: List<HealthType>, kind: SyncKind): Collected {
        val token = client.getChangesToken(ChangesTokenRequest(types.map { it.recordClass }.toSet()))
        val windowStart = Instant.now().minus(Duration.ofDays(HealthSyncLogic.WINDOW_DAYS))
        val changes = mutableListOf<JsonObject>()
        for (type in types) {
            @Suppress("UNCHECKED_CAST")
            val recordClass = type.recordClass as KClass<Record>
            var pageToken: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(recordClass, TimeRangeFilter.after(windowStart), emptySet(), true, PAGE_SIZE, pageToken),
                )
                response.records.mapNotNullTo(changes) { HealthRecords.upsert(it) }
                pageToken = response.pageToken
            } while (pageToken != null)
        }
        return Collected(kind, changes, token, windowStart)
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

    /**
     * One part as a file. The body is a byte array of known length, so it
     * goes with a Content-Length (WebDAV servers refuse chunked uploads).
     */
    private fun put(http: OkHttpClient, url: String, body: ByteArray, cookies: String?): UploadResult =
        send(http, url, cookies) { put(body.toRequestBody("application/json".toMediaType())) }

    /** Is there a letterbox? A `PROPFIND` of depth 0 on it (see [HealthSyncLogic.PROBE_BODY]). */
    private fun probe(http: OkHttpClient, inbox: String, cookies: String?): UploadResult =
        send(http, inbox, cookies) {
            method("PROPFIND", HealthSyncLogic.PROBE_BODY.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
        }

    private fun send(http: OkHttpClient, url: String, cookies: String?, configure: Request.Builder.() -> Request.Builder): UploadResult {
        val request = Request.Builder()
            .url(url)
            .configure()
            .header("User-Agent", "Ara/${BuildConfig.VERSION_NAME} (Android)")
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
            .build()
        return http.newCall(request).execute().use { resp ->
            HealthSyncLogic.parseResponse(resp.code, resp.message)
        }
    }
}
