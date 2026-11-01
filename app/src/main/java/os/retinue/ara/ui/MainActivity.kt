package os.retinue.ara.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.security.KeyChain
import android.util.Log
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.window.SplashScreen
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.health.HealthPermissionRequester
import os.retinue.ara.health.HealthSyncLogic
import os.retinue.ara.health.HealthSyncRuntime
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import os.retinue.ara.shell.LinkTarget
import os.retinue.ara.shell.PageFailure
import os.retinue.ara.shell.PageStatus
import os.retinue.ara.shell.PageStatusTracker
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.shell.ShellLinks
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.shell.WebMessage
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.update.UpdateRuntime
import os.retinue.ara.update.UpdateState
import os.retinue.ara.wake.WakeAlert
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.web.ShellHost
import os.retinue.ara.web.ShellIntents
import os.retinue.ara.web.SplitAware

/**
 * The shell (plan §1): a full-size WebView showing the Retinue web app, the
 * launcher bar below it, and native settings on top when asked for. Also the
 * home activity when the user makes the shell their default home app.
 *
 * The same shell runs in a second window for split screen
 * ([SplitShellActivity], [isSplitWindow]); the page itself is shared between
 * the two (see [ShellPage]) and shown by the one in front.
 */
open class MainActivity : ComponentActivity(), ShellHost, SplitAware {

    companion object {
        /**
         * Started by the wake detector: bring the shell up and dispatch `wake`.
         * Honoured only with the detector's one-time token
         * ([WakeRuntime.EXTRA_WAKE_TOKEN]): the activity is exported.
         */
        const val ACTION_WAKE = "os.retinue.ara.action.WAKE"

        /** The shortcut the settings' pin test asks for. */
        private const val TEST_SHORTCUT_ID = "ara-pin-test"

        /** How long an app started beside the split-screen window has to get there (see [splitCheck]). */
        private const val SPLIT_CHECK_MS = 1_500L

        private const val TAG = "MainActivity"
    }

    /** This is the split-screen window ([SplitShellActivity]), not the home screen. */
    protected open val isSplitWindow: Boolean get() = false

    /** Everything the split-screen window starts opens in the other half of the screen. */
    override val launchAdjacent: Boolean get() = isSplitWindow

    private val viewModel: ShellViewModel by viewModels()

    // --- Web state (one page for both windows) -------------------------------
    private val page = ShellPage
    private val webView: WebView? get() = page.webView
    private val bridge get() = page.bridge
    private val settings: ShellSettings get() = page.settings
    override val router: UrlRouter get() = page.router
    override val certificateAlias: String? get() = settings.certificateAlias

    /** This window shows the page (the other one shows a placeholder). */
    private val showsPage: Boolean get() = page.owner === this

    /**
     * This window's place for the page in its layout: [ShellPage] moves the
     * WebView in and out of it, Compose only hosts the container.
     */
    internal lateinit var pageContainer: FrameLayout
        private set

    // --- Native overlays -----------------------------------------------------
    private var showSettings by mutableStateOf(false)
    private var showDrawer by mutableStateOf(false)
    private var editing by mutableStateOf<Pair<LauncherEntry, Boolean>?>(null)

    /** The folder open over the bar (its id, so it follows edits). */
    private var openFolderId by mutableStateOf<String?>(null)

    /** The folder editor, over everything else; sheets (drawer, open folder) wait under it. */
    private var folderEditor by mutableStateOf<FolderEditorRequest?>(null)

    /** A folder with apps in it that the user asked to remove: confirmed first. */
    private var confirmRemove by mutableStateOf<LauncherEntry.Folder?>(null)
    private var authPrompt by mutableStateOf<AuthPrompt?>(null)
    private var isDefaultHome by mutableStateOf(false)
    private var pinShortcutsSupported by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var fullScreenIntentAllowed by mutableStateOf(true)
    private var overlayAllowed by mutableStateOf(true)
    private var notificationsAllowed by mutableStateOf(true)

    /** Android lets Ara install apps, so an update needs no dialog (see UpdateRuntime). */
    private var installAllowed by mutableStateOf(true)

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioRequest: PermissionRequest? = null

    /** Health Connect's permission dialog, started from this window for a sync, is up. */
    private var pendingHealthPermissions: CompletableDeferred<Unit>? = null

    /** The page waits for a file chooser, the microphone or Health Connect permissions started from this window. */
    internal val awaitsResult: Boolean
        get() = pendingFileCallback != null || pendingAudioRequest != null || pendingHealthPermissions != null

    /** A `retinue-shell:` link waiting for the settings, to be checked against the configured origin. */
    private var pendingLink: String? = null

    /** Asked for after the mic permission (two requests at once lose the second). */
    private var askNotificationsAfterMic = false

    /**
     * An accepted wake the page has not had yet (elapsedRealtime when accepted,
     * 0 for none): it waits until the page can open the mic (see [tryDeliverWake]).
     */
    private var pendingWakeAt = 0L

    /** The pending wake has loaded the start page (once per wake) and waits for it. */
    private var wakeLoadIssued = false
    private var settingsLoaded = false
    private var resumed by mutableStateOf(false)

    /** Split-screen window: the launcher entry to start beside it once it is in front. */
    private var pendingEntry: LauncherEntry? = null

    /**
     * Split-screen window started only to be among the recent apps, on its way
     * to the back: it does not take the page (it still passes through
     * onStart and onResume), until it really comes to the front.
     */
    internal var parking = false
        private set

    private val wakeExpiry = Runnable { tryDeliverWake() }

    /**
     * An app the home screen handed over that did not open beside this
     * window (Android 12 does not split for it, some apps refuse split
     * screen) covers it full screen: this window goes to the back, so Back
     * from the app leads home, as after any launch from the home screen.
     */
    private val splitCheck = Runnable {
        if (!isInMultiWindowMode && !resumed) moveTaskToBack(true)
    }

