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

    private val _models = MutableStateFlow(WakeModelFiles.status(app))
    val models: StateFlow<WakeModelStatus> = _models.asStateFlow()

    /** A model download or import in progress, or its last result (shown in settings). */
    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy: StateFlow<Boolean> = _modelBusy.asStateFlow()

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

    fun setWakeMode(mode: WakeMode) = viewModelScope.launch { store.setWakeMode(mode) }
    fun setWakeThreshold(value: Float) = viewModelScope.launch { store.setWakeThreshold(value) }
    fun setWakeModelUrl(url: String) = viewModelScope.launch { store.setWakeModelUrl(url) }
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

/** A new launcher entry for this pinned shortcut (a web app), on the bar or in a folder. */
fun PinnedShortcut.toEntry() = LauncherEntry.Shortcut(ShellViewModel.newId(), label, packageName, shortcutId)
