package os.retinue.ara.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.data.CredentialVault
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.launcher.AppCatalog
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.AppUsage
import os.retinue.ara.launcher.FolderIcons
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import os.retinue.ara.net.EndpointNames
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelEntry
import os.retinue.ara.wake.WakeModelFiles
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.wake.WakeStatus

/** Settings, launcher bar, app list (and its order by recent use) and model downloads for the shell's native UI. */
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

    /** Android's last use of each package, with usage access (see [AppUsage]); empty without. */
    private val _usage = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val _usageAccess = MutableStateFlow(AppUsage.hasAccess(app))

    /** The user has given Ara usage access, so the tray also sees apps opened elsewhere. */
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /**
     * The tray's lines: every app and web app but those on the bar itself
     * (apps in folders included, folders not), the most recently used first.
     */
    internal val trayItems: StateFlow<List<DrawerItem>> =
        combine(_apps, _shortcuts, launcher, store.launches, _usage) { apps, shortcuts, bar, launches, usage ->
            trayItems(apps, shortcuts, bar, launches, usage)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // The models and the job that changes them are the phone's, the same in
    // every window (WakeModelFiles.state); what a job reported is this window's.

    /** The feature models' files (its `phrase` is not used: see [library]). */
    val models: StateFlow<WakeModelStatus> = WakeModelFiles.state.features

    /** The phone's phrase models (the list), with whether each is downloaded. */
    val library: StateFlow<List<WakeModelEntry>> = WakeModelFiles.state.library

    /** The list's model a job is working on (a download, a removal), for its row. */
    val modelBusyId: StateFlow<String?> = WakeModelFiles.state.busyId

    /** The last result of a model job started in this window (shown in settings). */
    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()

    /** A model download, import or removal is in progress, in whichever window. */
    val modelBusy: StateFlow<Boolean> = WakeModelFiles.state.busy

    val wakeStatus: StateFlow<WakeStatus> = WakeRuntime.status

    /** When each endpoint's name was last asked for (epoch ms), so a server that is down is not asked on every occasion. */
    private val nameAttempts = mutableMapOf<String, Long>()

    init {
        // Folder icons an interrupted save left behind; anything stored from now on is kept.
        val started = System.currentTimeMillis()
        viewModelScope.launch { FolderIcons.sweep(appCtx, store.launcher.first().iconNames(), before = started) }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = AppCatalog.launchableApps(appCtx)
            _shortcuts.value = AppCatalog.pinnedShortcuts(appCtx)
        }
    }

    /** Read Android's usage stats again (and whether Ara may), for the tray's order. */
    fun refreshUsage() {
        viewModelScope.launch {
            _usageAccess.value = AppUsage.hasAccess(appCtx)
            _usage.value = AppUsage.lastUsed(appCtx)
        }
    }

    /** Ara launched [item] (an app or web app) just now: it moves to the top of the tray. */
    fun recordLaunch(item: LauncherEntry.Launchable) {
        val at = System.currentTimeMillis()
        viewModelScope.launch { store.recordLaunch(item.key, at) }
    }

    // --- Endpoints -----------------------------------------------------------

    /**
     * Save what the editor holds (see [EndpointDraft.toEndpoint]): a new
     * endpoint, or the old one with its fields replaced. A typed password is
     * sealed first; an empty field keeps the stored one unless the draft says
     * to forget it. A changed address drops the fetched name, and the name is
     * fetched afresh.
     */
    fun saveEndpoint(draft: EndpointDraft) = viewModelScope.launch {
        val typed = draft.password.takeIf { draft.user != null && it.isNotEmpty() }
        val sealed = typed?.let { withContext(Dispatchers.IO) { CredentialVault.seal(it) } }
        store.updateEndpoints { set ->
            set.upsert(draft.toEndpoint(set.endpoints.firstOrNull { it.id == draft.id }, sealed))
        }
        refreshEndpointNames(force = true, onlyId = draft.id)
    }

    fun removeEndpoint(id: String) = viewModelScope.launch { store.updateEndpoints { it.remove(id) } }

    /** Show the endpoint [id] from now on (the windows follow the settings and load its start page). */
    fun selectEndpoint(id: String) = viewModelScope.launch {
        store.updateEndpoints { it.select(id) }
        refreshEndpointNames(onlyId = id)
    }

    /** The failure card's "Choose certificate": the current endpoint gets [alias]. */
    fun setEndpointCertificate(id: String, alias: String?) = viewModelScope.launch {
        store.updateEndpoints { set -> set.update(id) { it.copy(certificateAlias = alias) } }
    }

    /** The settings page's health switch: the endpoint [id] takes health data, or not. */
    fun setHealthUpload(id: String, on: Boolean) = viewModelScope.launch {
        store.updateEndpoints { set -> set.update(id) { it.copy(healthUpload = on) } }
    }

    /** The settings page's call switch: the endpoint [id]'s page may place calls directly, or not. */
    fun setDirectCalls(id: String, on: Boolean) = viewModelScope.launch {
        store.updateEndpoints { set -> set.update(id) { it.copy(directCalls = on) } }
    }

    /** The sign-in dialog's "Remember": the endpoint [id] keeps this user name and (sealed) password. */
    fun rememberCredentials(id: String, username: String, password: String) = viewModelScope.launch {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return@launch
        val sealed = withContext(Dispatchers.IO) { CredentialVault.seal(password) }
        store.updateEndpoints { set -> set.update(id) { it.copy(username = user, secret = sealed) } }
    }

    /**
     * Fetch the names of the endpoints that are due for it (see
     * [EndpointNames.due]) — all of them, or [onlyId] — and store what comes
     * back. A fetch that failed is not repeated within [EndpointNames.RETRY_MS]
     * unless [force]d.
     */
    fun refreshEndpointNames(force: Boolean = false, onlyId: String? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        for (endpoint in store.settingsNow().endpoints) {
            if (onlyId != null && endpoint.id != onlyId) continue
            if (!EndpointNames.due(endpoint, now, force)) continue
            val last = nameAttempts[endpoint.id]
            if (!force && last != null && now - last in 0 until EndpointNames.RETRY_MS) continue
            nameAttempts[endpoint.id] = now
            val name = EndpointNames.fetch(appCtx, endpoint) ?: continue
            store.updateEndpoints { set ->
                set.update(endpoint.id) { it.copy(name = name, nameFetchedAt = System.currentTimeMillis()) }
            }
        }
    }

    // --- Other settings ------------------------------------------------------

    /** The wake mode of the instance [endpointId] on this phone. */
    fun setWakeMode(endpointId: String, mode: WakeMode) = viewModelScope.launch {
        store.updateEndpointWake(endpointId) { it.copy(mode = mode.key) }
    }

    /** The wake threshold of the instance [endpointId] on this phone. */
    fun setWakeThreshold(endpointId: String, value: Float) = viewModelScope.launch {
        store.updateEndpointWake(endpointId) { EndpointWake.of(it.wakeMode, value, it.modelId) }
    }
    fun setAutoUpdate(on: Boolean) = viewModelScope.launch { store.setAutoUpdate(on) }
    fun setUpdateChannel(channel: UpdateChannel) = viewModelScope.launch { store.setUpdateChannel(channel) }
    fun setSplitLaunch(on: Boolean) = viewModelScope.launch { store.setSplitLaunch(on) }
    fun setKeepSplitInRecents(on: Boolean) = viewModelScope.launch { store.setKeepSplitInRecents(on) }

    /**
     * One of the dashboard's per-device preferences (DeviceWebSettings), for
     * every endpoint; null removes it. A key the app does not hold is ignored.
     */
    fun setDeviceWebSetting(key: String, value: String?) = viewModelScope.launch { store.setDeviceWebSetting(key, value) }

    fun updateLauncher(change: (LauncherConfig) -> LauncherConfig) =
        viewModelScope.launch { editLauncher(change = change) }

    fun pin(app: AppInfo) = updateLauncher { it.add(app.toEntry()) }

    /** Add an app or web app to the folder [folderId]. */
    fun addToFolder(item: LauncherEntry.Launchable, folderId: String) = updateLauncher { it.addToFolder(folderId, item) }

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

    fun pin(shortcut: PinnedShortcut) = updateLauncher { it.add(shortcut.toEntry()) }

    /** Off the bar only: a shortcut stays pinned, and listed under All apps. */
    fun removeEntry(entry: LauncherEntry) = updateLauncher { it.remove(entry.id) }

    fun resetLauncher() = updateLauncher { LauncherConfig.DEFAULT }

    /** Remove a shortcut (a web app) for good: unpinned, off the bar, out of folders and out of All apps. */
    fun forget(shortcut: PinnedShortcut) {
        viewModelScope.launch {
            editLauncher { it.withoutShortcut(shortcut.packageName, shortcut.shortcutId) }
            AppCatalog.unpinShortcut(appCtx, shortcut.packageName, shortcut.shortcutId)
            _shortcuts.value = AppCatalog.pinnedShortcuts(appCtx)
        }
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

    fun downloadFeatureModels() = modelJob(null) {
        WakeModelFiles.downloadFeatureModels(appCtx, store.settingsNow())
        // With the phrase model of the instance shown, whose card asked for them.
        WakeModelFiles.validate(appCtx, store.settingsNow().current?.wake?.modelId)
        appCtx.getString(R.string.wake_models_done)
    }

    /** Download the list's model [id]; using it is a choice of its own ([useModel]). */
    fun downloadModel(id: String) = modelJob(id) {
        WakeModelFiles.downloadModel(appCtx, store.settingsNow(), id)
        appCtx.getString(R.string.wake_model_downloaded, nameOf(id))
    }

    /**
     * Add a model from [url] to the list, download it, and use it for the
     * instance [endpointId]: the one shown when this was asked for, not one
     * shown since (a download takes its time). Null: for none.
     */
    fun addModelFromUrl(url: String, endpointId: String?) = modelJob(null) {
        val id = WakeModelFiles.addFromUrl(appCtx, store.settingsNow(), url)
        addedFor(endpointId, id)
    }

    /** Add the picked file to the list, under its name, and use it for the instance [endpointId] (as [addModelFromUrl]). */
    fun importModel(uri: Uri, endpointId: String?) = modelJob(null) {
        val id = WakeModelFiles.importModel(appCtx, store.settingsNow(), uri)
        addedFor(endpointId, id)
    }

    /**
     * The model [id], new on the list, becomes the phrase of the instance it
     * was added for, if that is still there; returns what to report.
     */
    private suspend fun addedFor(endpointId: String?, id: String): String {
        val name = nameOf(id)
        val after = endpointId?.let { store.updateEndpointWake(it) { wake -> wake.copy(modelId = id) } }
        val endpoint = after?.endpoints?.firstOrNull { it.id == endpointId }
            ?: return appCtx.getString(R.string.wake_model_added_unused, name)
        return appCtx.getString(R.string.wake_model_added, name, EndpointLabels.label(endpoint, after.endpoints))
    }

    /**
     * The instance [endpointId] listens for the downloaded model [id], or for
     * none (null). Whether the model can be chosen is asked of the files and
     * of the phone's one job, not of the list this window shows: the other
     * window may have removed it, or be removing it now, and no instance is
     * to be left with a model that is gone. The choice is on its way to the
     * store before any later job can begin.
     */
    fun useModel(endpointId: String, id: String?) {
        if (id != null && !WakeModelFiles.mayUse(appCtx, id)) {
            // The row that offered it goes, where the list shown was behind the files.
            refreshModels()
            return
        }
        _modelMessage.value = null
        viewModelScope.launch { store.updateEndpointWake(endpointId) { it.copy(modelId = id) } }
    }

    /**
     * Delete model [id]'s file; the user's own leave the list. The instance
     * shown lets go of it first. A model that another instance uses stays,
     * whatever the page offered: no instance is left with a model that is gone.
     */
    fun removeModel(id: String) = modelJob(id) {
        val name = nameOf(id)
        val users = WakeModelFiles.remove(appCtx, id) { store.releaseWakeModel(id) }
        if (users.isNotEmpty()) return@modelJob appCtx.getString(R.string.wake_model_in_use, name, users.joinToString())
        appCtx.getString(R.string.wake_model_removed, name)
    }

    private fun nameOf(id: String) = WakeModelFiles.model(appCtx, id)?.name ?: id

    /** Read the model files again, for both windows' cards. */
    fun refreshModels() = WakeModelFiles.refreshState(appCtx)

    /**
     * One model job at a time for the phone, whichever window starts it ([id]:
     * the list's model it works on); [block] returns what to report here. One
     * asked for while another runs waits its turn (WakeModelState.beginInTurn):
     * a file picked or a removal confirmed is not dropped, and reports here.
     */
    private fun modelJob(id: String?, block: suspend () -> String) {
        // Begun inside the coroutine: a window that is closing starts none, and so leaves none unfinished.
        viewModelScope.launch {
            val app = appCtx
            // Waited for outside the try: a window that closes meanwhile has begun no job, and ends none.
            WakeModelFiles.beginJob(id)
            _modelMessage.value = null
            _modelMessage.value = try {
                block()
            } catch (e: Exception) {
                app.getString(R.string.wake_models_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                // Also when the window closed under the job (cancelled): the phone's job is over.
                WakeModelFiles.endJob(app)
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

/** A new launcher entry for this pinned shortcut (a web app), on the bar or in a folder. */
fun PinnedShortcut.toEntry() = LauncherEntry.Shortcut(ShellViewModel.newId(), label, packageName, shortcutId)
