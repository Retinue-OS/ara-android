package os.retinue.ara

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import os.retinue.ara.data.AraStore
import os.retinue.ara.health.HealthSyncRuntime
import os.retinue.ara.update.UpdateRuntime
import os.retinue.ara.wake.WakeModelFiles
import os.retinue.ara.wake.WakeRuntime

/**
 * Process start: wake detection, the updater and health sync follow the stored
 * settings for as long as the process lives (the activity and the wake service
 * come and go).
 */
class AraApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WakeRuntime.init(this)
        UpdateRuntime.init(this)
        HealthSyncRuntime.init(this)
        val store = AraStore(this)
        // The one phrase model of earlier builds joins the library first, and stays the one in use.
        val migrated = WakeModelFiles.migrateLegacy(this)
        scope.launch {
            if (migrated != null) {
                // The earlier file goes only once the choice is stored; a failure here must not stop the settings below.
                runCatching { store.adoptWakeModelId(migrated) }
                    .onSuccess { WakeModelFiles.legacyAdopted(this@AraApp) }
            }
            store.settings
                .map { Triple(it.wakeMode, it.wakeThreshold, it.wakeModelId) }
                .distinctUntilChanged()
                .collect { (mode, threshold, model) -> WakeRuntime.configure(mode, threshold, model) }
        }
    }
}
