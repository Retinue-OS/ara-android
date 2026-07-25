package os.retinue.ara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import os.retinue.ara.state.AraPreferences
import os.retinue.ara.state.JobRecord

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ara")

/**
 * Single persistence surface: user preferences, runtime settings, and the one
 * durable job record. Preferences and settings are simple typed keys; the job
 * record is stored as a JSON blob (small, single-writer).
 *
 * No secrets are persisted here — only the certificate *alias* is stored; the
 * private key never leaves the Android KeyChain.
 */
class AraStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val preferences: Flow<AraPreferences> = context.dataStore.data.map { p ->
        AraPreferences(
            continuousConversation = p[K.continuous] ?: true,
            automaticPlayback = p[K.autoPlay] ?: false,
            cloudRecognitionDisclosureAcknowledged = p[K.cloudAck] ?: false,
            selectedCertificateAlias = p[K.certAlias],
        )
    }

    val settings: Flow<AraSettings> = context.dataStore.data.map { p ->
        AraSettings(
            baseUrl = p[K.baseUrl] ?: "",
            messagePath = p[K.messagePath] ?: "/message",
            pollIntervalMs = p[K.pollInterval] ?: 3_000,
            pollIntervalMaxMs = p[K.pollIntervalMax] ?: 300_000,
            pollBackoff = p[K.pollBackoff] ?: 2.0,
            requestTimeoutMs = p[K.requestTimeout] ?: 30_000,
            overallJobTimeoutMs = p[K.overallTimeout] ?: 3_600_000,
            maxConsecutivePollFailures = p[K.maxFailures] ?: 10,
            autoEndOfSpeech = p[K.autoEndOfSpeech] ?: true,
            silenceTimeoutMs = p[K.silenceTimeout] ?: 0,
        )
    }

    val job: Flow<JobRecord?> = context.dataStore.data.map { p ->
        p[K.jobJson]?.let { runCatching { json.decodeFromString<JobRecord>(it) }.getOrNull() }
    }

    suspend fun preferencesNow(): AraPreferences = preferences.first()
    suspend fun settingsNow(): AraSettings = settings.first()
    suspend fun jobNow(): JobRecord? = job.first()

    suspend fun setContinuousConversation(enabled: Boolean) =
        edit { it[K.continuous] = enabled }

    suspend fun setAutomaticPlayback(enabled: Boolean) =
        edit { it[K.autoPlay] = enabled }

    suspend fun setCloudDisclosureAcknowledged(ack: Boolean) =
        edit { it[K.cloudAck] = ack }

    suspend fun setCertificateAlias(alias: String?) = edit {
        if (alias == null) it.remove(K.certAlias) else it[K.certAlias] = alias
    }

    suspend fun setSettings(s: AraSettings) = edit {
        it[K.baseUrl] = s.baseUrl
        it[K.messagePath] = s.messagePath
        it[K.pollInterval] = s.pollIntervalMs
        it[K.pollIntervalMax] = s.pollIntervalMaxMs
        it[K.pollBackoff] = s.pollBackoff
        it[K.requestTimeout] = s.requestTimeoutMs
        it[K.overallTimeout] = s.overallJobTimeoutMs
        it[K.maxFailures] = s.maxConsecutivePollFailures
        it[K.autoEndOfSpeech] = s.autoEndOfSpeech
        it[K.silenceTimeout] = s.silenceTimeoutMs
    }

    suspend fun saveJob(record: JobRecord?) = edit {
        if (record == null) it.remove(K.jobJson)
        else it[K.jobJson] = json.encodeToString(JobRecord.serializer(), record)
    }

    private suspend fun edit(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.dataStore.edit(block)
    }

    private object K {
        val continuous = booleanPreferencesKey("continuous_conversation")
        val autoPlay = booleanPreferencesKey("automatic_playback")
        val cloudAck = booleanPreferencesKey("cloud_disclosure_ack")
        val certAlias = stringPreferencesKey("certificate_alias")
        val baseUrl = stringPreferencesKey("base_url")
        val messagePath = stringPreferencesKey("message_path")
        val pollInterval = longPreferencesKey("poll_interval_ms")
        val pollIntervalMax = longPreferencesKey("poll_interval_max_ms")
        val pollBackoff = doublePreferencesKey("poll_backoff")
        val requestTimeout = longPreferencesKey("request_timeout_ms")
        val overallTimeout = longPreferencesKey("overall_timeout_ms")
        val maxFailures = intPreferencesKey("max_consecutive_poll_failures")
        val autoEndOfSpeech = booleanPreferencesKey("auto_end_of_speech")
        val silenceTimeout = longPreferencesKey("silence_timeout_ms")
        val jobJson = stringPreferencesKey("job_record_json")
    }
}
