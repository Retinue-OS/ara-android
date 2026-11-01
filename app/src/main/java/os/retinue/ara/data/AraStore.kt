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
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode

// The same store file the voice client used, so the base URL and the
// certificate alias carry over an update; its other keys are simply ignored.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ara")

/**
 * The single persistence surface: shell settings, the launcher bar (as JSON,
 * see [LauncherCodec]) and the health sync state.
 */
class AraStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ShellSettings> = store.data.map { p ->
        ShellSettings(
            baseUrl = p[K.baseUrl] ?: "",
            certificateAlias = p[K.certAlias],
            wakeMode = WakeMode.fromKey(p[K.wakeMode]),
            wakeThreshold = p[K.wakeThreshold] ?: ShellSettings.DEFAULT_WAKE_THRESHOLD,
            wakeModelUrl = p[K.wakeModelUrl] ?: ShellSettings.DEFAULT_WAKE_MODEL_URL,
            autoUpdate = p[K.autoUpdate] ?: false,
            updateChannel = UpdateChannel.fromKey(p[K.updateChannel]),
            splitLaunch = p[K.splitLaunch] ?: false,
            keepSplitInRecents = p[K.keepSplitInRecents] ?: false,
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

    val launcher: Flow<LauncherConfig> = store.data.map { p -> LauncherCodec.decode(p[K.launcher]) }

    suspend fun settingsNow(): ShellSettings = settings.first()

    suspend fun setBaseUrl(url: String) = edit { it[K.baseUrl] = url.trim() }

    suspend fun setCertificateAlias(alias: String?) = edit {
        if (alias == null) it.remove(K.certAlias) else it[K.certAlias] = alias
    }

    suspend fun setWakeMode(mode: WakeMode) = edit { it[K.wakeMode] = mode.key }

    suspend fun setWakeThreshold(value: Float) = edit { it[K.wakeThreshold] = value.coerceIn(0.05f, 0.99f) }

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
            it.putOrRemove(K.healthToken, s.token)
            it.putOrRemove(K.healthTokenTypes, s.tokenTypes)
            it.putOrRemove(K.healthTokenOrigin, s.tokenOrigin)
            it[K.healthPermissionsAsked] = s.permissionsAsked
            it[K.healthFailures] = s.failures
            it[K.healthBackoffUntil] = s.backoffUntil
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
        token = p[K.healthToken],
        tokenTypes = p[K.healthTokenTypes],
        tokenOrigin = p[K.healthTokenOrigin],
        permissionsAsked = p[K.healthPermissionsAsked] ?: false,
        failures = p[K.healthFailures] ?: 0,
        backoffUntil = p[K.healthBackoffUntil] ?: 0L,
    )

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private object K {
        val baseUrl = stringPreferencesKey("base_url")
        val certAlias = stringPreferencesKey("certificate_alias")
        val wakeMode = stringPreferencesKey("wake_mode")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val wakeModelUrl = stringPreferencesKey("wake_model_url")
        val launcher = stringPreferencesKey("launcher_json")
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
        val healthToken = stringPreferencesKey("health_changes_token")
        val healthTokenTypes = stringPreferencesKey("health_changes_token_types")
        val healthTokenOrigin = stringPreferencesKey("health_changes_token_origin")
        val healthPermissionsAsked = booleanPreferencesKey("health_permissions_asked")
        val healthFailures = intPreferencesKey("health_failures")
        val healthBackoffUntil = longPreferencesKey("health_backoff_until")
    }
}
