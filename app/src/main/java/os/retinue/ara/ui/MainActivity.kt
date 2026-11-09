package os.retinue.ara.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.retinue.ara.R
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.data.credentials
import os.retinue.ara.health.HealthPermissionRequester
import os.retinue.ara.health.HealthSyncEvent
import os.retinue.ara.health.HealthSyncLogic
import os.retinue.ara.health.HealthSyncRuntime
import os.retinue.ara.health.HealthTrigger
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.AppUsage
import os.retinue.ara.launcher.LauncherCodec
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import os.retinue.ara.launcher.TrayLogic
import os.retinue.ara.net.BasicCredentials
import os.retinue.ara.net.CertificateImport
import os.retinue.ara.net.CertificateInstaller
import os.retinue.ara.settings.AppPage
import os.retinue.ara.settings.SettingsAction
import os.retinue.ara.settings.SettingsMessages
import os.retinue.ara.settings.SettingsPageHost
import os.retinue.ara.settings.SettingsWebView
import os.retinue.ara.shell.LinkTarget
import os.retinue.ara.shell.PageFailure
import os.retinue.ara.shell.PageStatus
import os.retinue.ara.shell.PageStatusTracker
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.shell.ShellLinks
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.shell.WebMessage
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
 * launcher bar below it, and the settings page on top when asked for (the
 * app's own page, in a WebView of its own: [SettingsWebView]). Also the home
 * activity when the user makes the shell their default home app.
 *
 * The same shell runs in a second window for split screen
 * ([SplitShellActivity], [isSplitWindow]); the page itself is shared between
 * the two (see [ShellPage]) and shown by the one in front.
 */
open class MainActivity : ComponentActivity(), ShellHost, SplitAware, SettingsPageHost {

    companion object {
        /**
         * Started by the wake detector: bring the shell up and dispatch `wake`.
         * Honoured only with the detector's one-time token
         * ([WakeRuntime.EXTRA_WAKE_TOKEN]): the activity is exported.
         */
        const val ACTION_WAKE = "os.retinue.ara.action.WAKE"

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
    override fun storedCredentials(): BasicCredentials? = settings.current?.credentials()

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

    /** The bar pulled up into a tray of more apps (see [LauncherTray]). */
    private val tray = TrayState()

    /**
     * Where the web app's top menu ends, in px below the top of the page, as
     * the page measured it when the tray last came up; null when unknown.
     */
    private var topMenuPx by mutableStateOf<Float?>(null)

    /** The folder editor, over everything else; sheets (drawer, open folder) wait under it. */
    private var folderEditor by mutableStateOf<FolderEditorRequest?>(null)

    /** A folder with apps in it that the user asked to remove: confirmed first. */
    private var confirmRemove by mutableStateOf<LauncherEntry.Folder?>(null)
    private var authPrompt by mutableStateOf<AuthPrompt?>(null)

    /** The launcher bar's editor, over the settings page (its Launcher bar card opens it). */
    private var showLauncherEditor by mutableStateOf(false)

    /** The settings page while it is up ([SettingsPageOverlay]); null while the settings are closed. */
    private var settingsPage: SettingsWebView? = null

    /** Where the settings page opens: at the top, at an endpoint's editor, or at a new one ([AppPage.startUrl]). */
    private var settingsStart = AppPage.START_URL

    /** Counts the settings pages whose renderer went away: each is replaced by a fresh one. */
    private var settingsGeneration by mutableIntStateOf(0)

    /** A `retinue-shell:` link for another configured endpoint, waiting for the user to agree to the switch. */
    private var linkSwitch by mutableStateOf<LinkSwitch?>(null)

    /** The settings page opened at a new endpoint once in this window, on first run (a dismissal is not nagged about). */
    private var firstRunEditorShown = false
    private var isDefaultHome by mutableStateOf(false)
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

    /**
     * The instance the pending wake is for, when its phrase was heard while
     * another was shown: the shell switches to it, and the wake waits until
     * the switch has reached this window. Null: the instance shown.
     */
    private var pendingWakeEndpoint: String? = null
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

    /** The settings page's certificate import under way (one at a time; see [importCertificateFor]). */
    private var certificateImport: CertificateImportRun? = null

    private val certificateFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onCertificateFile(uri)
    }

    private val certificateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onCertificateInstalled(result.resultCode == RESULT_OK)
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
                val trayItems by viewModel.trayItems.collectAsStateWithLifecycle()
                val stored by viewModel.settings.collectAsStateWithLifecycle()
                val voiceActive by WakeRuntime.voiceActive.collectAsStateWithLifecycle()
                KeepScreenOn(voiceActive)
                // A long-press offers the other way of opening: beside Ara or full screen (home screen only).
                val alternateOpen = when {
                    isSplitWindow -> null
                    stored?.splitLaunch == true -> R.string.launcher_open_full
                    else -> R.string.launcher_open_beside
                }
                // The tray needs the apps before it is first pulled up (it only comes up as far as they go).
                LaunchedEffect(Unit) { viewModel.refreshApps() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ShellLayout(launcher, trayItems, alternateOpen)
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
                    // A fresh page in place of one whose renderer went away (onSettingsPageGone).
                    if (showSettings) key(settingsGeneration) { SettingsPageOverlay() }
                    if (showLauncherEditor) {
                        Surface(Modifier.fillMaxSize()) {
                            LauncherEditorScreen(
                                entries = launcher.entries,
                                onClose = { showLauncherEditor = false },
                                onAdd = { template -> editing = template to true },
                                onAddFolder = { newFolder() },
                                onEdit = ::editLauncherEntry,
                                onRemove = ::removeLauncherEntry,
                                onMove = { entry, delta -> viewModel.updateLauncher { it.move(entry.id, delta) } },
                                onReset = { viewModel.resetLauncher() },
                            )
                        }
                    }
                    linkSwitch?.let { request ->
                        LinkSwitchDialog(
                            label = request.label,
                            onConfirm = { switchForLink(request) },
                            onDismiss = { linkSwitch = null },
                        )
                    }
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

    /**
     * The page, and over its bottom the launcher bar, which pulls up into the
     * tray ([LauncherTray]) of [trayItems]. [alternateOpen]: the long-press
     * item for the other way of opening an app (null for none).
     */
    @Composable
    private fun ShellLayout(launcher: LauncherConfig, trayItems: List<DrawerItem>, alternateOpen: Int?) {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // The bar hides while the keyboard is up, and the tray with it.
        LaunchedEffect(imeVisible) { if (imeVisible) tray.snapCollapsed() }
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.fillMaxSize()) {
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
                // The bar's place, so the page keeps its height while the keyboard is down;
                // the tray, pulled up, goes over the page instead of shrinking it.
                if (!imeVisible) {
                    Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(LauncherBarHeight))
                }
            }
            if (!imeVisible) {
                LauncherTray(
                    state = tray,
                    items = trayItems,
                    folders = launcher.folders,
                    topMenuPx = topMenuPx,
                    onOpened = ::onTrayOpened,
                    onLaunch = { openEntry(it.toEntry().asEntry()) },
                    alternateOpen = alternateOpen,
                    onLaunchAlternate = { openEntry(it.toEntry().asEntry(), alternate = true) },
                    onPin = { item ->
                        when (item) {
                            is DrawerItem.App -> pinApp(item.app)
                            is DrawerItem.Shortcut -> pinShortcut(item.shortcut)
                        }
                    },
                    onAddToFolder = ::addToFolder,
                    onNewFolder = ::newFolder,
                    onRemoveShortcut = viewModel::forget,
                    // Between the system bars: the tray stops below the page's top menu.
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                ) {
                    LauncherBar(
                        entries = launcher.entries,
                        onOpen = { openEntry(it) },
                        alternateOpen = alternateOpen,
                        onOpenAlternate = { openEntry(it, alternate = true) },
                        onEdit = ::editLauncherEntry,
                        onRemove = ::removeLauncherEntry,
                        onMove = { e, delta -> viewModel.updateLauncher { it.move(e.id, delta) } },
                        onMoveToFolder = { app, folder -> viewModel.updateLauncher { it.moveIntoFolder(app.id, folder.id) } },
                        onNewFolder = { newFolder() },
                        onAllApps = {
                            tray.collapse()
                            showDrawer = true
                        },
                        onSettings = ::openSettings,
                    )
                }
            }
        }
    }

    /**
     * The tray starts to come up: the apps are read again (one may have been
     * installed), so is Android's usage, and the page says where its top menu
     * ends, so the tray stops below it.
     */
    private fun onTrayOpened() {
        viewModel.refreshApps()
        viewModel.refreshUsage()
        measureTopMenu()
    }

    /** Ask the page where its top menu ends ([TrayLogic.TOP_MENU_SCRIPT]); only the web app's own pages. */
    private fun measureTopMenu() {
        val view = webView
        if (!showsPage || view == null || !router.isConfiguredOrigin(view.url)) {
            topMenuPx = null
            return
        }
        view.evaluateJavascript(TrayLogic.TOP_MENU_SCRIPT) { result -> topMenuPx = TrayLogic.topMenuPx(result) }
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
                onSwitchEndpoint = if (settings.endpoints.size > 1) ({ openSettings() }) else null,
            )
            PageStatus.Idle, PageStatus.Ready -> Unit
        }
    }

    /**
     * The settings page, over everything (see [SettingsWebView]). Its state is
     * everything read here, sent whole whenever any of it changes; the bridge
     * drops a state it has sent already.
     */
    @Composable
    private fun SettingsPageOverlay() {
        val stored by viewModel.settings.collectAsStateWithLifecycle()
        val models by viewModel.models.collectAsStateWithLifecycle()
        val modelBusy by viewModel.modelBusy.collectAsStateWithLifecycle()
        val modelMessage by viewModel.modelMessage.collectAsStateWithLifecycle()
        val wakeStatus by viewModel.wakeStatus.collectAsStateWithLifecycle()
        val wakeListening by WakeRuntime.listening.collectAsStateWithLifecycle()
        val update by UpdateRuntime.state.collectAsStateWithLifecycle()
        val health by HealthSyncRuntime.status.collectAsStateWithLifecycle()
        val usageAccess by viewModel.usageAccess.collectAsStateWithLifecycle()
        val state = settingsPageState(
            context = LocalContext.current,
            settings = stored ?: ShellSettings(),
            page = page.state,
            health = health,
            isDefaultHome = isDefaultHome,
            wake = WakeConditions(
                status = wakeStatus,
                micGranted = micGranted,
                notificationsAllowed = notificationsAllowed,
                fullScreenAllowed = fullScreenIntentAllowed,
                overlayAllowed = overlayAllowed,
                models = models,
                modelBusy = modelBusy,
                modelMessage = modelMessage,
                listening = wakeListening,
            ),
            update = update,
            installAllowed = installAllowed,
            usageAccess = usageAccess,
        )
        val message = SettingsMessages.state(state.toJson())
        val view = remember { SettingsWebView(this@MainActivity, this@MainActivity) }
        DisposableEffect(view) {
            settingsPage = view
            onDispose {
                if (settingsPage === view) settingsPage = null
                view.destroy()
            }
        }
        SideEffect { view.show(message, state.current?.origin, state.current?.frameKey, settingsStart) }
        Surface(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    (view.view.parent as? ViewGroup)?.removeView(view.view)
                    view.view
                },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }

    /** What the user did on the settings page (see [SettingsMessages]). */
    override fun onSettingsAction(action: SettingsAction) {
        when (action) {
            // The bridge answers a hello with the page's state by itself.
            SettingsAction.Hello -> Unit
            SettingsAction.Close -> closeSettings()
            SettingsAction.Retry -> {
                closeSettings()
                retryLoad()
            }
            is SettingsAction.Open -> if (router.isConfiguredOrigin(action.url)) openInDashboard(action.url)
            is SettingsAction.SelectEndpoint -> viewModel.selectEndpoint(action.id)
            is SettingsAction.SaveEndpoint -> saveEndpoint(action)
            is SettingsAction.RemoveEndpoint -> viewModel.removeEndpoint(action.id)
            is SettingsAction.ChooseCertificate -> chooseCertificateFor(action)
            is SettingsAction.ImportCertificate -> importCertificateFor(action)
            is SettingsAction.HealthUpload -> settings.current?.let { viewModel.setHealthUpload(it.id, action.on) }
            SettingsAction.HealthSync -> HealthSyncRuntime.syncNow(HealthTrigger.Manual, healthPermissionRequester)
            is SettingsAction.HealthAuto -> HealthSyncRuntime.setAutoSync(action.on)
            is SettingsAction.HealthInterval -> HealthSyncRuntime.setInterval(action.minutes)
            SettingsAction.HomeRequest -> requestHome()
            is SettingsAction.SplitLaunch -> viewModel.setSplitLaunch(action.on)
            is SettingsAction.SplitRecents -> viewModel.setKeepSplitInRecents(action.on)
            SettingsAction.LauncherEdit -> showLauncherEditor = true
            SettingsAction.LauncherUsageAccess -> AppUsage.openAccessSettings(this)
            is SettingsAction.WakeModeSet -> setWakeMode(action.mode)
            // The slider's range.
            is SettingsAction.WakeThreshold -> settings.current?.let { viewModel.setWakeThreshold(it.id, action.value.coerceIn(0.1f, 0.9f)) }
            SettingsAction.WakeFeatures -> viewModel.downloadFeatureModels()
            is SettingsAction.WakePhrase -> viewModel.downloadPhraseModel(action.url)
            SettingsAction.WakePick -> pickPhraseModel()
            SettingsAction.WakeFullScreen -> WakeAlert.fullScreenIntentSettings(this)?.let(::startSystemPage)
            SettingsAction.WakeOverlay -> startSystemPage(WakeAlert.overlaySettings(this))
            SettingsAction.WakeNotifications -> startSystemPage(WakeAlert.notificationSettings(this))
            is SettingsAction.UpdateAuto -> viewModel.setAutoUpdate(action.on)
            is SettingsAction.UpdateChannelSet -> viewModel.setUpdateChannel(action.channel)
            SettingsAction.UpdateCheck -> UpdateRuntime.checkNow()
            SettingsAction.UpdateInstall -> UpdateRuntime.installNow()
            SettingsAction.UpdateConfirm -> UpdateRuntime.confirmation()?.let(::startUpdateConfirmation)
            SettingsAction.UpdateCancel -> UpdateRuntime.cancelConfirmation()
            SettingsAction.UpdateAllowInstalls -> startSystemPage(UpdateRuntime.installSettings(this))
            is SettingsAction.DeviceSet -> viewModel.setDeviceWebSetting(action.key, action.value)
        }
    }

    /** A page of the dashboard, from the settings page (its instance section, or its way into the dashboard). */
    override fun openInDashboard(url: String) {
        showLauncherEditor = false
        showSettings = false
        page.take(this)
        page.pageReady = false
        webView?.loadUrl(url)
    }

    /** The settings page's renderer is gone: a fresh page takes its place, at the top. */
    override fun onSettingsPageGone() {
        settingsStart = AppPage.START_URL
        settingsGeneration++
    }

    /** A health sync's events, for the settings page while it is up (see [ShellPage]). */
    internal fun onHealthSyncEvent(event: HealthSyncEvent) {
        settingsPage?.bridge?.send(SettingsMessages.health(event.detail()))
    }

    /**
     * The editor's save, checked again here (what is stored already, such as
     * a password, is the app's, never the page's); the page is told whether it
     * is saved, and keeps the editor open with the reason when it is not.
     */
    private fun saveEndpoint(action: SettingsAction.SaveEndpoint) {
        val existing = settings.endpoints.firstOrNull { it.id == action.draft.id }
        val draft = SettingsMessages.toEndpointDraft(action.draft, existing)
        val problem = draft.problem
        if (problem == null) viewModel.saveEndpoint(draft)
        settingsPage?.bridge?.send(SettingsMessages.saved(action.request, problem))
    }

    /** The microphone first (and notifications after it, for Always), then the mode. */
    private fun setWakeMode(mode: WakeMode) {
        val wantsNotifications = mode == WakeMode.Always
        if (mode != WakeMode.Off && !hasMic()) {
            // One request at a time: the notification request follows the mic's answer.
            askNotificationsAfterMic = wantsNotifications
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (wantsNotifications) {
            requestNotificationsIfNeeded()
        }
        // The wake card is the shown instance's: its phrase, on this phone.
        settings.current?.let { viewModel.setWakeMode(it.id, mode) }
    }

    private fun pickPhraseModel() {
        try {
            phraseModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** One of Android's settings pages (notifications, overlays, installs); a phone without it is told so. */
    private fun startSystemPage(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
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
        // Back from an app, maybe one opened elsewhere: the tray's order (and usage access) may have changed.
        viewModel.refreshUsage()
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
        // Back on screen, the home screen shows the bar again, not a tray left open.
        tray.snapCollapsed()
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
        val taken = if (wakeAction && !ShellDecisions.isReplayedIntent(restored, fromHistory)) {
            WakeRuntime.takePendingWake(intent?.getStringExtra(WakeRuntime.EXTRA_WAKE_TOKEN))
        } else {
            null
        }
        val isWake = taken != null
        if (wakeAction && intent != null) {
            // A recreated activity must not see this wake again.
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).apply { removeExtra(WakeRuntime.EXTRA_WAKE_TOKEN) })
        }
        val isHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        when (ShellDecisions.onNewIntent(isWake, isHome, alreadyInFront && !initial)) {
            ShellDecisions.IntentAction.Wake -> acceptWake(fromIntent = true, endpointId = taken?.endpointId)
            ShellDecisions.IntentAction.HomeEvent -> {
                closeOverlays()
                bridge.dispatchEvent("home") { onUnhandledEvent(isWake = false) }
            }
            ShellDecisions.IntentAction.Show -> if (isHome) {
                tray.snapCollapsed()
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
     * The pending link's URL when it is on the configured origin. For another
     * configured endpoint the user is asked whether to switch ([LinkSwitchDialog]);
     * the link is kept and loads once the switch has been made. Otherwise
     * nothing is loaded and nothing is set up: the user is told, and null.
     */
    private fun takeLinkUrl(): String? {
        val link = pendingLink ?: return null
        pendingLink = null
        val current = settings.current
        val others = settings.endpoints.filter { it.id != current?.id }.associate { it.id to it.baseUrl }
        return when (val target = ShellLinks.target(link, settings.base, others)) {
            is LinkTarget.Open -> target.url
            is LinkTarget.OtherEndpoint -> {
                settings.endpoints.firstOrNull { it.id == target.endpointId }?.let { endpoint ->
                    linkSwitch = LinkSwitch(endpoint.id, EndpointLabels.label(endpoint, settings.endpoints), link)
                }
                null
            }
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
        val deviceChanged = s.deviceWebSettings != settings.deviceWebSettings
        page.settings = s
        settingsLoaded = true
        page.router = UrlRouter(s.baseUrl)
        val base = s.base
        if (aliasChanged) WebView.clearClientCertPreferences(null)
        keepSplitWindowInRecents()
        // The endpoint list the page sees: baked into the bridge script on every
        // install, and pushed to a page already on screen when it changed.
        val labels = page.endpointLabels()
        val labelsChanged = labels != page.pushedEndpoints
        page.pushedEndpoints = labels
        if (base == null) {
            page.loadedBase = null
            page.loadedEndpointId = null
            // First run: the settings page opens at a new endpoint, once.
            val firstRun = s.endpoints.isEmpty() && !firstRunEditorShown && !isSplitWindow
            if (firstRun) firstRunEditorShown = true
            if (!showSettings) settingsStart = if (firstRun) AppPage.startUrl(add = true) else AppPage.START_URL
            showSettings = true
            webView?.loadUrl("about:blank")
            // A link cannot be for an origin that is not set up.
            openPendingLink()
            tryDeliverWake()
            return
        }
        val endpointId = s.current?.id
        if (base != page.loadedBase || aliasChanged || endpointId != page.loadedEndpointId) {
            page.loadedBase = base
            page.loadedEndpointId = endpointId
            bridge.install()
            page.pageReady = false
            // A pending wake waits for this load and goes out as the event (onPageFinished).
            if (pendingWakeAt != 0L) wakeLoadIssued = true
            // A link that brought the shell up loads instead of the start page.
            webView?.loadUrl(takeLinkUrl() ?: ShellUrls.startUrl(base))
        } else {
            if (labelsChanged) bridge.endpointsChanged(labels)
            if (deviceChanged) bridge.deviceSettingsChanged(s.deviceWebSettings)
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
    private fun acceptWake(fromIntent: Boolean, endpointId: String?) {
        // A wake for an instance that was removed meanwhile is dropped: it must
        // not open the page of the instance shown instead.
        if (endpointId != null && settings.endpoints.none { it.id == endpointId }) return
        // The page answers in this window.
        page.take(this)
        closeOverlays()
        pendingWakeAt = SystemClock.elapsedRealtime()
        // A phrase of an instance working in the background brings that one to
        // the front; the wake goes to its page once that has loaded.
        pendingWakeEndpoint = endpointId
        if (endpointId != null && endpointId != settings.current?.id) viewModel.selectEndpoint(endpointId)
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

    /** A wake for the instance [endpointId], heard while this window is visible (see [ShellPage]). */
    internal fun onDirectWake(endpointId: String) = acceptWake(fromIntent = false, endpointId = endpointId)

    private fun pendingWakeDecision(): ShellDecisions.PendingWake? {
        val since = pendingWakeAt
        if (since == 0L) return null
        val keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return ShellDecisions.pendingWake(
            ageMs = SystemClock.elapsedRealtime() - since,
            // A wake for another instance waits until the switch to it has arrived.
            settingsLoaded = settingsLoaded && ShellDecisions.wakeEndpointShown(pendingWakeEndpoint, settings.current?.id),
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
        pendingWakeEndpoint = null
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
                openSettings()
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
        tray.collapse()
        showDrawer = false
        editing = null
        openFolderId = null
        folderEditor = null
        confirmRemove = null
        showLauncherEditor = false
        if (settings.base != null) showSettings = false
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val overlay = showSettings || showLauncherEditor || showDrawer || editing != null || authPrompt != null ||
                openFolderId != null || folderEditor != null || confirmRemove != null || linkSwitch != null || tray.isOpen
            // The split-screen window is a plain app: at the root, Back leaves it.
            val webCanGoBack = showsPage && webView?.canGoBack() == true
            when (ShellDecisions.onBack(overlay, webCanGoBack, isHome = isDefaultHome && !isSplitWindow)) {
                ShellDecisions.BackAction.CloseOverlay -> when {
                    authPrompt != null -> authPrompt?.cancel()
                    linkSwitch != null -> linkSwitch = null
                    confirmRemove != null -> confirmRemove = null
                    editing != null -> editing = null
                    folderEditor != null -> folderEditor = null
                    showLauncherEditor -> showLauncherEditor = false
                    openFolderId != null -> openFolderId = null
                    showDrawer -> showDrawer = false
                    tray.isOpen && !showSettings -> tray.collapse()
                    // The page takes Back first (its dialog, its editor), then the settings close.
                    else -> settingsPage?.bridge?.back { closeSettings() } ?: closeSettings()
                }
                ShellDecisions.BackAction.WebBack -> webView?.goBack()
                ShellDecisions.BackAction.Ignore -> Unit
                // Keep the WebView (and its page) alive rather than finishing.
                ShellDecisions.BackAction.Leave -> moveTaskToBack(true)
            }
        }
    }

    private fun openSettings() = openSettingsPage(AppPage.START_URL)

    /**
     * The page's `openSettings(section)`: the endpoint section means the list
     * (it is at the top), or the editor straight away when there is one endpoint.
     */
    private fun openSettingsAt(section: String?) {
        val only = settings.endpoints.singleOrNull()
        openSettingsPage(
            if (section == ShellMessages.SECTION_ENDPOINTS && only != null) AppPage.startUrl(editId = only.id) else AppPage.START_URL,
        )
    }

    /** The settings page, opened at [start] ([AppPage.startUrl]); one that is up already stays where it is. */
    private fun openSettingsPage(start: String) {
        tray.collapse()
        if (!showSettings) settingsStart = start
        showSettings = true
        // The names the list shows come from the servers; ask again if they are due.
        viewModel.refreshEndpointNames()
    }

    /** The settings close; with no endpoint to show, the shell says what it needs. */
    private fun closeSettings() {
        if (settings.base == null) {
            Toast.makeText(this, R.string.msg_base_url_needed, Toast.LENGTH_SHORT).show()
        }
        showLauncherEditor = false
        showSettings = false
    }

    /** The link's endpoint becomes the current one; the link loads once it is (applySettings → takeLinkUrl). */
    private fun switchForLink(request: LinkSwitch) {
        linkSwitch = null
        pendingLink = request.link
        viewModel.selectEndpoint(request.endpointId)
    }

    /** A link for another configured endpoint: which, what it is called, and the link itself. */
    private class LinkSwitch(val endpointId: String, val label: String, val link: String)

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
        // Whatever opens, the tray goes down (an app may open beside Ara, which stays in view).
        tray.collapse()
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
            // The split-screen window starts it, and counts it.
            openInSplitWindow(entry)
            return
        }
        // An app or web app opened: it comes first in the tray from now on.
        if (entry is LauncherEntry.Launchable) viewModel.recordLaunch(entry)
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
            if (entry is LauncherEntry.Launchable) viewModel.recordLaunch(entry)
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
        tray.collapse()
        if (entry is LauncherEntry.Folder) {
            folderEditor = FolderEditorRequest(entry, isNew = false)
        } else {
            editing = entry to false
        }
    }

    /** Open the folder editor on a new folder; made from All apps, it starts with [item] (an app or web app) in it. */
    private fun newFolder(item: LauncherEntry.Launchable? = null) {
        tray.collapse()
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

    /** The failure card's "Choose certificate": for the current endpoint, saved at once. */
    private fun chooseCertificate() {
        val endpoint = settings.current ?: return openSettings()
        val host = endpoint.origin
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias -> runOnUiThread { if (alias != null) viewModel.setEndpointCertificate(endpoint.id, alias) } },
            null, // any key type
            null, // any issuer
            host?.host,
            host?.port ?: -1,
            endpoint.certificateAlias,
        )
    }

    /** The page's "Select certificate": the answer goes back to the page, into its draft (saved with it). */
    private fun chooseCertificateFor(action: SettingsAction.ChooseCertificate) {
        val host = ShellUrls.origin(ShellUrls.normalizeBase(action.baseUrl))
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias -> runOnUiThread { settingsPage?.bridge?.send(SettingsMessages.certificate(action.request, alias)) } },
            null, // any key type
            null, // any issuer
            host?.host,
            host?.port ?: -1,
            action.current,
        )
    }

    /** A certificate import for the page's [request], for the endpoint at [baseUrl]; [name] as suggested to the installer. */
    private data class CertificateImportRun(val request: String, val baseUrl: String, val name: String? = null)

    /**
     * The page's "Import certificate": a PKCS#12 file from the picker goes to
     * Android's certificate installer ([CertificateInstaller]), and once it is
     * installed Android's chooser opens on it, as "Select certificate" does —
     * installing gives Ara no access to the key, the user's choice there does.
     * Each way the import ends is told to the page ([SettingsMessages.certificateImport]),
     * the chooser's answer as for "Select certificate".
     */
    private fun importCertificateFor(action: SettingsAction.ImportCertificate) {
        if (CertificateInstaller.blockedByPolicy(this)) {
            return answerImport(action.request, CertificateImport.Outcome.Blocked)
        }
        val run = CertificateImportRun(action.request, action.baseUrl)
        certificateImport = run
        try {
            certificateFilePicker.launch(CertificateImport.PICKER_TYPES)
        } catch (_: ActivityNotFoundException) {
            certificateImport = null
            answerImport(run.request, CertificateImport.Outcome.NoPicker)
        }
    }

    private fun onCertificateFile(uri: Uri?) {
        val run = certificateImport ?: return
        if (uri == null) {
            certificateImport = null
            return answerImport(run.request, CertificateImport.Outcome.NoFile)
        }
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { CertificateInstaller.read(this@MainActivity, uri) }
            if (certificateImport !== run) {
                // Another import started meanwhile: this one is dropped.
                if (read is CertificateInstaller.Read.Picked) read.file.bytes.fill(0)
                return@launch
            }
            when (read) {
                is CertificateInstaller.Read.Refused -> {
                    certificateImport = null
                    answerImport(run.request, read.outcome)
                }
                is CertificateInstaller.Read.Picked -> installCertificate(run, read.file)
            }
        }
    }

    private fun installCertificate(run: CertificateImportRun, file: CertificateInstaller.File) {
        val host = ShellUrls.origin(ShellUrls.normalizeBase(run.baseUrl))?.host
        val name = CertificateImport.suggestedName(file.displayName, host)
        certificateImport = run.copy(name = name)
        try {
            certificateInstaller.launch(CertificateInstaller.installIntent(file, name))
        } catch (e: Exception) {
            // ActivityNotFoundException: no installer on this device (or firmware
            // without it); SecurityException: one Ara may not start.
            Log.w(TAG, "certificate installer unavailable", e)
            certificateImport = null
            answerImport(run.request, CertificateImport.Outcome.NoInstaller)
        } finally {
            // The intent has gone to Android (or nowhere): Ara's copy is wiped.
            file.bytes.fill(0)
        }
    }

    private fun onCertificateInstalled(installed: Boolean) {
        val run = certificateImport ?: return
        certificateImport = null
        if (!installed) return answerImport(run.request, CertificateImport.Outcome.NotInstalled)
        answerImport(run.request, CertificateImport.Outcome.Installed)
        chooseCertificateFor(SettingsAction.ChooseCertificate(run.request, run.baseUrl, run.name))
    }

    private fun answerImport(request: String, outcome: CertificateImport.Outcome) {
        settingsPage?.bridge?.send(SettingsMessages.certificateImport(request, outcome))
    }

    // --- WebView -------------------------------------------------------------

    /** A message from the page, to the window showing it (see [ShellPage]). */
    internal fun onWebMessage(message: WebMessage) {
        when (message) {
            is WebMessage.VoiceActive -> WakeRuntime.setVoiceActive(message.active)
            is WebMessage.OpenSettings -> openSettingsAt(message.section)
            is WebMessage.SelectEndpoint -> viewModel.selectEndpoint(message.id)
            is WebMessage.Speak, WebMessage.StopSpeaking, WebMessage.Voices -> page.speech?.handle(message)
            WebMessage.HealthSync -> HealthSyncRuntime.syncNow(
                HealthSyncLogic.triggerFor(webView?.url, settings.base),
                healthPermissionRequester,
            )
            is WebMessage.HealthSyncInterval -> HealthSyncRuntime.setInterval(message.minutes)
            is WebMessage.HealthAutoSync -> HealthSyncRuntime.setAutoSync(message.on)
            is WebMessage.DeviceSet -> viewModel.setDeviceWebSetting(message.key, message.value)
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
        // The server is reachable: a name that is due is fetched now (rate-limited).
        if (page.pageReady) viewModel.refreshEndpointNames()
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
        var keep by remember(prompt) { mutableStateOf(false) }
        val endpointId = settings.current?.id
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
                    if (endpointId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keep, onCheckedChange = { keep = it })
                            Text(stringResource(R.string.auth_remember), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (keep && endpointId != null) viewModel.rememberCredentials(endpointId, user, password)
                        prompt.proceed(user, password)
                    },
                ) { Text(stringResource(R.string.auth_sign_in)) }
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
