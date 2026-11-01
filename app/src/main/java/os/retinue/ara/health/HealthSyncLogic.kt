package os.retinue.ara.health

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What started a sync (the envelope's `sync.trigger`). */
enum class HealthTrigger(val key: String) {
    /** Ara came to the front (automatic, at most every [HealthSyncLogic.DEFAULT_INTERVAL_MINUTES]). */
    Resume("resume"),

    /** The web app asked (`RetinueShell.healthSync()`), e.g. Settings → Phone shell. */
    Manual("manual"),

    /** As [Manual], from the `/sync-health` page a thread or an intent link opened. */
    Link("link"),
    ;

    /** A sync the user asked for: it always runs, never waits for the interval. */
    val deliberate: Boolean get() = this != Resume
}

/** The envelope's `sync.kind`. */
enum class SyncKind(val key: String) {
    /** What changed since the stored Changes token. */
    Changes("changes"),

    /** No token yet (or the granted types changed): the last [HealthSyncLogic.WINDOW_DAYS] days. */
    Initial("initial"),

    /** The token expired (Health Connect keeps changes about 30 days): read the window again. */
    Resync("resync"),
}

/** The `permissions` field of the status: of the synced types' read permissions. */
enum class PermissionState(val key: String) { Granted("granted"), Partial("partial"), None("none") }

/** Why a sync did not run (the `reason` of a `skipped` event). */
enum class SkipReason(val key: String) {
    Interval("interval"),
    NoPermission("no-permission"),
    Unavailable("unavailable"),
    NoBackendSupport("no-backend-support"),
}

/** The last sync's outcome, kept across restarts. */
enum class SyncResult(val key: String) {
    Ok("ok"), Error("error"), Skipped("skipped");

    companion object {
        fun fromKey(key: String?): SyncResult? = entries.firstOrNull { it.key == key }
    }
}

/** What the shell tells the page about health sync (`RetinueShell.healthSyncStatus()`). */
data class HealthSyncStatus(
    val available: Boolean = false,
    val permissions: PermissionState = PermissionState.None,
    /** When the last sync completed successfully (epoch ms; 0 for never). */
    val lastSyncAt: Long = 0L,
    val lastResult: SyncResult? = null,
    val lastError: String? = null,
    val lastCount: Int? = null,
    val minIntervalMinutes: Int = HealthSyncLogic.DEFAULT_INTERVAL_MINUTES,
    val autoOnResume: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("available", available)
        put("permissions", permissions.key)
        put("last_sync_at", if (lastSyncAt > 0) JsonPrimitive(Instant.ofEpochMilli(lastSyncAt).toString()) else JsonNull)
        put("last_result", lastResult?.key?.let { JsonPrimitive(it) } ?: JsonNull)
        put("last_error", lastError?.let { JsonPrimitive(it) } ?: JsonNull)
        put("last_count", lastCount?.let { JsonPrimitive(it) } ?: JsonNull)
        put("min_interval_minutes", minIntervalMinutes)
        put("auto_on_resume", autoOnResume)
    }
}

/** A sync event for the page: `retinue-shell` with `{type: 'health-sync', state, …}`. */
sealed class HealthSyncEvent(val state: String) {
    data class Started(val trigger: HealthTrigger) : HealthSyncEvent("started")
    data class Done(val count: Int) : HealthSyncEvent("done")
    data class Error(val error: String) : HealthSyncEvent("error")
    data class Skipped(val reason: SkipReason) : HealthSyncEvent("skipped")

    /** The event's `detail` fields besides `type`. */
    fun detail(): JsonObject = buildJsonObject {
        put("state", state)
        when (this@HealthSyncEvent) {
            is Started -> put("trigger", trigger.key)
            is Done -> put("count", count)
            is Error -> put("error", error)
            is Skipped -> put("reason", reason.key)
        }
    }
}

/** The answer to the `PUT` of one part into the letterbox (`<origin>/inbox/`). */
sealed class UploadResult {
    data object Stored : UploadResult()

    /** 404/405: the backend has no letterbox share. */
    data object NoBackend : UploadResult()
    data class Failed(val message: String) : UploadResult()
}

/** What the probe of the letterbox found (see [HealthSyncLogic.parseProbe]). */
sealed class ProbeResult {
    /** A WebDAV share answered. */
    data object Present : ProbeResult()

    /** 404: nothing at `/inbox/`. */
    data object Absent : ProbeResult()

    /** The server does not speak PROPFIND (405, 501, …); a plain PUT may still work, so the sync goes on. */
    data object Unknown : ProbeResult()

