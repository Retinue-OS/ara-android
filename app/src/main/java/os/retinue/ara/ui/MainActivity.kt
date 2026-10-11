package os.retinue.ara.ui

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.security.KeyChain
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.shell.WebMessage
import os.retinue.ara.wake.WakeAlert
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeRuntime
import os.retinue.ara.web.NativeSpeech
import os.retinue.ara.web.ShellBridge
import os.retinue.ara.web.ShellChromeClient
import os.retinue.ara.web.ShellHost
import os.retinue.ara.web.ShellIntents
import os.retinue.ara.web.ShellWebViewClient
import os.retinue.ara.web.configureShellWebView

/**
 * The shell (plan §1): a full-size WebView showing the Retinue web app, the
 * launcher bar below it, and native settings on top when asked for. Also the
 * home activity when the user makes the shell their default home app.
 */
class MainActivity : ComponentActivity(), ShellHost {

    companion object {
        /**
         * Started by the wake detector: bring the shell up and dispatch `wake`.
         * Honoured only with the detector's one-time token
         * ([WakeRuntime.EXTRA_WAKE_TOKEN]): the activity is exported.
         */
        const val ACTION_WAKE = "os.retinue.ara.action.WAKE"
    }

    private val viewModel: ShellViewModel by viewModels()

    // --- Web state -----------------------------------------------------------
    private var webView by mutableStateOf<WebView?>(null)
    private lateinit var bridge: ShellBridge
    private lateinit var speech: NativeSpeech
    private var settings = ShellSettings()
    private var currentRouter = UrlRouter(null)
    override val router: UrlRouter get() = currentRouter
    override val certificateAlias: String? get() = settings.certificateAlias
    private var loadedBase: String? = null
    private var pageReady = false

    /** Between onPageStarted and onPageFinished of the main frame. */
    private var pageLoading = false

    // --- Native overlays -----------------------------------------------------
    private var showSettings by mutableStateOf(false)
    private var showDrawer by mutableStateOf(false)
    private var editing by mutableStateOf<Pair<LauncherEntry, Boolean>?>(null)
    private var authPrompt by mutableStateOf<AuthPrompt?>(null)
    private var isDefaultHome by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var fullScreenIntentAllowed by mutableStateOf(true)

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioRequest: PermissionRequest? = null

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
    private var resumed = false

    private val wakeExpiry = Runnable { tryDeliverWake() }