    private val keyguardCallback = object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() {
            runOnUiThread { tryDeliverWake() }
        }

        override fun onDismissCancelled() {
            // The user did not unlock: the wake is abandoned, the shell leaves the lock screen.
            runOnUiThread { if (pendingWakeAt != 0L) endWake() }
        }

        override fun onDismissError() {
            runOnUiThread { tryDeliverWake() }
        }
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        deliverFiles(result)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        pendingAudioRequest?.let { request ->
            if (granted) grantAudio(request) else request.deny()
        }
        pendingAudioRequest = null
        WakeRuntime.refresh()
        if (askNotificationsAfterMic) {
            askNotificationsAfterMic = false
            requestNotificationsIfNeeded()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsAllowed = WakeAlert.notificationsAllowed(this)
        }

    // Health Connect's own dialog; the sync reads what was granted itself.
    private val healthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            pendingHealthPermissions?.complete(Unit)
            pendingHealthPermissions = null
        }

    /** Asks for Health Connect permissions when the page's sync needs them (see HealthSyncRuntime). */
    private val healthPermissionRequester = HealthPermissionRequester { permissions ->
        pendingHealthPermissions?.complete(Unit)
        val done = CompletableDeferred<Unit>()
        pendingHealthPermissions = done
        try {
            healthPermissions.launch(permissions)
        } catch (e: Exception) {
            // No Health Connect to ask (ActivityNotFoundException): the sync finds nothing granted.
            Log.w(TAG, "Health Connect permission request failed", e)
            pendingHealthPermissions = null
            done.complete(Unit)
        }
        done.await()
    }

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeRole()
    }

    private val phraseModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPhraseModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pageContainer = FrameLayout(this)
        page.open(this)
        refreshHomeRole()
        micGranted = hasMic()

        onBackPressedDispatcher.addCallback(this, backCallback)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.settings.filterNotNull().collect { applySettings(it) }
            }
        }
        // The installer's confirmation dialog, when an update needs one: started
        // from here while in front (a start from the background would be refused).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateRuntime.state.collect { if (it is UpdateState.AwaitingConfirmation && !it.shown) showUpdateConfirmation() }
            }
        }

        setContent {
            AraTheme {
                val launcher by viewModel.launcher.collectAsStateWithLifecycle()
                val apps by viewModel.apps.collectAsStateWithLifecycle()
                val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
                val stored by viewModel.settings.collectAsStateWithLifecycle()
                val voiceActive by WakeRuntime.voiceActive.collectAsStateWithLifecycle()
                KeepScreenOn(voiceActive)
                // A long-press offers the other way of opening: beside Ara or full screen (home screen only).
                val alternateOpen = when {
                    isSplitWindow -> null
                    stored?.splitLaunch == true -> R.string.launcher_open_full
                    else -> R.string.launcher_open_beside
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ShellLayout(launcher.entries, alternateOpen)
                    // Sheets are windows above everything here, so they step aside for the folder editor.
                    val editor = folderEditor
                    if (showDrawer && editor == null) {
                        LaunchedEffect(Unit) { viewModel.refreshApps() }
                        AppDrawer(
                            apps = apps,
                            shortcuts = shortcuts,
                            folders = launcher.folders,
                            isPinned = { launcher.containsApp(it.packageName, it.activityName) },
                            isShortcutPinned = { launcher.containsShortcut(it.packageName, it.shortcutId) },
                            onLaunch = { launchApp(it) },
                            onPin = ::pinApp,
                            onLaunchShortcut = { launchShortcut(it) },
                            alternateOpen = alternateOpen,
                            onLaunchAlternate = { item -> launchFromDrawer(item, alternate = true) },
                            onPinShortcut = ::pinShortcut,
                            onRemoveShortcut = viewModel::forget,
                            onAddToFolder = ::addToFolder,
                            onNewFolder = ::newFolder,
                            onDismiss = { showDrawer = false },
                            onSettings = {
                                showDrawer = false
                                openSettings()
                            },
                        )
                    }
                    openFolderId?.let { id ->
                        val folder = launcher.folder(id)
                        if (folder == null) {
                            // Gone (removed or reset): nothing left to show.
                            LaunchedEffect(id) { openFolderId = null }
                        } else if (editor == null) {
                            FolderSheet(
                                folder = folder,
                                onLaunch = { launchFolderApp(it) },
                                alternateOpen = alternateOpen,
                                onLaunchAlternate = { launchFolderApp(it, alternate = true) },
                                onMove = { app, delta -> viewModel.updateLauncher { it.moveInFolder(id, app.id, delta) } },
                                onRemove = { app -> viewModel.updateLauncher { it.removeFromFolder(id, app.id) } },
                                onEdit = { addApps -> folderEditor = FolderEditorRequest(folder, isNew = false, addApps = addApps) },
                                onDismiss = { openFolderId = null },
                            )
                        }
                    }
                    if (showSettings) SettingsOverlay(launcher)
                    editor?.let { request ->
                        LaunchedEffect(request) { viewModel.refreshApps() }
                        Surface(Modifier.fillMaxSize()) {
                            FolderEditor(
                                request = request,
                                apps = apps,
                                shortcuts = shortcuts,
                                onSave = { folder, newIcon ->
                                    viewModel.saveFolder(folder, request.isNew, newIcon)
                                    folderEditor = null
                                },
                                onDismiss = { folderEditor = null },
                            )
                        }
                    }
                    editing?.let { (entry, isNew) ->
                        EntryEditorDialog(
                            entry = entry,
                            isNew = isNew,
                            onSave = { saved ->
                                viewModel.updateLauncher { if (isNew) it.add(saved) else it.replace(saved) }
                                editing = null
                            },
                            onDismiss = { editing = null },
                        )
                    }
                    confirmRemove?.let { folder ->
                        FolderRemoveDialog(
                            folder = folder,
                            onConfirm = {
                                viewModel.removeEntry(folder)
                                confirmRemove = null
                            },
                            onDismiss = { confirmRemove = null },
                        )
                    }
                    authPrompt?.let { AuthDialog(it) }
                }
            }
        }

        handleIntent(intent, initial = true, restored = savedInstanceState != null)
        handleSplitIntent(intent, restored = savedInstanceState != null)
    }

    /** The split-screen window has been asked for: an entry to start beside it, or only a place in Recents. */
    private fun handleSplitIntent(intent: Intent?, restored: Boolean) {
        if (!isSplitWindow || intent == null) return
        val raw = intent.getStringExtra(SplitShellActivity.EXTRA_ENTRY)
        val park = intent.getBooleanExtra(SplitShellActivity.EXTRA_PARK, false)
        if (raw != null || park) {
            // A recreated activity must not see them again.
            setIntent(
                Intent(intent).apply {
                    removeExtra(SplitShellActivity.EXTRA_ENTRY)
                    removeExtra(SplitShellActivity.EXTRA_PARK)
                },
            )
        }
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val replayed = ShellDecisions.isReplayedIntent(restored, fromHistory)
        when (ShellDecisions.splitRequest(hasEntry = raw != null, park = park, replayed = replayed)) {
            ShellDecisions.SplitRequest.Open -> {
                parking = false
                pendingEntry = LauncherCodec.decodeEntry(raw)
                if (resumed) openPendingEntry()
            }
            // Back at once: only the Recents tile stays, and the page stays with the home screen.
            ShellDecisions.SplitRequest.Park -> parking = moveTaskToBack(true)
            ShellDecisions.SplitRequest.Show -> parking = false
        }
    }

    /**
     * Start the entry the home screen handed over, beside this window, once it
     * is in front: Android puts the app in the other half of the screen next
     * to the task it is started from, which must be this one, in front.
     */
    private fun openPendingEntry() {
        if (pendingEntry == null) return
        window.decorView.post {
            val entry = pendingEntry ?: return@post
            pendingEntry = null
            openEntry(entry)
            window.decorView.removeCallbacks(splitCheck)
            window.decorView.postDelayed(splitCheck, SPLIT_CHECK_MS)
        }
    }

    // --- Layout --------------------------------------------------------------

    /** [alternateOpen]: the long-press item for the other way of opening an app (null for none). */
    @Composable
    private fun ShellLayout(entries: List<LauncherEntry>, alternateOpen: Int?) {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().imePadding()) {
                AndroidView(
                    factory = {
                        // Only ever in one place (a recreated node gets it from the old one).
                        (pageContainer.parent as? ViewGroup)?.removeView(pageContainer)
                        pageContainer
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (page.owner === this@MainActivity) {
                    PageStatusOverlay()
                } else if (page.webView != null) {
                    PageElsewhere()
                }
            }
            // The bar hides while the keyboard is up, so the page keeps its height.
            if (!imeVisible) {
                LauncherBar(
                    entries = entries,
                    onOpen = { openEntry(it) },
                    alternateOpen = alternateOpen,
                    onOpenAlternate = { openEntry(it, alternate = true) },
                    onEdit = ::editLauncherEntry,
                    onRemove = ::removeLauncherEntry,
                    onMove = { e, delta -> viewModel.updateLauncher { it.move(e.id, delta) } },
                    onMoveToFolder = { app, folder -> viewModel.updateLauncher { it.moveIntoFolder(app.id, folder.id) } },
                    onNewFolder = { newFolder() },
                    onAllApps = { showDrawer = true },
                    onSettings = ::openSettings,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    /**
     * In place of the page while the other window shows it (both windows are
     * visible at once only in a desktop or freeform mode, or for a moment
     * while one replaces the other): a tap brings it here.
     */
    @Composable
    private fun PageElsewhere() {
        Box(
            Modifier.fillMaxSize().clickable(onClickLabel = stringResource(R.string.split_page_show_here)) { page.take(this@MainActivity) },
            contentAlignment = Alignment.Center,
        ) {
            // Only while this window is in front, so a window on its way out does not flash the text.
            if (resumed) {
                Text(
                    stringResource(R.string.split_page_elsewhere),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * Over the web area: a progress bar while loading, and in place of a page
     * that did not load, why. A page that loaded with console errors is not
     * covered; those are in the settings' diagnostics.
     */
    @Composable
    private fun BoxScope.PageStatusOverlay() {
        val state = page.state
        when (val status = state.status) {
            is PageStatus.Loading -> PageProgressBar(status.progress, Modifier.align(Alignment.TopCenter))
            is PageStatus.Failed -> PageFailureCard(
                failure = status.failure,
                url = state.url,
                certificateSelected = settings.certificateAlias != null,
                onRetry = ::retryLoad,
                onChooseCertificate = ::chooseCertificate,
                onSettings = ::openSettings,
            )
            PageStatus.Idle, PageStatus.Ready -> Unit
        }
    }

    @Composable
    private fun SettingsOverlay(launcher: os.retinue.ara.launcher.LauncherConfig) {
        val s by viewModel.settings.collectAsStateWithLifecycle()
        val models by viewModel.models.collectAsStateWithLifecycle()
        val busy by viewModel.modelBusy.collectAsStateWithLifecycle()
        val message by viewModel.modelMessage.collectAsStateWithLifecycle()
        val wakeStatus by viewModel.wakeStatus.collectAsStateWithLifecycle()
        val update by UpdateRuntime.state.collectAsStateWithLifecycle()
        Surface(Modifier.fillMaxSize()) {
            SettingsScreen(
                state = SettingsState(
                    settings = s ?: ShellSettings(),
                    launcher = launcher,
                    isDefaultHome = isDefaultHome,
                    pinShortcutsSupported = pinShortcutsSupported,
                    models = models,
                    modelBusy = busy,
                    modelMessage = message,
                    wakeStatus = wakeStatus,
                    micGranted = micGranted,
                    fullScreenIntentAllowed = fullScreenIntentAllowed,
                    overlayAllowed = overlayAllowed,
                    notificationsAllowed = notificationsAllowed,
                    page = page.state,
                    update = update,
                    installAllowed = installAllowed,
                ),
                actions = settingsActions,
            )
        }
    }

    private val settingsActions = object : SettingsActions {
        override fun close() {
            if (settings.base == null) {
                Toast.makeText(this@MainActivity, R.string.msg_base_url_needed, Toast.LENGTH_SHORT).show()
            }
            showSettings = false
        }

        override fun saveBaseUrl(url: String) {
            viewModel.setBaseUrl(url)
        }

        override fun selectCertificate() = chooseCertificate()
        override fun clearCertificate() {
            viewModel.setCertificateAlias(null)
        }

        override fun requestHomeRole() = requestHome()
        override fun testPinShortcut() = requestTestShortcut()
        override fun addEntry(template: LauncherEntry) {
            editing = template to true
        }

        override fun addFolder() = newFolder()

        override fun editEntry(entry: LauncherEntry) = editLauncherEntry(entry)

        override fun removeEntry(entry: LauncherEntry) = removeLauncherEntry(entry)

        override fun moveEntry(entry: LauncherEntry, delta: Int) {
            viewModel.updateLauncher { it.move(entry.id, delta) }
        }

        override fun resetLauncher() {
            viewModel.resetLauncher()
        }

        override fun setWakeMode(mode: WakeMode) {
            val wantsNotifications = mode == WakeMode.Always
            if (mode != WakeMode.Off && !hasMic()) {
                // One request at a time: the notification request follows the mic's answer.
                askNotificationsAfterMic = wantsNotifications
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else if (wantsNotifications) {
                requestNotificationsIfNeeded()
            }
            viewModel.setWakeMode(mode)
        }

        override fun setWakeThreshold(value: Float) {
            viewModel.setWakeThreshold(value)
        }

        override fun downloadFeatureModels() {
            viewModel.downloadFeatureModels()
        }

        override fun downloadPhraseModel(url: String) {
            viewModel.downloadPhraseModel(url)
        }

        override fun pickPhraseModel() {
            try {
                phraseModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }

        override fun openNotificationSettings() {
            try {
                startActivity(WakeAlert.notificationSettings(this@MainActivity))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }

        override fun allowOverlay() {
            try {
                startActivity(WakeAlert.overlaySettings(this@MainActivity))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }

        override fun allowFullScreenIntent() {
            val intent = WakeAlert.fullScreenIntentSettings(this@MainActivity) ?: return
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }

        override fun setAutoUpdate(on: Boolean) {
            viewModel.setAutoUpdate(on)
        }

        override fun setUpdateChannel(channel: UpdateChannel) {
            viewModel.setUpdateChannel(channel)
        }

        override fun setSplitLaunch(on: Boolean) {
            viewModel.setSplitLaunch(on)
        }

        override fun setKeepSplitInRecents(on: Boolean) {
            viewModel.setKeepSplitInRecents(on)
        }

        override fun checkForUpdates() = UpdateRuntime.checkNow()

        override fun installUpdate() = UpdateRuntime.installNow()

        override fun confirmUpdate() {
            val intent = UpdateRuntime.confirmation() ?: return
            startUpdateConfirmation(intent)
        }

        override fun cancelUpdate() = UpdateRuntime.cancelConfirmation()

        override fun allowUpdateInstalls() {
            try {
                startActivity(UpdateRuntime.installSettings(this@MainActivity))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The installer's confirmation, once per request (see UpdateRuntime.takeConfirmation). */
    private fun showUpdateConfirmation() {
        val intent = UpdateRuntime.takeConfirmation() ?: return
        startUpdateConfirmation(intent)
    }

    private fun startUpdateConfirmation(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // ActivityNotFoundException, or a SecurityException from a stale intent: never crash the shell.
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- Lifecycle -----------------------------------------------------------

    override fun onStart() {
        super.onStart()
        page.windowStarted(this)
        // The window coming to the front shows the page.
        if (!parking) page.take(this)
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        micGranted = hasMic()
        fullScreenIntentAllowed = WakeAlert.canUseFullScreenIntent(this)
        overlayAllowed = WakeAlert.canDrawOverlays(this)
        notificationsAllowed = WakeAlert.notificationsAllowed(this)
        installAllowed = UpdateRuntime.canInstall(this)
        viewModel.refreshModels()
        page.windowResumed(this)
        // Also here: the other window may have had it meanwhile (both up in a desktop mode).
        if (!parking) page.take(this)
        resumed = true
        UpdateRuntime.onShellResumed()
        HealthSyncRuntime.onShellResumed()
        tryDeliverWake()
        openPendingEntry()
        keepSplitWindowInRecents()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && parking) {
            // It did come to the front after all (a parked window never gets focus).
            parking = false
            page.take(this)
        }
        if (hasFocus) tryDeliverWake()
    }

    /** Split screen begins or ends: a waiting wake may go out now (see [ShellDecisions.pendingWake]). */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        tryDeliverWake()
    }

    override fun onPause() {
        resumed = false
        page.windowPaused(this)
        // Only a wake shows the shell over the keyguard and turns the screen on
        // (see acceptWake); a pending wake keeps waiting for the unlock.
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        super.onPause()
    }

    override fun onStop() {
        parking = false
        page.windowStopped(this)
        super.onStop()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(wakeExpiry)
        window.decorView.removeCallbacks(splitCheck)
        // A sync waiting for the permission dialog goes on with what is granted.
        pendingHealthPermissions?.complete(Unit)
        pendingHealthPermissions = null
        // The page goes to the other window, or with the last one.
        page.close(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        // Launcher3's test: focused and not just brought to front = already showing.
        val alreadyInFront = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false, alreadyInFront = alreadyInFront)
        handleSplitIntent(intent, restored = false)
    }

    private fun handleIntent(
        intent: Intent?,
        initial: Boolean,
        alreadyInFront: Boolean = false,
        restored: Boolean = false,
    ) {
        if (handleShellLink(intent, restored)) return
        val wakeAction = intent?.action == ACTION_WAKE
        val fromHistory = intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        // The activity is exported: a wake counts only with the detector's fresh
        // one-time token, and never when an old intent is replayed.
        val isWake = wakeAction &&
            !ShellDecisions.isReplayedIntent(restored, fromHistory) &&
            WakeRuntime.consumePendingWake(intent?.getStringExtra(WakeRuntime.EXTRA_WAKE_TOKEN))
        if (wakeAction && intent != null) {
            // A recreated activity must not see this wake again.
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).apply { removeExtra(WakeRuntime.EXTRA_WAKE_TOKEN) })
        }
        val isHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        when (ShellDecisions.onNewIntent(isWake, isHome, alreadyInFront && !initial)) {
            ShellDecisions.IntentAction.Wake -> acceptWake(fromIntent = true)
            ShellDecisions.IntentAction.HomeEvent -> {
                closeOverlays()
                bridge.dispatchEvent("home") { onUnhandledEvent(isWake = false) }
            }
            ShellDecisions.IntentAction.Show -> if (isHome) {
                showDrawer = false
                editing = null
                openFolderId = null
                folderEditor = null
                confirmRemove = null
            }
        }
    }

    /**
     * A `retinue-shell:` link (see [ShellLinks]): its URL loads in the page
     * once the settings are known, and only on the configured origin. Never
     * replayed by a recreated activity or one brought back from Recents.
     */
    private fun handleShellLink(intent: Intent?, restored: Boolean): Boolean {
        if (intent?.action != Intent.ACTION_VIEW || !ShellLinks.isShellLink(intent.dataString)) return false
        val link = intent.dataString
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).setData(null))
        if (ShellDecisions.isReplayedIntent(restored, fromHistory)) return true
        pendingLink = link
        if (settingsLoaded) openPendingLink()
        return true
    }

    /** Load the pending link's URL in the page, if it may load (see [takeLinkUrl]). */
    private fun openPendingLink() {
        val url = takeLinkUrl() ?: return
        closeOverlays()
        page.take(this)
        page.pageReady = false
        webView?.loadUrl(url)
    }

    /**
     * The pending link's URL when it is on the configured origin. Otherwise
     * nothing is loaded and nothing is set up: the user is told, and null.
     */
    private fun takeLinkUrl(): String? {
        val link = pendingLink ?: return null
        pendingLink = null
        return when (val target = ShellLinks.target(link, settings.base)) {
            is LinkTarget.Open -> target.url
            is LinkTarget.OtherOrigin -> {
                Toast.makeText(this, getString(R.string.link_other_origin, target.origin), Toast.LENGTH_LONG).show()
                null
            }
            LinkTarget.Malformed -> {
                Toast.makeText(this, R.string.link_malformed, Toast.LENGTH_SHORT).show()
                null
            }
        }
    }

    // --- Settings → web ------------------------------------------------------

    /**
     * Both windows read the settings; the page is shared, so the first to see
     * a change applies it to the page and the other finds nothing to do.
     */
    private fun applySettings(s: ShellSettings) {
        val aliasChanged = s.certificateAlias != settings.certificateAlias
        page.settings = s
        settingsLoaded = true
        page.router = UrlRouter(s.baseUrl)
        val base = s.base
        if (aliasChanged) WebView.clearClientCertPreferences(null)
        keepSplitWindowInRecents()
        if (base == null) {
            page.loadedBase = null
            showSettings = true
            webView?.loadUrl("about:blank")
            // A link cannot be for an origin that is not set up.
            openPendingLink()
            tryDeliverWake()
            return
        }
        if (base != page.loadedBase || aliasChanged) {
            page.loadedBase = base
            bridge.install()
            page.pageReady = false
            // A pending wake waits for this load and goes out as the event (onPageFinished).
            if (pendingWakeAt != 0L) wakeLoadIssued = true
            // A link that brought the shell up loads instead of the start page.
            webView?.loadUrl(takeLinkUrl() ?: ShellUrls.startUrl(base))
        } else {
            openPendingLink()
            tryDeliverWake()
        }
    }

    // --- Wake / home ---------------------------------------------------------

    /**
     * A wake for this shell: from the detector directly ([fromIntent] false,
     * the shell is visible) or through an intent that may have to turn the
     * screen on and get past the keyguard first. It reaches the page once the
     * page can open the mic ([tryDeliverWake]).
     */
    private fun acceptWake(fromIntent: Boolean) {
        // The page answers in this window.
        page.take(this)
        closeOverlays()
        pendingWakeAt = SystemClock.elapsedRealtime()
        WakeRuntime.wakeAccepted()
        // A wake still waiting when its window ends is dropped then, not on the next event.
        window.decorView.removeCallbacks(wakeExpiry)
        window.decorView.postDelayed(wakeExpiry, ShellDecisions.PENDING_WAKE_MAX_MS + 1)
        if (fromIntent) {
            // Show over the keyguard, turn the screen on and ask for the unlock.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, keyguardCallback)
        }
        tryDeliverWake()
    }

    /** A wake heard while this window is visible (see [ShellPage]). */
    internal fun onDirectWake() = acceptWake(fromIntent = false)

    private fun pendingWakeDecision(): ShellDecisions.PendingWake? {
        val since = pendingWakeAt
        if (since == 0L) return null
        val keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return ShellDecisions.pendingWake(
            ageMs = SystemClock.elapsedRealtime() - since,
            settingsLoaded = settingsLoaded,
            resumed = resumed,
            windowFocused = hasWindowFocus(),
            keyguardLocked = keyguardLocked,
            inMultiWindow = isInMultiWindowMode,
        )
    }

    /** Hand a pending wake to the page if it can open the mic now (see [ShellDecisions.pendingWake]). */
    private fun tryDeliverWake() {
        when (pendingWakeDecision()) {
            ShellDecisions.PendingWake.Deliver -> deliverWake()
            ShellDecisions.PendingWake.Drop -> endWake()
            ShellDecisions.PendingWake.Wait, null -> Unit
        }
    }

    /** The pending wake goes to the page (or settings) now; the caller delivers it. */
    private fun completeWake() {
        endWake()
        // Restart the hold: the page takes the mic from here.
        WakeRuntime.wakeAccepted()
    }

    /** No wake pending any more: the shell stops showing over the keyguard. */
    private fun endWake() {
        pendingWakeAt = 0L
        wakeLoadIssued = false
        setShowWhenLocked(false)
        setTurnScreenOn(false)
    }

    /**
     * A wake that may reach the page now. A page that is not loaded gets the
     * start page first; the wake stays pending and goes out as the event once
     * that page has finished loading ([onPageFinished]) — or is dropped when
     * the pending-wake window runs out.
     */
    private fun deliverWake() {
        closeOverlays()
        val base = settings.base
        val url = webView?.url
        val onOrigin = page.pageReady && router.isConfiguredOrigin(url)
        val startLoading = wakeLoadIssued || (page.pageLoading && base != null && ShellUrls.isStartPage(base, url))
        when (ShellDecisions.wakeDelivery(base != null, onOrigin, startLoading)) {
            ShellDecisions.WakeDelivery.DispatchEvent -> {
                completeWake()
                bridge.dispatchEvent("wake") { onUnhandledEvent(isWake = true) }
            }
            ShellDecisions.WakeDelivery.LoadStartPage -> if (base != null) {
                wakeLoadIssued = true
                page.pageReady = false
                webView?.loadUrl(ShellUrls.startUrl(base))
            }
            ShellDecisions.WakeDelivery.WaitForPage -> Unit
            ShellDecisions.WakeDelivery.OpenSettings -> {
                completeWake()
                showSettings = true
            }
        }
    }

    private fun loadWakePage() {
        val base = settings.base ?: return
        page.pageReady = false
        webView?.loadUrl(ShellUrls.wakeUrl(base))
    }

    /**
     * The page did not take a `wake` or `home` event: go to the web app itself.
     * A wake falls back to `?wake=1`, which the web app strips from its URL.
     */
    private fun onUnhandledEvent(isWake: Boolean) {
        val base = settings.base ?: return
        when (ShellDecisions.onUnhandledEvent(isWake, ShellUrls.isStartPage(base, webView?.url))) {
            ShellDecisions.UnhandledEvent.LoadWake -> loadWakePage()
            ShellDecisions.UnhandledEvent.LoadStart -> {
                page.pageReady = false
                webView?.loadUrl(ShellUrls.startUrl(base))
            }
            ShellDecisions.UnhandledEvent.Nothing -> Unit
        }
    }

    private fun closeOverlays() {
        showDrawer = false
        editing = null
        openFolderId = null
        folderEditor = null
        confirmRemove = null
        if (settings.base != null) showSettings = false
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val overlay = showSettings || showDrawer || editing != null || authPrompt != null ||
                openFolderId != null || folderEditor != null || confirmRemove != null
            // The split-screen window is a plain app: at the root, Back leaves it.
            val webCanGoBack = showsPage && webView?.canGoBack() == true
            when (ShellDecisions.onBack(overlay, webCanGoBack, isHome = isDefaultHome && !isSplitWindow)) {
                ShellDecisions.BackAction.CloseOverlay -> when {
                    authPrompt != null -> authPrompt?.cancel()
                    confirmRemove != null -> confirmRemove = null
                    editing != null -> editing = null
                    folderEditor != null -> folderEditor = null
                    openFolderId != null -> openFolderId = null
                    showDrawer -> showDrawer = false
                    else -> settingsActions.close()
                }
                ShellDecisions.BackAction.WebBack -> webView?.goBack()
                ShellDecisions.BackAction.Ignore -> Unit
                // Keep the WebView (and its page) alive rather than finishing.
                ShellDecisions.BackAction.Leave -> moveTaskToBack(true)
            }
        }
    }

    private fun openSettings() {
        showSettings = true
    }

    /** The failure card's Retry: load the start page afresh. */
    private fun retryLoad() {
        val base = settings.base ?: return openSettings()
        val url = ShellUrls.startUrl(base)
        updatePage { onLoadRequested(url) }
        page.pageReady = false
        webView?.loadUrl(url)
    }

    /** Apply [change] to the shared page's status tracker (see [ShellPage.updateState]). */
    private fun updatePage(change: PageStatusTracker.() -> Unit) = page.updateState(change)

    // --- Launcher ------------------------------------------------------------

    /**
     * Open a launcher entry: a folder over the bar, a page of the web app in
     * the WebView, anything else through Android — here, beside this window,
     * or through the split-screen window ([ShellDecisions.launchTarget]).
     * [alternate] is the long-press choice, the other way of opening it.
     */
    private fun openEntry(entry: LauncherEntry, alternate: Boolean = false) {
        if (entry is LauncherEntry.Folder) {
            openFolderId = entry.id
            return
        }
        if (entry is LauncherEntry.WebPage) {
            val base = settings.base ?: return openSettings()
            page.take(this)
            webView?.loadUrl(ShellUrls.webPathUrl(base, entry.path))
            return
        }
        val target = ShellDecisions.launchTarget(isSplitWindow, settings.splitLaunch, alternate)
        if (target == ShellDecisions.LaunchTarget.ThroughSplitWindow) {
            openInSplitWindow(entry)
            return
        }
        // Here or beside this window: ShellIntents asks this window (SplitAware).
        when (val r = ShellIntents.launch(this, entry, router)) {
            is Route.Load -> {
                page.take(this)
                webView?.loadUrl(r.url)
            }
            is Route.Browser -> ShellIntents.openInBrowser(this, r.url)
            else -> Unit
        }
    }

    /**
     * From the home screen: bring up the split-screen window, which starts
     * [entry] beside itself once it is in front (see [handleSplitIntent]).
     */
    private fun openInSplitWindow(entry: LauncherEntry) {
        try {
            startActivity(SplitShellActivity.openIntent(this, entry))
        } catch (e: Exception) {
            // Never lose the tap: open it the usual way.
            Log.w(TAG, "split-screen window did not start", e)
            when (val r = ShellIntents.launch(this, entry, router)) {
                is Route.Load -> webView?.loadUrl(r.url)
                is Route.Browser -> ShellIntents.openInBrowser(this, r.url)
                else -> Unit
            }
        }
    }

    /**
     * The home screen keeps the split-screen window among the recent apps when
     * the setting asks for it: if it is not there (never opened, or swiped
     * away), it is started straight to the back ([SplitShellActivity.parkIntent]).
     */
    private fun keepSplitWindowInRecents() {
        if (isSplitWindow || !resumed || !settingsLoaded || !settings.keepSplitInRecents) return
        // Never in the middle of a wake or a conversation (the start pauses this window, which ends
        // showing over the keyguard), nor over the keyguard: a later, quiet resume does it.
        val keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (pendingWakeAt != 0L || keyguardLocked || WakeRuntime.voiceActive.value) return
        val now = SystemClock.elapsedRealtime()
        val since = SplitShellActivity.lastParkAt?.let { now - it }
        if (!ShellDecisions.parkSplitWindow(true, SplitShellActivity.inRecents(this), since)) return
        SplitShellActivity.lastParkAt = now
        // A new task always gets a splash screen; without the icon it is a plain flash of the background.
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        }
        try {
            startActivity(SplitShellActivity.parkIntent(this), options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "split-screen window could not be put among the recent apps", e)
        }
    }

    /** Remove a bar entry; a folder with apps in it only after a confirmation. */
    private fun removeLauncherEntry(entry: LauncherEntry) {
        if (entry is LauncherEntry.Folder && entry.apps.isNotEmpty()) {
            confirmRemove = entry
        } else {
            viewModel.removeEntry(entry)
        }
    }

    /** Edit a bar entry: a folder in the folder editor, anything else in the entry dialog. */
    private fun editLauncherEntry(entry: LauncherEntry) {
        if (entry is LauncherEntry.Folder) {
            folderEditor = FolderEditorRequest(entry, isNew = false)
        } else {
            editing = entry to false
        }
    }

    /** Open the folder editor on a new folder; made from All apps, it starts with [item] (an app or web app) in it. */
    private fun newFolder(item: LauncherEntry.Launchable? = null) {
        showDrawer = false
        val folder = LauncherEntry.Folder(ShellViewModel.newId(), label = "", apps = listOfNotNull(item))
        folderEditor = FolderEditorRequest(folder, isNew = true)
    }

    private fun launchApp(app: AppInfo) {
        showDrawer = false
        openEntry(app.toEntry())
    }

    private fun launchShortcut(shortcut: PinnedShortcut) {
        showDrawer = false
        openEntry(shortcut.toEntry())
    }

    /** All apps' long-press choice: the other way of opening an app or web app. */
    private fun launchFromDrawer(item: LauncherEntry.Launchable, alternate: Boolean) {
        showDrawer = false
        openEntry(item.asEntry(), alternate)
    }

    private fun pinShortcut(shortcut: PinnedShortcut) {
        viewModel.pin(shortcut)
        Toast.makeText(this, getString(R.string.drawer_pinned, shortcut.label), Toast.LENGTH_SHORT).show()
    }

    private fun launchFolderApp(item: LauncherEntry.Launchable, alternate: Boolean = false) {
        openFolderId = null
        openEntry(item.asEntry(), alternate)
    }

    private fun LauncherEntry.Launchable.asEntry(): LauncherEntry = when (this) {
        is LauncherEntry.App -> this
        is LauncherEntry.Shortcut -> this
    }

    private fun pinApp(app: AppInfo) {
        // Pinning an app already on the bar changes nothing: no "pinned" toast for it.
        val wasPinned = viewModel.launcher.value.containsApp(app.packageName, app.activityName)
        viewModel.pin(app)
        if (!wasPinned) Toast.makeText(this, getString(R.string.drawer_pinned, app.label), Toast.LENGTH_SHORT).show()
    }

    private fun addToFolder(item: LauncherEntry.Launchable, folder: LauncherEntry.Folder) {
        viewModel.addToFolder(item, folder.id)
        val name = folder.label.ifBlank { getString(folder.kind.nameRes) }
        Toast.makeText(this, getString(R.string.drawer_added_to_folder, item.label, name), Toast.LENGTH_SHORT).show()
    }

    // --- Home role -----------------------------------------------------------

    private fun refreshHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        isDefaultHome = rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && rm.isRoleHeld(RoleManager.ROLE_HOME)
        pinShortcutsSupported = getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true
    }

    /**
     * Pin a shortcut to Ara's own start page through ShortcutManager, as a
     * browser installing a web app does: when Android routes the request,
     * Ara's confirm dialog appears; when it refuses, the toast says so.
     */
    private fun requestTestShortcut() {
        val sm = getSystemService(ShortcutManager::class.java)
        val info = ShortcutInfo.Builder(this, TEST_SHORTCUT_ID)
            .setShortLabel(getString(R.string.settings_pin_test_label))
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        val sent = try {
            sm != null && sm.isRequestPinShortcutSupported && sm.requestPinShortcut(info, null)
        } catch (e: IllegalStateException) {
            false
        }
        if (!sent) Toast.makeText(this, R.string.settings_pin_refused, Toast.LENGTH_LONG).show()
    }

    /** Ask to become the default home app (RoleManager), or open the system's home settings. */
    private fun requestHome() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                homeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            } catch (_: ActivityNotFoundException) {
                // fall through to the settings page
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Certificate ---------------------------------------------------------

    private fun chooseCertificate() {
        val host = router.origin
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias -> runOnUiThread { if (alias != null) viewModel.setCertificateAlias(alias) } },
            null, // any key type
            null, // any issuer
            host?.host,
            host?.port ?: -1,
            settings.certificateAlias,
        )
    }

    // --- WebView -------------------------------------------------------------

    /** A message from the page, to the window showing it (see [ShellPage]). */
    internal fun onWebMessage(message: WebMessage) {
        when (message) {
            is WebMessage.VoiceActive -> WakeRuntime.setVoiceActive(message.active)
            WebMessage.OpenSettings -> openSettings()
            is WebMessage.Speak, WebMessage.StopSpeaking, WebMessage.Voices -> page.speech?.handle(message)
            WebMessage.HealthSync -> HealthSyncRuntime.syncNow(
                HealthSyncLogic.triggerFor(webView?.url, settings.base),
                healthPermissionRequester,
            )
            is WebMessage.HealthSyncInterval -> HealthSyncRuntime.setInterval(message.minutes)
            is WebMessage.HealthAutoSync -> HealthSyncRuntime.setAutoSync(message.on)
        }
    }

    /**
     * The other window took the page: what the page asked of this window is
     * answered now, as it would never be otherwise. A sign-in dialog is
     * cancelled (the page offers a retry there), a file chooser comes back
     * empty (the page takes no new one while one is open), a microphone
     * request is denied (the page may ask again).
     */
    internal fun onPageLeft() {
        authPrompt?.cancel()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingAudioRequest?.deny()
        pendingAudioRequest = null
    }

    // --- ShellHost -----------------------------------------------------------

    override fun onPageStarted(url: String?) {
        page.pageReady = false
        page.pageLoading = true
        // A new document has no microphone open: never stay paused for a page that left.
        WakeRuntime.setVoiceActive(false)
        page.speech?.reset()
        bridge.onPageStarted(url)
        updatePage { onPageStarted(url) }
    }

    override fun onPageFinished(url: String?) {
        page.pageReady = router.isConfiguredOrigin(url)
        page.pageLoading = false
        bridge.onPageFinished(url)
        updatePage { onPageFinished(url) }
        // A wake waiting for this page goes out now.
        if (pendingWakeAt != 0L) tryDeliverWake()
    }


    override fun onHttpAuth(host: String, realm: String, handler: HttpAuthHandler) {
        authPrompt?.cancel(byUser = false)
        authPrompt = AuthPrompt(host, realm, handler)
    }

    override fun onPageFailed(failure: PageFailure) {
        updatePage { onMainFrameFailure(failure) }
    }

    override fun onPageProgress(progress: Int) {
        updatePage { onProgress(progress) }
    }

    override fun onConsoleError(message: String, source: String, line: Int) {
        updatePage { onConsoleError(message, source, line) }
    }

    override fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        val intent = params.createIntent()
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try {
            fileChooser.launch(Intent.createChooser(intent, params.title))
            true
        } catch (_: ActivityNotFoundException) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            true
        }
    }

    private fun deliverFiles(result: ActivityResult) {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        val data = result.data
        val clip = data?.clipData
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        }
        callback.onReceiveValue(uris)
    }

    override fun onAudioPermission(request: PermissionRequest) {
        if (hasMic()) {
            grantAudio(request)
        } else {
            pendingAudioRequest?.deny()
            pendingAudioRequest = request
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Close the wake mic first: the page is about to open its own. */
    private fun grantAudio(request: PermissionRequest) {
        WakeRuntime.pageMicGranted()
        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
    }

    override fun onRenderProcessGone() {
        page.replaceWebView()
        bridge.install()
        page.pageReady = false
        page.pageLoading = false
        settings.base?.let {
            val url = ShellUrls.startUrl(it)
            updatePage { onLoadRequested(url) }
            webView?.loadUrl(url)
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // --- HTTP auth -----------------------------------------------------------

    private inner class AuthPrompt(val host: String, val realm: String, private val handler: HttpAuthHandler) {
        fun proceed(user: String, password: String) {
            handler.proceed(user, password)
            authPrompt = null
        }

        /**
         * [byUser]: the user dismissed the dialog, which leaves the page blank
         * (the 401 body), so the failure card offers a retry. A prompt replaced
         * by a newer one is not a failure.
         */
        fun cancel(byUser: Boolean = true) {
            handler.cancel()
            authPrompt = null
            if (byUser) updatePage { onSignInCancelled() }
        }
    }

    @Composable
    private fun AuthDialog(prompt: AuthPrompt) {
        var user by remember(prompt) { mutableStateOf("") }
        var password by remember(prompt) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { prompt.cancel() },
            title = { Text(stringResource(R.string.auth_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.auth_body, prompt.host, prompt.realm))
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text(stringResource(R.string.auth_user)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.auth_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { prompt.proceed(user, password) }) { Text(stringResource(R.string.auth_sign_in)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
