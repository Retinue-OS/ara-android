package os.retinue.ara.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject

/**
 * Health Connect through the Jetpack client, for [HealthSyncer]. The client
 * is made by [create] on first use only — `HealthConnectClient.getOrCreate`
 * throws on a device without Health Connect — so [available] (which asks
 * the SDK status, never the client) is the gate everything else waits
 * behind. Reads on the IO dispatcher.
 */
class HealthConnectSource(
    private val available: () -> Boolean,
    private val create: () -> HealthConnectClient,
) : HealthSource {

    private val client: HealthConnectClient by lazy(LazyThreadSafetyMode.NONE) { create() }

    override fun available(): Boolean = available.invoke()

    override suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /** Asked for with the read permissions where Health Connect supports reading in the background. */
    override suspend fun backgroundPermission(): Set<String> {
        val supported = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            false
        }
        return if (supported) setOf(HealthRecords.BACKGROUND_PERMISSION) else emptySet()
    }

    /**
     * What to send: the changes since [storedToken], or the whole window
     * without a usable one. A token Health Connect no longer knows (it was
     * reset or reinstalled; an invalid-argument error) counts as expired:
     * the window is read again, rather than every sync failing on it.
     */
    override suspend fun collect(types: List<HealthType>, storedToken: String?): Collected {
        if (storedToken == null) return readWindow(types, SyncKind.Initial)
        val changes = mutableListOf<JsonObject>()
        var token: String = storedToken
        while (true) {
            val response = try {
                client.getChanges(token)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Health Connect rejected the changes token; reading the window again", e)
                return readWindow(types, SyncKind.Resync)
            }
            if (response.changesTokenExpired) return readWindow(types, SyncKind.Resync)
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
    private suspend fun readWindow(types: List<HealthType>, kind: SyncKind): Collected {
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

    private companion object {
        const val TAG = "HealthConnectSource"

        /** Records per readRecords page in an initial read (Health Connect allows up to 5000). */
        const val PAGE_SIZE = 1000
    }
}
