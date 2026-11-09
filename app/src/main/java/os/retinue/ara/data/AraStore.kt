package os.retinue.ara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import os.retinue.ara.health.HealthSyncLogic
import os.retinue.ara.health.HealthSyncState
import os.retinue.ara.health.SyncResult
import os.retinue.ara.launcher.AppRecency
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode

// The same store file the voice client used, so the base URL and the
// certificate alias carry over an update; its other keys are simply ignored.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ara")

/**
 * The single persistence surface: shell settings (the endpoints as JSON, see
 * [EndpointCodec]), the launcher bar (as JSON, see [LauncherCodec]), when Ara
 * last launched each app (see [AppRecency]) and the health sync state.
 *
 * Endpoints replaced the one base URL and certificate alias of earlier builds:
 * until the endpoint list is written once, those two keys are read as the
 * first endpoint, and every write of the list mirrors the current endpoint
 * back into them, so an older build still finds its server.
 */
class AraStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ShellSettings> = store.data.map { p ->
        val endpoints = endpointsOf(p)
        ShellSettings(
            endpoints = endpoints.endpoints,
            currentEndpointId = endpoints.currentId,
            wakeModelUrl = p[K.wakeModelUrl] ?: ShellSettings.DEFAULT_WAKE_MODEL_URL,
            autoUpdate = p[K.autoUpdate] ?: false,
            updateChannel = UpdateChannel.fromKey(p[K.updateChannel]),
            splitLaunch = p[K.splitLaunch] ?: false,
            keepSplitInRecents = p[K.keepSplitInRecents] ?: false,
            deviceWebSettings = DeviceWebSettings.decode(p[K.deviceWebSettings]),
        )
    }

    /**
     * When the update channel was last checked, in epoch ms (0 for never), as
     * long as that check was this build's ([build]): after an update, the new
     * build checks afresh. State, not a setting.
     */
    fun updateCheckedAt(build: String): Flow<Long> = store.data.map { p ->
        if (p[K.updateCheckedBy] == build) p[K.updateCheckedAt] ?: 0L else 0L
    }

    /** Health Connect sync: its two settings and its state (see HealthSyncRuntime). */
    val healthSync: Flow<HealthSyncState> = store.data.map { healthSyncOf(it) }

    /**
     * Set one of the dashboard's per-device preferences (null removes it).
     * False when [DeviceWebSettings] refuses the change; nothing is stored then.
     */
    suspend fun setDeviceWebSetting(key: String, value: String?): Boolean {
        var accepted = false
        edit { p ->
            val after = DeviceWebSettings.apply(DeviceWebSettings.decode(p[K.deviceWebSettings]), key, value) ?: return@edit
            accepted = true
            p[K.deviceWebSettings] = DeviceWebSettings.encode(after)
        }
        return accepted
    }

    val launcher: Flow<LauncherConfig> = store.data.map { p -> LauncherCodec.decode(p[K.launcher]) }

    /** When Ara last launched each app or web app (by [os.retinue.ara.launcher.LauncherEntry.Launchable.key], epoch ms). */
    val launches: Flow<Map<String, Long>> = store.data.map { p -> AppRecency.decode(p[K.launches]) }

    suspend fun settingsNow(): ShellSettings = settings.first()

    /**
     * Apply [change] to the stored endpoints atomically (no lost updates
     * between edits) and return the result. The current endpoint's base URL
     * and certificate alias are mirrored into the legacy keys.
     */
    suspend fun updateEndpoints(change: (EndpointSet) -> EndpointSet): EndpointSet {
        var after = EndpointSet.EMPTY
        edit { p ->
            after = change(endpointsOf(p))
            p[K.endpoints] = EndpointCodec.encode(after.endpoints)
            p.putOrRemove(K.currentEndpoint, after.currentId)
            val current = after.current
            p.putOrRemove(K.baseUrl, current?.baseUrl?.trim()?.takeIf { it.isNotEmpty() })
            p.putOrRemove(K.certAlias, current?.certificateAlias)
        }
        return after
    }

    private fun endpointsOf(p: Preferences): EndpointSet {
        val list = EndpointCodec.decode(p[K.endpoints]) ?: EndpointCodec.legacy(p[K.baseUrl], p[K.certAlias])
        return EndpointSet(EndpointCodec.withLegacyWake(list, legacyWake(p)), p[K.currentEndpoint])
    }

    /**
     * The one device-wide wake setting of builds before per-instance wake
     * phrases: what an endpoint without its own starts with. The keys stay,
     * so an older build still finds its setting.
     */
    private fun legacyWake(p: Preferences): EndpointWake = EndpointWake.of(
        WakeMode.fromKey(p[K.wakeMode]),
        p[K.wakeThreshold] ?: EndpointWake.DEFAULT_THRESHOLD,
    )

    /** The wake phrase of the endpoint [id] on this phone: [change] applied to what it has. */
    suspend fun updateEndpointWake(id: String, change: (EndpointWake) -> EndpointWake) {
        updateEndpoints { set -> set.update(id) { e -> e.copy(wake = change(e.wake ?: EndpointWake.OFF)) } }
    }

    suspend fun setWakeModelUrl(url: String) = edit { it[K.wakeModelUrl] = url.trim() }

    suspend fun setAutoUpdate(on: Boolean) = edit { it[K.autoUpdate] = on }

    suspend fun setUpdateChannel(channel: UpdateChannel) = edit { it[K.updateChannel] = channel.key }

    suspend fun setSplitLaunch(on: Boolean) = edit { it[K.splitLaunch] = on }

    suspend fun setKeepSplitInRecents(on: Boolean) = edit { it[K.keepSplitInRecents] = on }

    suspend fun setUpdateCheckedAt(epochMs: Long, build: String) = edit {
        it[K.updateCheckedAt] = epochMs
        it[K.updateCheckedBy] = build
    }

    /**
     * Apply [change] to the stored launcher atomically (no lost updates between
     * edits). Returns the launcher before and after, so what an edit dropped
     * (a folder's custom icon) can be cleaned up.
     */
    suspend fun updateLauncher(change: (LauncherConfig) -> LauncherConfig): Pair<LauncherConfig, LauncherConfig> {
        var before = LauncherConfig.DEFAULT
        var after = LauncherConfig.DEFAULT
        edit {
            before = LauncherCodec.decode(it[K.launcher])
            after = change(before)
            it[K.launcher] = LauncherCodec.encode(after)
        }
        return before to after
    }

    /** Ara launched [key] (an app or web app) at [at]. */
    suspend fun recordLaunch(key: String, at: Long) = edit {
        it[K.launches] = AppRecency.encode(AppRecency.recorded(AppRecency.decode(it[K.launches]), key, at))
    }

    /** Apply [change] to the stored health sync state atomically; returns the new state. */
    suspend fun updateHealthSync(change: (HealthSyncState) -> HealthSyncState): HealthSyncState {
        var after = HealthSyncState()
        edit {
            val s = change(healthSyncOf(it))
            after = s
            it[K.healthInterval] = s.intervalMinutes.coerceAtLeast(1)
            it[K.healthAuto] = s.autoOnResume
            it[K.healthLastSyncAt] = s.lastSyncAt
            it[K.healthLastAttemptAt] = s.lastAttemptAt
            it.putOrRemove(K.healthLastResult, s.lastResult?.key)
            it.putOrRemove(K.healthLastError, s.lastError)
            val count = s.lastCount
            if (count == null) it.remove(K.healthLastCount) else it[K.healthLastCount] = count
            it[K.healthTokens] = HealthSyncLogic.encodeTokens(s.tokens)
            // The one token of older builds now lives in the map.
            it.remove(K.healthToken)
            it.remove(K.healthTokenTypes)
            it.remove(K.healthTokenOrigin)
            it[K.healthPermissionsAsked] = s.permissionsAsked
            it[K.healthFailures] = s.failures
            it[K.healthBackoffUntil] = s.backoffUntil
            it.putOrRemove(K.healthBackoffOrigin, s.backoffOrigin)
            if (s.peers.isEmpty()) it.remove(K.healthPeers) else it[K.healthPeers] = HealthSyncLogic.encodePeers(s.peers)
        }
        return after
    }

    private fun healthSyncOf(p: Preferences) = HealthSyncState(
        intervalMinutes = (p[K.healthInterval] ?: HealthSyncLogic.DEFAULT_INTERVAL_MINUTES).coerceAtLeast(1),
        autoOnResume = p[K.healthAuto] ?: true,
        lastSyncAt = p[K.healthLastSyncAt] ?: 0L,
        lastAttemptAt = p[K.healthLastAttemptAt] ?: 0L,
        lastResult = SyncResult.fromKey(p[K.healthLastResult]),
        lastError = p[K.healthLastError],
        lastCount = p[K.healthLastCount],
        tokens = HealthSyncLogic.decodeTokens(p[K.healthTokens])
            ?: HealthSyncLogic.legacyTokens(p[K.healthToken], p[K.healthTokenTypes], p[K.healthTokenOrigin]),
        permissionsAsked = p[K.healthPermissionsAsked] ?: false,
        failures = p[K.healthFailures] ?: 0,
        backoffUntil = p[K.healthBackoffUntil] ?: 0L,
        backoffOrigin = p[K.healthBackoffOrigin],
        peers = HealthSyncLogic.decodePeers(p[K.healthPeers]),
    )

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private object K {
        /** The one base URL and certificate alias of builds before the endpoint list; mirrored from the current endpoint. */
        val baseUrl = stringPreferencesKey("base_url")
        val certAlias = stringPreferencesKey("certificate_alias")
        val endpoints = stringPreferencesKey("endpoints_json")
        val currentEndpoint = stringPreferencesKey("current_endpoint")
        val wakeMode = stringPreferencesKey("wake_mode")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val wakeModelUrl = stringPreferencesKey("wake_model_url")
        val launcher = stringPreferencesKey("launcher_json")
        val launches = stringPreferencesKey("app_launches_json")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val updateChannel = stringPreferencesKey("update_channel")
        val updateCheckedAt = longPreferencesKey("update_checked_at")
        val updateCheckedBy = stringPreferencesKey("update_checked_by")
        val splitLaunch = booleanPreferencesKey("split_launch")
        val keepSplitInRecents = booleanPreferencesKey("keep_split_in_recents")
        val healthInterval = intPreferencesKey("health_interval_minutes")
        val healthAuto = booleanPreferencesKey("health_auto_on_resume")
        val healthLastSyncAt = longPreferencesKey("health_last_sync_at")
        val healthLastAttemptAt = longPreferencesKey("health_last_attempt_at")
        val healthLastResult = stringPreferencesKey("health_last_result")
        val healthLastError = stringPreferencesKey("health_last_error")
        val healthLastCount = intPreferencesKey("health_last_count")
        /** Each server's Changes token (JSON, origin → token; see [HealthSyncState.tokens]). */
        val healthTokens = stringPreferencesKey("health_changes_tokens_json")

        /** The one Changes token of builds before per-server tokens, with its types and origin: read once, then removed. */
        val healthToken = stringPreferencesKey("health_changes_token")
        val healthTokenTypes = stringPreferencesKey("health_changes_token_types")
        val healthTokenOrigin = stringPreferencesKey("health_changes_token_origin")
        val healthPermissionsAsked = booleanPreferencesKey("health_permissions_asked")
        val healthFailures = intPreferencesKey("health_failures")
        val healthBackoffUntil = longPreferencesKey("health_backoff_until")
        val healthBackoffOrigin = stringPreferencesKey("health_backoff_origin")

        /** The background servers' sync state (JSON, origin → PeerSync). */
        val healthPeers = stringPreferencesKey("health_background_json")

        /** The dashboard's per-device preferences (JSON, see [DeviceWebSettings]). */
        val deviceWebSettings = stringPreferencesKey("device_web_settings_json")
    }
}
