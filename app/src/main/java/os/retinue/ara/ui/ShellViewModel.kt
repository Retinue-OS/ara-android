package os.retinue.ara.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.launcher.AppCatalog
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelFiles
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.wake.WakeStatus

/** Settings, launcher bar, app list and model downloads for the shell's native UI. */
class ShellViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    private val store = AraStore(app)

    /** Null until the store has been read once (so first-run detection is not fooled). */
    val settings: StateFlow<ShellSettings?> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val launcher: StateFlow<LauncherConfig> =
        store.launcher.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherConfig.DEFAULT)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _models = MutableStateFlow(WakeModelFiles.status(app))
    val models: StateFlow<WakeModelStatus> = _models.asStateFlow()

    /** A model download or import in progress, or its last result (shown in settings). */
    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy: StateFlow<Boolean> = _modelBusy.asStateFlow()

    val wakeStatus: StateFlow<WakeStatus> = WakeRuntime.status

    fun refreshApps() {
        viewModelScope.launch { _apps.value = AppCatalog.launchableApps(appCtx) }
    }

    fun setBaseUrl(url: String) = viewModelScope.launch { store.setBaseUrl(url) }
    fun setCertificateAlias(alias: String?) = viewModelScope.launch { store.setCertificateAlias(alias) }
    fun setWakeMode(mode: WakeMode) = viewModelScope.launch { store.setWakeMode(mode) }
    fun setWakeThreshold(value: Float) = viewModelScope.launch { store.setWakeThreshold(value) }
    fun setWakeModelUrl(url: String) = viewModelScope.launch { store.setWakeModelUrl(url) }

    fun updateLauncher(change: (LauncherConfig) -> LauncherConfig) =
        viewModelScope.launch { store.updateLauncher(change) }

    fun pin(app: AppInfo) = updateLauncher {
        it.add(LauncherEntry.App(newId(), app.label, app.packageName, app.activityName))
    }

    /**
     * Remove an entry; a shortcut is also unpinned (Ara keeps pinned exactly the
     * shortcuts of that package still on the bar), so Android and the app know.
     */
    fun removeEntry(entry: LauncherEntry) {
        viewModelScope.launch {
            var after: LauncherConfig? = null
            store.updateLauncher { it.remove(entry.id).also { c -> after = c } }
            val remaining = after
            if (entry is LauncherEntry.Shortcut && remaining != null) {
                AppCatalog.syncPinnedShortcuts(appCtx, entry.packageName, remaining.shortcutIds(entry.packageName))
            }
        }
    }

    /** Back to the defaults, which hold no shortcuts: every shortcut on the bar is unpinned. */
    fun resetLauncher() = viewModelScope.launch {
        var before: LauncherConfig? = null
        store.updateLauncher {
            before = it
            LauncherConfig.DEFAULT
        }
        val packages = before?.entries.orEmpty().filterIsInstance<LauncherEntry.Shortcut>().map { it.packageName }
        packages.distinct().forEach { AppCatalog.syncPinnedShortcuts(appCtx, it, emptyList()) }
    }

    fun downloadFeatureModels() = modelJob {
        WakeModelFiles.downloadFeatureModels(appCtx, store.settingsNow())
        WakeModelFiles.validate(appCtx)
    }

    fun downloadPhraseModel(url: String) = modelJob {
        store.setWakeModelUrl(url)
        WakeModelFiles.downloadPhraseModel(appCtx, store.settingsNow(), url)
    }

    fun importPhraseModel(uri: Uri) = modelJob {
        WakeModelFiles.importPhraseModel(appCtx, uri)
    }

    fun refreshModels() {
        _models.value = WakeModelFiles.status(appCtx)
    }

    private fun modelJob(block: suspend () -> Unit) {
        if (_modelBusy.value) return
        _modelBusy.value = true
        _modelMessage.value = null
        viewModelScope.launch {
            val app = appCtx
            _modelMessage.value = try {
                block()
                app.getString(R.string.wake_models_done)
            } catch (e: Exception) {
                app.getString(R.string.wake_models_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                _modelBusy.value = false
                refreshModels()
                WakeRuntime.refresh()
            }
        }
    }

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