    /** Not signed in (401, 403), redirected, or a server error: nothing can be sent. */
    data class Failed(val message: String) : ProbeResult()
}

/** What the syncer does with the round an earlier sync left on the device (see [HealthSyncLogic.pendingAction]). */
enum class PendingAction { None, Resume, Discard }

/** What one read of Health Connect produced: the changes to send and the token to store once they are. */
data class Collected(val kind: SyncKind, val changes: List<JsonObject>, val token: String, val windowStart: Instant?)

/**
 * One round of parts on their way to the letterbox, kept on the device until
 * every part is stored (see HealthRoundStore): a sync that fails halfway
 * resumes it under the same `batch_id` and `sent_at`, from the first unsent
 * part, so the server sees one batch and no orphans. [nextToken] is stored as
 * the Changes token once the round is complete.
 */
@Serializable
data class HealthRound(
    val batchId: String,
    /** The origin the parts were read for and go to (`https://host[:port]`); for another one they are discarded. */
    val origin: String,
    /** The envelope's `sent_at`, epoch ms. */
    val sentAt: Long,
    val kind: String,
    val typeKey: String,
    val parts: Int,
    /** Parts stored so far: the next one to send is `partsSent + 1`. */
    val partsSent: Int,
    /** Changes in the round, for the `done` count. */
    val count: Int,
    val nextToken: String,
) {
    val complete: Boolean get() = partsSent >= parts

    fun fileName(part: Int): String = HealthSyncLogic.fileName(Instant.ofEpochMilli(sentAt), batchId, part, parts)

    fun encode(): String = HealthSyncLogic.json.encodeToString(serializer(), this)

    companion object {
        /** Null for anything that is not a round (an older build's file, a torn write). */
        fun decode(raw: String?): HealthRound? {
            if (raw.isNullOrBlank()) return null
            return try {
                HealthSyncLogic.json.decodeFromString(serializer(), raw).takeIf { it.parts > 0 && it.partsSent in 0..it.parts }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Whether a sync runs now (see [HealthSyncLogic.decide]). */
sealed class SyncDecision {
    data object Run : SyncDecision()

    /** Not now, and the page is told why. */
    data class Skip(val reason: SkipReason) : SyncDecision()

    /** Nothing at all: auto-sync off, or a sync running or just started covers it. */
    data object Ignore : SyncDecision()
}

/**
 * The framework-free half of Health Connect sync (contract §§2–3): when a
 * sync runs, what it sends, where it goes and how the answer is read.
 * Unit-tested.
 */
object HealthSyncLogic {

    const val SCHEMA = "retinue-health-connect/1"

    /** The framework's letterbox, shared over WebDAV with the dashboard's authentication. */
    const val INBOX_PATH = "/inbox/"

    /** What the health chamber's type declaration matches a delivered file on (with [SCHEMA]). */
    const val FILE_SUFFIX = ".health-connect.json"
    const val DEFAULT_INTERVAL_MINUTES = 5

    /** A deliberate sync asked for again within this long is the same request. */
    const val COLLAPSE_MS = 5_000L

    /** An initial or resync read goes this far back (as far as Changes tokens live). */
    const val WINDOW_DAYS = 30L

    /** At most this many changes go in one file. */
    const val MAX_PART = 5_000

    /** Automatic syncs back off no further than this after repeated failures. */
    const val MAX_BACKOFF_MINUTES = 6 * 60

    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Run a sync for [trigger] now? Automatic ones ([HealthTrigger.Resume])
     * only with auto-sync on, at most every [intervalMinutes] since the
     * last attempt ([lastAttemptAt], epoch ms; a clock set back runs it),
     * and not while they back off after a failure ([backoffUntil], epoch ms,
     * 0 for none; see [backoffMs]): until then they are skipped for
     * [backoffReason]. Deliberate ones always run, unless one is running
     * ([running]) or one was asked for less than [COLLAPSE_MS] ago
     * ([lastDeliberateAt]).
     */
    fun decide(
        trigger: HealthTrigger,
        now: Long,
        running: Boolean,
        lastAttemptAt: Long,
        lastDeliberateAt: Long,
        intervalMinutes: Int,
        autoOnResume: Boolean,
        backoffUntil: Long = 0L,
        backoffReason: SkipReason = SkipReason.Interval,
    ): SyncDecision {
        if (running) return SyncDecision.Ignore
        if (trigger.deliberate) {
            val since = now - lastDeliberateAt
            return if (lastDeliberateAt > 0 && since in 0 until COLLAPSE_MS) SyncDecision.Ignore else SyncDecision.Run
        }
        if (!autoOnResume) return SyncDecision.Ignore
        // A clock set forward and back again cannot hold the sync off for longer than the longest back-off.
        val backoffLeft = backoffUntil - now
        if (backoffLeft > 0 && backoffLeft <= MAX_BACKOFF_MINUTES * 60_000L) return SyncDecision.Skip(backoffReason)
        val since = now - lastAttemptAt
        val interval = intervalMinutes.coerceAtLeast(1) * 60_000L
        return if (lastAttemptAt > 0 && since in 0 until interval) SyncDecision.Skip(SkipReason.Interval) else SyncDecision.Run
    }

    /**
     * How long automatic syncs wait after the [failures]th failure in a row
     * (a letterbox that is missing, or an upload that failed): the interval,
     * doubled with each failure, capped at [MAX_BACKOFF_MINUTES]. A success
     * resets the count. Deliberate syncs never wait.
     */
    fun backoffMs(failures: Int, intervalMinutes: Int): Long {
        if (failures <= 0) return 0L
        val base = intervalMinutes.coerceAtLeast(1).toLong()
        val doublings = (failures - 1).coerceAtMost(20)
        val minutes = (base shl doublings).coerceAtMost(MAX_BACKOFF_MINUTES.toLong())
        return minutes * 60_000L
    }

    /** The skip reason an automatic sync reports while it backs off: why the last sync did not store anything. */
    fun backoffReason(lastResult: SyncResult?, lastError: String?): SkipReason =
        if (lastResult == SyncResult.Skipped && lastError == SkipReason.NoBackendSupport.key) SkipReason.NoBackendSupport else SkipReason.Interval

    /** How many parts [changes] changes make. */
    fun partCount(changes: Int, max: Int = MAX_PART): Int = if (changes <= 0) 0 else (changes + max - 1) / max.coerceAtLeast(1)

    /** The changes of part [part] (1-based) of [changes], as a view. */
    fun <T> part(changes: List<T>, part: Int, max: Int = MAX_PART): List<T> {
        val size = max.coerceAtLeast(1)
        val from = (part - 1) * size
        return changes.subList(from.coerceIn(0, changes.size), (from + size).coerceIn(0, changes.size))
    }

    /** How many of the [wanted] read permissions are among [granted]. */
    fun permissionState(granted: Set<String>, wanted: Set<String>): PermissionState {
        val n = wanted.count { it in granted }
        return when {
            wanted.isNotEmpty() && n == wanted.size -> PermissionState.Granted
            n > 0 -> PermissionState.Partial
            else -> PermissionState.None
        }
    }

    /**
     * Ask Health Connect for permissions before this sync? Only a deliberate
     * one asks: when nothing is granted yet (Health Connect itself stops
     * showing the dialog after the user declined twice), or when only some
     * types are and the shell never asked before ([askedBefore]); after that,
     * whatever subset the user granted is synced.
     */
    fun shouldRequestPermissions(deliberate: Boolean, state: PermissionState, askedBefore: Boolean): Boolean =
        deliberate && (state == PermissionState.None || (state == PermissionState.Partial && !askedBefore))

    /** The key a stored Changes token belongs to: the sorted names of the types it was made for. */
    fun typeKey(types: Collection<HealthType>): String = types.map { it.name }.sorted().joinToString(",")

    /** The body of one POST (contract §3). */
    fun envelope(
        batchId: String,
        kind: SyncKind,
        trigger: HealthTrigger,
        requestedTypes: List<String>,
        windowStart: Instant?,
        part: Int,
        parts: Int,
        app: String,
        version: String,
        androidSdk: Int,
        sentAt: Instant,
        changes: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("batch_id", batchId)
        put(
            "sync",
            buildJsonObject {
                put("kind", kind.key)
                put("trigger", trigger.key)
                put("requested_types", buildJsonArray { requestedTypes.forEach { add(JsonPrimitive(it)) } })
                if (kind != SyncKind.Changes && windowStart != null) put("window_start", windowStart.toString())
                put("part", part)
                put("parts", parts)
            },
        )
        put(
            "client",
            buildJsonObject {
                put("app", app)
                put("version", version)
                put("android_sdk", androidSdk)
            },
        )
        put("sent_at", sentAt.toString())
        put("changes", buildJsonArray { changes.forEach { add(it) } })
    }

    private val compactUtc: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * The letterbox file one part is written to:
     * `hc-<sent_at as 20261101T171500Z>-<batch_id>-p<part>of<parts>.health-connect.json`.
     */
    fun fileName(sentAt: Instant, batchId: String, part: Int, parts: Int): String =
        "hc-${compactUtc.format(sentAt)}-$batchId-p${part}of$parts$FILE_SUFFIX"

    /** The letterbox of [origin] (`https://host[:port]`): what the probe asks about. */
    fun inboxUrl(origin: String): String = origin.trimEnd('/') + INBOX_PATH

    /** Where one part is PUT: the letterbox of [origin]. */
    fun uploadUrl(origin: String, fileName: String): String = inboxUrl(origin) + fileName

    /**
     * The body of the probe: a `PROPFIND` with depth 0 for one property, which
     * a WebDAV share answers with 207 and a server without the share with 404.
     * Cheaper than a sync and asked before Health Connect is read.
     */
    const val PROBE_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>"

    /**
     * Read the answer to the probe (a `PROPFIND`). Only a 404 means there is
     * no letterbox: a server that takes a plain `PUT` into `/inbox/` need not
     * speak WebDAV at all (405, 501, or any other refusal of the method), so
     * those are unknown and the sync goes on to the `PUT`. Not being signed
     * in (401, 403), a redirect or a server error fails the sync.
     */
    fun parseProbe(code: Int, message: String? = null): ProbeResult = when {
        code in 200..299 -> ProbeResult.Present
        code == 404 -> ProbeResult.Absent
        code == 401 || code == 403 -> ProbeResult.Failed(status(code, message))
        code in 300..399 -> ProbeResult.Failed("HTTP $code (redirected; signed out?)")
        code == 501 || code in 400..499 -> ProbeResult.Unknown
        else -> ProbeResult.Failed(status(code, message))
    }

    /**
     * The round an earlier sync left: resumed when it is for the configured
     * [origin], discarded when it is for another one (what was read for one
     * server is not the next one's) or none is configured.
     */
    fun pendingAction(round: HealthRound?, origin: String?): PendingAction = when {
        round == null -> PendingAction.None
        origin != null && round.origin == origin -> PendingAction.Resume
        else -> PendingAction.Discard
    }

    private fun status(code: Int, message: String?) = if (!message.isNullOrBlank()) "HTTP $code $message" else "HTTP $code"

    /**
     * Read the answer to one part's `PUT`: any 2xx (201 created, 204 replaced)
     * stored it; 404 or 405 means there is no letterbox share; anything else
     * failed, with a short reason from [message] (the status line).
     */
    fun parseResponse(code: Int, message: String? = null): UploadResult = when {
        code in 200..299 -> UploadResult.Stored
        code == 404 || code == 405 -> UploadResult.NoBackend
        code in 300..399 -> UploadResult.Failed("HTTP $code (redirected; signed out?)")
        else -> UploadResult.Failed(status(code, message))
    }

    /** The page whose `healthSync()` counts as [HealthTrigger.Link]: `<base>/sync-health`. */
    fun triggerFor(pageUrl: String?, base: String?): HealthTrigger {
        if (pageUrl == null || base == null) return HealthTrigger.Manual
        val path = pageUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        return if (path == "$base/sync-health") HealthTrigger.Link else HealthTrigger.Manual
    }
}

/** What the shell keeps about health sync on the device (see AraStore). State, apart from the two settings. */
data class HealthSyncState(
    /** Automatic syncs are at least this far apart (≥ 1). */
    val intervalMinutes: Int = HealthSyncLogic.DEFAULT_INTERVAL_MINUTES,
    /** Sync when Ara comes to the front. */
    val autoOnResume: Boolean = true,
    /** The last successful sync (epoch ms; 0 for never). */
    val lastSyncAt: Long = 0L,
    /** The last sync that ran, whatever came of it (epoch ms; 0 for never): the interval counts from here. */
    val lastAttemptAt: Long = 0L,
    val lastResult: SyncResult? = null,
    val lastError: String? = null,
    val lastCount: Int? = null,
    /** The Changes token, advanced only once every part of a sync was stored. */
    val token: String? = null,
    /** The types [token] was made for ([HealthSyncLogic.typeKey]); another set starts afresh. */
    val tokenTypes: String? = null,
    /** The origin [token]'s changes were delivered to; another server starts afresh with the whole window. */
    val tokenOrigin: String? = null,
    /** The shell has asked Health Connect for permissions before. */
    val permissionsAsked: Boolean = false,
    /** Syncs in a row that stored nothing for want of a letterbox or an upload error; 0 after a success. */
    val failures: Int = 0,
    /** No automatic sync before this (epoch ms; 0 for none), after such a failure (see [HealthSyncLogic.backoffMs]). */
    val backoffUntil: Long = 0L,
)
