package os.retinue.ara

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import os.retinue.ara.service.AraForeground

/**
 * Maintains the process-level foreground flag the polling service reads to
 * decide whether a completed job should raise a background answer-ready
 * notification.
 */
class AraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AraForeground.inForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                AraForeground.inForeground = false
            }
        })
    }
}
