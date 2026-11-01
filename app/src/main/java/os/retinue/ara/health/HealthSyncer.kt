package os.retinue.ara.health

import android.os.Build
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the syncer reads from Health Connect (see HealthSyncRuntime for the real one). */
interface HealthSource {
    fun available(): Boolean
    suspend fun grantedPermissions(): Set<String>

    /** The background-read permission, where Health Connect offers it; asked for with the read permissions. */
    suspend fun backgroundPermission(): Set<String>

    /** The changes since [storedToken], or the whole window without a usable one (see [Collected]). */
    suspend fun collect(types: List<HealthType>, storedToken: String?): Collected
}

/** The letterbox of the configured origin, reached as the WebView is. */
interface Letterbox {
    suspend fun probe(origin: String): ProbeResult
    suspend fun put(url: String, body: ByteArray): UploadResult
}

/** The `client` of the envelope. */
data class ClientInfo(val app: String, val version: String, val androidSdk: Int = Build.VERSION.SDK_INT)

/** How one sync ended. */
sealed class SyncOutcome {
    data class Done(val count: Int, val token: String, val types: String, val origin: String) : SyncOutcome()
    data class Skipped(val reason: SkipReason) : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/**
 * One sync, from the stored state to its outcome (contract §3), with Health
 * Connect and the letterbox behind [HealthSource] and [Letterbox] so the
 * order of things is unit-tested:
 *
 * 1. The round an earlier sync left half-uploaded ([HealthRoundStore]) goes
 *    first, before any Health Connect gate: its parts hold data already
 *    read, and a permission revoked or Health Connect gone meanwhile must
 *    not leave them on the device for good. A round for another origin is
 *    discarded (what was read for one server is not the next one's).
 * 2. Health Connect must be there and some type granted (a deliberate sync
 *    asks first, see [HealthSyncLogic.shouldRequestPermissions]).
 * 3. The letterbox is probed (once per sync, before anything is read).
 * 4. The changes are read, written to the device as a round and uploaded.
 *
 * The token advances only once every part of a round was stored; it is
 * kept per set of granted types and per origin.
 */
class HealthSyncer(
    private val source: HealthSource,
    private val letterbox: Letterbox,
    private val rounds: HealthRoundStore,
    private val client: ClientInfo,
) {

    /**
     * Run one sync for [origin] (null: no base URL configured). [state] is
     * read whenever needed and changed through [update], which persists;
     * [onGates] reports what Health Connect offers and grants.
     */
    suspend fun sync(
        trigger: HealthTrigger,
        requester: HealthPermissionRequester?,
        origin: String?,
        state: () -> HealthSyncState,
        update: suspend ((HealthSyncState) -> HealthSyncState) -> Unit,
        onGates: (available: Boolean, permissions: PermissionState) -> Unit,
    ): SyncOutcome {
        var count = 0
        var probed = false
        val pending = withContext(Dispatchers.IO) {
            rounds.discardUnless(origin)
            rounds.pending()
        }
        if (origin == null) return SyncOutcome.Failed("No Retinue base URL is set")
        if (pending != null) {
            probe(origin)?.let { return it }
            probed = true
            upload(origin, pending)?.let { return it }
            count += pending.count
            update { it.copy(token = pending.nextToken, tokenTypes = pending.typeKey, tokenOrigin = origin) }
        }

        val available = source.available()
        if (!available) {
            onGates(false, PermissionState.None)
            return SyncOutcome.Skipped(SkipReason.Unavailable)
        }
        val wanted = HealthRecords.READ_PERMISSIONS
        var granted = source.grantedPermissions()
        var permissions = HealthSyncLogic.permissionState(granted, wanted)
        if (requester != null && HealthSyncLogic.shouldRequestPermissions(trigger.deliberate, permissions, state().permissionsAsked)) {
            update { it.copy(permissionsAsked = true) }
            requester.request(wanted + source.backgroundPermission())
            granted = source.grantedPermissions()
            permissions = HealthSyncLogic.permissionState(granted, wanted)
        }
        onGates(true, permissions)
        val types = HealthRecords.grantedTypes(granted)
        if (types.isEmpty()) return SyncOutcome.Skipped(SkipReason.NoPermission)

        if (!probed) probe(origin)?.let { return it }

        val typeKey = HealthSyncLogic.typeKey(types)
        val s = state()
        val storedToken = s.token?.takeIf { s.tokenTypes == typeKey && s.tokenOrigin == origin }
        val collected = withContext(Dispatchers.IO) { source.collect(types, storedToken) }
        // Nothing new: the token still moves on.
        if (collected.changes.isEmpty()) return SyncOutcome.Done(count, collected.token, typeKey, origin)
        val round = withContext(Dispatchers.IO) { begin(collected, trigger, types, typeKey, origin) }
        upload(origin, round)?.let { return it }
        return SyncOutcome.Done(count + round.count, round.nextToken, typeKey, origin)
    }

    /** Is there a letterbox? Null to go on; otherwise the outcome that ends the sync. */
    private suspend fun probe(origin: String): SyncOutcome? = when (val found = letterbox.probe(origin)) {
        ProbeResult.Present, ProbeResult.Unknown -> null
        ProbeResult.Absent -> SyncOutcome.Skipped(SkipReason.NoBackendSupport)
        is ProbeResult.Failed -> SyncOutcome.Failed("Letterbox: ${found.message}")
    }

    /**
     * Write the round [collected] makes to the device, one part at a time
     * (a part's file is built and written, then forgotten, before the next),
     * ready to upload. IO thread.
     */
    private fun begin(collected: Collected, trigger: HealthTrigger, types: List<HealthType>, typeKey: String, origin: String): HealthRound {
        val parts = HealthSyncLogic.partCount(collected.changes.size)
        val sentAt = Instant.now()
        val round = HealthRound(
            batchId = UUID.randomUUID().toString(),
            origin = origin,
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
                app = client.app,
                version = client.version,
                androidSdk = client.androidSdk,
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
    private suspend fun upload(origin: String, round: HealthRound): SyncOutcome? {
        var current = round
        while (!current.complete) {
            val part = current.partsSent + 1
            val url = HealthSyncLogic.uploadUrl(origin, current.fileName(part))
            val body = withContext(Dispatchers.IO) { rounds.partFile(part).readBytes() }
            when (val result = letterbox.put(url, body)) {
                UploadResult.Stored -> current = withContext(Dispatchers.IO) { rounds.sent(current, part) }
                UploadResult.NoBackend -> return SyncOutcome.Skipped(SkipReason.NoBackendSupport)
                is UploadResult.Failed -> return SyncOutcome.Failed("Part $part of ${current.parts}: ${result.message}")
            }
        }
        withContext(Dispatchers.IO) { rounds.clear() }
        return null
    }
}
