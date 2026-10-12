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
import os.retinue.ara.launcher.PinnedShortcut
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

    private val _shortcuts = MutableStateFlow<List<PinnedShortcut>>(emptyList())
    val shortcuts: StateFlow<List<PinnedShortcut>> = _shortcuts.asStateFlow()

    private val _models = MutableStateFlow(WakeModelFiles.status(app))
    val models: StateFlow<WakeModelStatus> = _models.asStateFlow()

    /** A model download or import in progress, or its last result (shown in settings). */
    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy: StateFlow<Boolean> = _modelBusy.asStateFlow()

    val wakeStatus: StateFlow<WakeStatus> = WakeRuntime.status

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = AppCatalog.launchableApps(appCtx)
            _shortcuts.value = AppCatalog.pinnedShortcuts(appCtx)
        }
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

    fun pin(shortcut: PinnedShortcut) = updateLauncher {
        it.add(LauncherEntry.Shortcut(newId(), shortcut.label, shortcut.packageName, shortcut.shortcutId))
    }

    /** Off the bar only: a shortcut stays pinned, and listed under All apps. */
    fun removeEntry(entry: LauncherEntry) = updateLauncher { it.remove(entry.id) }

    fun resetLauncher() = updateLauncher { LauncherConfig.DEFAULT }

    /** Remove a shortcut for good: unpinned, off the bar and out of All apps. */
    fun forget(shortcut: PinnedShortcut) {
        viewModelScope.launch {
            store.updateLauncher { config ->
                config.entries
                    .filter { it is LauncherEntry.Shortcut && it.packageName == shortcut.packageName && it.shortcutId == shortcut.shortcutId }
                    .fold(config) { c, e -> c.remove(e.id) }
            }
            AppCatalog.unpinShortcut(appCtx, shortcut.packageName, shortcut.shortcutId)
            _shortcuts.value = AppCatalog.pinnedShortcuts(appCtx)
        }
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
