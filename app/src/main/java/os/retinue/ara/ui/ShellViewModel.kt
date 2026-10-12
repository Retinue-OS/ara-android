package os.retinue.ara.ui

import android.app.Application
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.launcher.AppCatalog
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.FolderIcons
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

    /** Tells Ara about apps uninstalled while it runs (an update never counts as a removal). */
    private val launcherApps: LauncherApps? = app.getSystemService(LauncherApps::class.java)

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            // The bar holds this user's apps only.
            if (user == Process.myUserHandle()) viewModelScope.launch { pruneUninstalled(listOf(packageName)) }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = Unit
        override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
    }

    init {
        // Folder icons an interrupted save left behind; anything stored from now on is kept.
        val started = System.currentTimeMillis()
        viewModelScope.launch { FolderIcons.sweep(appCtx, store.launcher.first().iconNames(), before = started) }
        // Apps uninstalled while Ara was not running; from now on the callback reports them.
        viewModelScope.launch { pruneUninstalled(store.launcher.first().appPackageNames()) }
        launcherApps?.registerCallback(packageCallback)
    }

    override fun onCleared() {
        launcherApps?.unregisterCallback(packageCallback)
        super.onCleared()
    }

    fun refreshApps() {
        viewModelScope.launch { _apps.value = AppCatalog.launchableApps(appCtx) }
    }

    fun setBaseUrl(url: String) = viewModelScope.launch { store.setBaseUrl(url) }
    fun setCertificateAlias(alias: String?) = viewModelScope.launch { store.setCertificateAlias(alias) }
    fun setWakeMode(mode: WakeMode) = viewModelScope.launch { store.setWakeMode(mode) }
    fun setWakeThreshold(value: Float) = viewModelScope.launch { store.setWakeThreshold(value) }
    fun setWakeModelUrl(url: String) = viewModelScope.launch { store.setWakeModelUrl(url) }

    fun updateLauncher(change: (LauncherConfig) -> LauncherConfig) =
        viewModelScope.launch { editLauncher(change = change) }

    fun pin(app: AppInfo) = updateLauncher { it.add(app.toEntry()) }

    fun addToFolder(app: AppInfo, folderId: String) = updateLauncher { it.addToFolder(folderId, app.toEntry()) }

    /**
     * Save a folder from the editor. [newIcon], a picked image not stored yet,
     * is stored first and becomes the folder's icon; if it cannot be stored,
     * the folder is saved with the icon it had.
     */
    fun saveFolder(folder: LauncherEntry.Folder, isNew: Boolean, newIcon: ByteArray?) = viewModelScope.launch {
        val stored = newIcon?.let {
            try {
                FolderIcons.store(appCtx, it)
            } catch (e: IOException) {
                Toast.makeText(appCtx, R.string.folder_icon_failed, Toast.LENGTH_SHORT).show()
                null
            }
        }
        val saved = if (stored != null) folder.copy(icon = stored) else folder
        editLauncher(stored) { if (isNew) it.add(saved) else it.replace(saved) }
    }

    /**
     * Remove an entry; a shortcut is also unpinned (Ara keeps pinned exactly the
     * shortcuts of that package still on the bar), so Android and the app know.
     */
    fun removeEntry(entry: LauncherEntry) {
        viewModelScope.launch {
            val (_, after) = editLauncher { it.remove(entry.id) }
            if (entry is LauncherEntry.Shortcut) {
                AppCatalog.syncPinnedShortcuts(appCtx, entry.packageName, after.shortcutIds(entry.packageName))
            }
        }
    }

    /** Back to the defaults, which hold no shortcuts: every shortcut on the bar is unpinned. */
    fun resetLauncher() = viewModelScope.launch {
        val (before, _) = editLauncher { LauncherConfig.DEFAULT }
        val packages = before.entries.filterIsInstance<LauncherEntry.Shortcut>().map { it.packageName }
        packages.distinct().forEach { AppCatalog.syncPinnedShortcuts(appCtx, it, emptyList()) }
    }

    /**
     * Take the apps (and pinned shortcuts) of those [candidates] that are really
     * uninstalled off the bar and out of folders; see [AppCatalog.isInstalled]
     * for what still counts as installed.
     */
    private suspend fun pruneUninstalled(candidates: Collection<String>) {
        val gone = withContext(Dispatchers.IO) { candidates.filterNot { AppCatalog.isInstalled(appCtx, it) }.toSet() }
        if (gone.isNotEmpty()) editLauncher { it.withoutPackages(gone) }
    }

    /**
     * Apply [change], then delete the folder icons no longer in use: those it
     * dropped, and [stored] (an icon stored for it) if the change did not keep
     * it. Returns the launcher before and after.
     */
    private suspend fun editLauncher(
        stored: String? = null,
        change: (LauncherConfig) -> LauncherConfig,
    ): Pair<LauncherConfig, LauncherConfig> {
        val (before, after) = store.updateLauncher(change)
        val unused = before.iconNames() + listOfNotNull(stored) - after.iconNames()
        if (unused.isNotEmpty()) FolderIcons.delete(appCtx, unused)
        return before to after
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

/** A new launcher entry for this app, on the bar or in a folder. */
fun AppInfo.toEntry() = LauncherEntry.App(ShellViewModel.newId(), label, packageName, activityName)
