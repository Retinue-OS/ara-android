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

/** The chamber apps of one endpoint, as last loaded. */
data class ChamberAppsState(
    val endpointId: String? = null,
    val apps: List<ChamberApp> = emptyList(),
)

/**
 * The shown endpoint's chamber apps, from its `/apps/index.json`, listed in
 * All apps (and the tray and the folder editor) with the phone's apps. The
 * last list per endpoint is kept on the device (the raw document), so they
 * are listed offline and from the start; the apps themselves load from the
 * server (or from their own service workers, offline). A failed fetch keeps
 * the list as it was.
 */
object ChamberApps {

    private const val PREFS = "chamber_apps_index"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChamberAppsState())
    val state: StateFlow<ChamberAppsState> = _state.asStateFlow()

    /** List [endpoint]'s apps (from the stored copy at once) and fetch them afresh. */
    fun load(context: Context, endpoint: Endpoint?) {
        val app = context.applicationContext
        val base = endpoint?.base
        if (endpoint == null || base == null) {
            _state.value = ChamberAppsState()
            return
        }
        if (_state.value.endpointId != endpoint.id) {
            _state.value = ChamberAppsState(endpoint.id, AppsLogic.parseIndex(prefs(app).getString(endpoint.id, null), base))
        }
        scope.launch {
            val document = AppsClient.get(app, endpoint, AppsLogic.INDEX_PATH) ?: return@launch
            if (_state.value.endpointId != endpoint.id) return@launch  // switched meanwhile
            prefs(app).edit().putString(endpoint.id, document).apply()
            _state.value = ChamberAppsState(endpoint.id, AppsLogic.parseIndex(document, base))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
