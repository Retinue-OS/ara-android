package os.retinue.ara.apps

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import os.retinue.ara.data.Endpoint

/** What the Apps sheet shows for one endpoint. */
data class ChamberAppsState(
    val endpointId: String? = null,
    val apps: List<ChamberApp> = emptyList(),
    val loading: Boolean = false,
    /** The list is the last one loaded, not a fresh one (the fetch failed). */
    val stale: Boolean = false,
    /** Nothing was ever loaded for this endpoint and the fetch failed. */
    val failed: Boolean = false,
)

/**
 * The shown endpoint's chamber apps, from its `/apps/index.json`, for the
 * launcher bar's *Apps* slot and sheet. The last list per endpoint is kept on
 * the device (the raw document), so the sheet shows it offline and the slot
 * is there from the start; the apps themselves load from the server (or from
 * their own service workers, offline).
 */
object ChamberApps {

    private const val PREFS = "chamber_apps_index"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChamberAppsState())
    val state: StateFlow<ChamberAppsState> = _state.asStateFlow()

    /** Show [endpoint]'s apps (from the stored copy at once) and fetch them afresh. */
    fun load(context: Context, endpoint: Endpoint?) {
        val app = context.applicationContext
        val base = endpoint?.base
        if (endpoint == null || base == null) {
            _state.value = ChamberAppsState()
            return
        }
        val stored = AppsLogic.parseIndex(prefs(app).getString(endpoint.id, null), base)
        val same = _state.value.endpointId == endpoint.id
        _state.value = ChamberAppsState(endpoint.id, if (same) _state.value.apps else stored, loading = true)
        scope.launch {
            val document = AppsClient.get(app, endpoint, AppsLogic.INDEX_PATH)
            if (_state.value.endpointId != endpoint.id) return@launch  // switched meanwhile
            _state.value = if (document != null) {
                prefs(app).edit().putString(endpoint.id, document).apply()
                ChamberAppsState(endpoint.id, AppsLogic.parseIndex(document, base))
            } else {
                val last = _state.value.apps.ifEmpty { stored }
                ChamberAppsState(endpoint.id, last, stale = last.isNotEmpty(), failed = last.isEmpty())
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
