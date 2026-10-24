package os.retinue.ara

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import os.retinue.ara.data.AraStore
import os.retinue.ara.update.UpdateRuntime
import os.retinue.ara.wake.WakeRuntime

/**
 * Process start: wake detection and the updater follow the stored settings for
 * as long as the process lives (the activity and the wake service come and go).
 */
class AraApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        WakeRuntime.init(this)
        UpdateRuntime.init(this)
        scope.launch {
            AraStore(this@AraApp).settings
                .map { it.wakeMode to it.wakeThreshold }
                .distinctUntilChanged()
                .collect { (mode, threshold) -> WakeRuntime.configure(mode, threshold) }
        }
    }
}