    /** This instance's wake handler, so onDestroy clears only its own. */
    private val onWakeHandler: () -> Unit = { acceptWake(fromIntent = false) }

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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeRole()
    }

    private val phraseModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPhraseModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        speech = NativeSpeech(this) { js -> if (::bridge.isInitialized) bridge.run(js) }
        createWebView()
        refreshHomeRole()
        micGranted = hasMic()

        onBackPressedDispatcher.addCallback(this, backCallback)
        WakeRuntime.onWake = onWakeHandler

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.settings.filterNotNull().collect { applySettings(it) }
            }
        }

        setContent {
            AraTheme {
                val launcher by viewModel.launcher.collectAsStateWithLifecycle()
                val apps by viewModel.apps.collectAsStateWithLifecycle()
                val voiceActive by WakeRuntime.voiceActive.collectAsStateWithLifecycle()
                KeepScreenOn(voiceActive)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ShellLayout(launcher.entries)
                    if (showDrawer) {
                        LaunchedEffect(Unit) { viewModel.refreshApps() }
                        AppDrawer(
                            apps = apps,
                            isPinned = { launcher.containsApp(it.packageName, it.activityName) },
                            onLaunch = ::launchApp,
                            onPin = ::pinApp,
                            onDismiss = { showDrawer = false },
                        )
                    }
                    if (showSettings) SettingsOverlay(launcher)
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
                    authPrompt?.let { AuthDialog(it) }
                }
            }
        }

        handleIntent(intent, initial = true, restored = savedInstanceState != null)
    }

    // --- Layout --------------------------------------------------------------

    @Composable
    private fun ShellLayout(entries: List<LauncherEntry>) {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            val view = webView
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().imePadding()) {
                if (view != null) {
                    key(view) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            // The bar hides while the keyboard is up, so the page keeps its height.
            if (!imeVisible) {
                LauncherBar(
                    entries = entries,
                    onOpen = ::openEntry,
                    onEdit = { editing = it to false },
                    onRemove = { e -> viewModel.updateLauncher { it.remove(e.id) } },
                    onMove = { e, delta -> viewModel.updateLauncher { it.move(e.id, delta) } },
                    onAllApps = { showDrawer = true },
                    onSettings = ::openSettings,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    @Composable
    private fun SettingsOverlay(launcher: os.retinue.ara.launcher.LauncherConfig) {
        val s by viewModel.settings.collectAsStateWithLifecycle()
        val models by viewModel.models.collectAsStateWithLifecycle()
        val busy by viewModel.modelBusy.collectAsStateWithLifecycle()
        val message by viewModel.modelMessage.collectAsStateWithLifecycle()
        val wakeStatus by viewModel.wakeStatus.collectAsStateWithLifecycle()
        Surface(Modifier.fillMaxSize()) {
            SettingsScreen(
                state = SettingsState(
                    settings = s ?: ShellSettings(),
                    launcher = launcher,
                    isDefaultHome = isDefaultHome,
                    models = models,
                    modelBusy = busy,
                    modelMessage = message,
                    wakeStatus = wakeStatus,
                    micGranted = micGranted,
                    fullScreenIntentAllowed = fullScreenIntentAllowed,
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
        override fun addEntry(template: LauncherEntry) {
            editing = template to true
        }

        override fun editEntry(entry: LauncherEntry) {
            editing = entry to false
        }

        override fun removeEntry(entry: LauncherEntry) {
            viewModel.updateLauncher { it.remove(entry.id) }
        }

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

        override fun allowFullScreenIntent() {
            val intent = WakeAlert.fullScreenIntentSettings(this@MainActivity) ?: return
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
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
        WakeRuntime.setShellVisible(true)
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        micGranted = hasMic()
        fullScreenIntentAllowed = WakeAlert.canUseFullScreenIntent(this)
        viewModel.refreshModels()
        webView?.onResume()
        resumed = true
        WakeRuntime.setShellResumed(true)
        tryDeliverWake()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tryDeliverWake()
    }

    override fun onPause() {
        resumed = false
        WakeRuntime.setShellResumed(false)
        webView?.onPause()
        // Only a wake shows the shell over the keyguard and turns the screen on
        // (see acceptWake); a pending wake keeps waiting for the unlock.
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        super.onPause()
    }

    override fun onStop() {
        WakeRuntime.setShellVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (WakeRuntime.onWake === onWakeHandler) WakeRuntime.onWake = null
        window.decorView.removeCallbacks(wakeExpiry)
        speech.shutdown()
        webView?.let { destroyWebView(it) }
        webView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        // Launcher3's test: focused and not just brought to front = already showing.
        val alreadyInFront = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false, alreadyInFront = alreadyInFront)
    }

    private fun handleIntent(
        intent: Intent?,
        initial: Boolean,
        alreadyInFront: Boolean = false,
        restored: Boolean = false,
    ) {
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
            }
        }
    }

    // --- Settings → web ------------------------------------------------------

    private fun applySettings(s: ShellSettings) {
        val aliasChanged = s.certificateAlias != settings.certificateAlias
        settings = s
        settingsLoaded = true
        currentRouter = UrlRouter(s.baseUrl)
        val base = s.base
        if (aliasChanged) WebView.clearClientCertPreferences(null)
        if (base == null) {
            loadedBase = null
            showSettings = true
            webView?.loadUrl("about:blank")
            tryDeliverWake()
            return
        }
        if (base != loadedBase || aliasChanged) {
            loadedBase = base
            bridge.install()
            pageReady = false
            // A pending wake waits for this load and goes out as the event (onPageFinished).
            if (pendingWakeAt != 0L) wakeLoadIssued = true
            webView?.loadUrl(ShellUrls.startUrl(base))
        } else {
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
        val onOrigin = pageReady && router.isConfiguredOrigin(url)
        val startLoading = wakeLoadIssued || (pageLoading && base != null && ShellUrls.isStartPage(base, url))
        when (ShellDecisions.wakeDelivery(base != null, onOrigin, startLoading)) {
            ShellDecisions.WakeDelivery.DispatchEvent -> {
                completeWake()
                bridge.dispatchEvent("wake") { onUnhandledEvent(isWake = true) }
            }
            ShellDecisions.WakeDelivery.LoadStartPage -> if (base != null) {
                wakeLoadIssued = true
                pageReady = false
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
        pageReady = false
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
                pageReady = false
                webView?.loadUrl(ShellUrls.startUrl(base))
            }
            ShellDecisions.UnhandledEvent.Nothing -> Unit
        }
    }

    private fun closeOverlays() {
        showDrawer = false
        editing = null
        if (settings.base != null) showSettings = false
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val overlay = showSettings || showDrawer || editing != null || authPrompt != null
            when (ShellDecisions.onBack(overlay, webView?.canGoBack() == true, isDefaultHome)) {
                ShellDecisions.BackAction.CloseOverlay -> when {
                    authPrompt != null -> authPrompt?.cancel()
                    editing != null -> editing = null
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

    // --- Launcher ------------------------------------------------------------

    private fun openEntry(entry: LauncherEntry) {
        if (entry is LauncherEntry.WebPage) {
            val base = settings.base ?: return openSettings()
            webView?.loadUrl(ShellUrls.webPathUrl(base, entry.path))
            return
        }
        when (val r = ShellIntents.launch(this, entry, router)) {
            is Route.Load -> webView?.loadUrl(r.url)
            is Route.Browser -> ShellIntents.openInBrowser(this, r.url)
            else -> Unit
        }
    }

    private fun launchApp(app: AppInfo) {
        showDrawer = false
        ShellIntents.launchApp(this, app.packageName, app.activityName)
    }

    private fun pinApp(app: AppInfo) {
        viewModel.pin(app)
        Toast.makeText(this, getString(R.string.drawer_pinned, app.label), Toast.LENGTH_SHORT).show()
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

    private fun createWebView() {
        val view = WebView(this)
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        configureShellWebView(view)
        view.webViewClient = ShellWebViewClient(this, this)
        view.webChromeClient = ShellChromeClient(this)
        bridge = ShellBridge(this, view, { router }, ::onWebMessage)
        webView = view
    }

    private fun destroyWebView(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun onWebMessage(message: WebMessage) {
        when (message) {
            is WebMessage.VoiceActive -> WakeRuntime.setVoiceActive(message.active)
            WebMessage.OpenSettings -> openSettings()
            is WebMessage.Speak, WebMessage.StopSpeaking, WebMessage.Voices -> speech.handle(message)
        }
    }

    // --- ShellHost -----------------------------------------------------------

    override fun onPageStarted(url: String?) {
        pageReady = false
        pageLoading = true
        // A new document has no microphone open: never stay paused for a page that left.
        WakeRuntime.setVoiceActive(false)
        speech.reset()
        bridge.onPageStarted(url)
    }

    override fun onPageFinished(url: String?) {
        pageReady = router.isConfiguredOrigin(url)
        pageLoading = false
        bridge.onPageFinished(url)
        // A wake waiting for this page goes out now.
        if (pendingWakeAt != 0L) tryDeliverWake()
    }

    override fun onUrlChanged(url: String?) {
        bridge.onUrlChanged(url)
    }

    override fun onHttpAuth(host: String, realm: String, handler: HttpAuthHandler) {
        authPrompt?.cancel()
        authPrompt = AuthPrompt(host, realm, handler)
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
        webView?.let { destroyWebView(it) }
        createWebView()
        bridge.install()
        pageReady = false
        pageLoading = false
        settings.base?.let { webView?.loadUrl(ShellUrls.startUrl(it)) }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // --- HTTP auth -----------------------------------------------------------

    private inner class AuthPrompt(val host: String, val realm: String, private val handler: HttpAuthHandler) {
        fun proceed(user: String, password: String) {
            handler.proceed(user, password)
            authPrompt = null
        }

        fun cancel() {
            handler.cancel()
            authPrompt = null
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
