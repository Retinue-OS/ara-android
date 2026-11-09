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
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.wake.WakeTargets

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
        scope.launch {
            AraStore(this@AraApp).settings
                // Each instance shown or working in the background listens for its own phrase.
                .map { WakeTargets.of(it) }
                .distinctUntilChanged()
                .collect { WakeRuntime.configure(it) }
        }
    }
}
