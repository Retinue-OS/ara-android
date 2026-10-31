package os.retinue.ara.shell

/**
 * The shell's branching rules for Back, Home, wake and the WebView's window
 * focus, framework-free so they are unit-tested without an emulator.
 * MainActivity only gathers the inputs and carries out the answer.
 */
object ShellDecisions {

    enum class BackAction {
        /** Close the open native overlay (settings, app drawer, a menu). */
        CloseOverlay,

        /** Go back one step in the WebView's history. */
        WebBack,

        /** Swallow the press: the home screen has nowhere to go back to. */
        Ignore,

        /** Leave the app (only when it is not the home screen). */
        Leave,
    }

    /**
     * Back: an open overlay first, then the web page's history; at the root the
     * home screen stays put, a plain app leaves.
     */
    fun onBack(overlayOpen: Boolean, webCanGoBack: Boolean, isHome: Boolean): BackAction = when {
        overlayOpen -> BackAction.CloseOverlay
        webCanGoBack -> BackAction.WebBack
        isHome -> BackAction.Ignore
        else -> BackAction.Leave
    }

    /** What a new intent asks the running activity to do. */
    enum class IntentAction {
        /** A Home press while the shell was already in front: tell the page (`home`). */
        HomeEvent,

        /** Returning to the shell from elsewhere (Home from another app, a launcher tap). */
        Show,

        /** The wake phrase was heard (wake action from the detector). */
        Wake,
    }

    /**
     * Classify a new intent. [alreadyInFront] is what the activity knew before the
     * intent arrived — it had window focus and the intent did not bring the task
     * to front (the same test Launcher3 uses).
     */
    fun onNewIntent(isWake: Boolean, isHomeCategory: Boolean, alreadyInFront: Boolean): IntentAction = when {
        isWake -> IntentAction.Wake
        isHomeCategory && alreadyInFront -> IntentAction.HomeEvent
        else -> IntentAction.Show
    }

    enum class WakeDelivery {
        /** The page is loaded on the configured origin: dispatch the `wake` event. */
        DispatchEvent,

        /**
         * Not loaded (or elsewhere): load the start page — without a wake flag,
         * so no `?wake=1` entry lands in the history — and keep the wake pending
         * until the page has finished loading.
         */
        LoadStartPage,

        /** The start page is already loading (for this wake or not): wait for it. */
        WaitForPage,

        /** No base URL configured: open native settings instead. */
        OpenSettings,
    }

    /**
     * How a wake that may go to the page now gets there. [startPageLoading] is
     * true while the start page is loading — the shell loaded it for this wake
     * (once per wake) or it was loading anyway — so it is not loaded again.
     */
    fun wakeDelivery(baseConfigured: Boolean, pageLoadedOnOrigin: Boolean, startPageLoading: Boolean): WakeDelivery = when {
        !baseConfigured -> WakeDelivery.OpenSettings
        pageLoadedOnOrigin -> WakeDelivery.DispatchEvent
        startPageLoading -> WakeDelivery.WaitForPage
        else -> WakeDelivery.LoadStartPage
    }

    /**
     * A wake intent that is not a new wake: the activity was recreated (saved
     * state present) or the task was relaunched from Recents, which replays the
     * intent that last started it.
     */
    fun isReplayedIntent(restored: Boolean, launchedFromHistory: Boolean): Boolean =
        restored || launchedFromHistory

    /** Where a launcher entry is started (an app, a shortcut, a link). */
    enum class LaunchTarget {
        /** From this window, as any launcher starts it: full screen, or wherever Android puts it. */
        Here,

        /**
         * In the other half of split screen, next to this window
         * (FLAG_ACTIVITY_LAUNCH_ADJACENT): everything the split-screen window starts.
         */
        Beside,

        /**
         * From the home screen, which cannot be in split screen itself: the
         * split-screen window comes up and starts it beside itself.
         */
        ThroughSplitWindow,
    }

    /**
     * Where a launcher entry opened in [splitWindow] (or else the home screen)
     * is started. [splitLaunch] is the setting; [alternate] is the long-press
     * choice, the other of the two on the home screen.
     */
    fun launchTarget(splitWindow: Boolean, splitLaunch: Boolean, alternate: Boolean): LaunchTarget = when {
        splitWindow -> LaunchTarget.Beside
        splitLaunch != alternate -> LaunchTarget.ThroughSplitWindow
        else -> LaunchTarget.Here
    }

    /** What an intent asks of the split-screen window. */
    enum class SplitRequest {
        /** Come to the front (a tap on its Recents tile, a plain start). */
        Show,

        /** Start the launcher entry it carries beside itself, once it is in front. */
        Open,

        /** Only be among the recent apps: go to the back at once. */
        Park,
    }

    /**
     * An intent of the split-screen window. A replayed one ([isReplayedIntent]:
     * a relaunch from Recents repeats the intent that created the task) only
     * shows it: neither the entry nor the parking happens twice.
     */
    fun splitRequest(hasEntry: Boolean, park: Boolean, replayed: Boolean): SplitRequest = when {
        replayed -> SplitRequest.Show
        park -> SplitRequest.Park
        hasEntry -> SplitRequest.Open
        else -> SplitRequest.Show
    }

    /** Between two attempts to put the split-screen window back among the recent apps. */
    const val PARK_RETRY_MS = 10_000L

    /**
     * Whether the home screen puts the split-screen window back among the
     * recent apps: the setting is on, it is not there, and the last attempt
     * ([sinceLastAttemptMs] ago, null for none) is not that recent, so one that
     * did not take is not repeated on every resume.
     */
    fun parkSplitWindow(keepInRecents: Boolean, inRecents: Boolean, sinceLastAttemptMs: Long?): Boolean =
        keepInRecents && !inRecents && (sinceLastAttemptMs == null || sinceLastAttemptMs >= PARK_RETRY_MS)

    /** How long an accepted wake may wait for the shell to come up (screen on, unlock). */
    const val PENDING_WAKE_MAX_MS = 60_000L

    enum class PendingWake {
        /** Hand the wake to the page now. */
        Deliver,

        /** Keep it: the page could not open the mic yet. */
        Wait,

        /** Too old: forget it (the user never got to the shell). */
        Drop,
    }

    /**
     * When an accepted wake reaches the page. The page opens the mic only while
     * its document is visible, so the wake waits until the activity is resumed,
     * its window has focus and the keyguard is gone — and until the settings
     * are read, so an unconfigured base URL is not mistaken for a missing one.
     * In split screen ([inMultiWindow]) both halves are resumed and visible but
     * only one has focus: the page's half needs none (the mic and the page's
     * audio work there), and taking it would interrupt the other app.
     */
    fun pendingWake(
        ageMs: Long,
        settingsLoaded: Boolean,
        resumed: Boolean,
        windowFocused: Boolean,
        keyguardLocked: Boolean,
        inMultiWindow: Boolean = false,
        maxAgeMs: Long = PENDING_WAKE_MAX_MS,
    ): PendingWake = when {
        ageMs > maxAgeMs -> PendingWake.Drop
        settingsLoaded && resumed && (windowFocused || inMultiWindow) && !keyguardLocked -> PendingWake.Deliver
        else -> PendingWake.Wait
    }

    enum class UnhandledEvent {
        /** Load the web app's start page (a Home press). */
        LoadStart,

        /**
         * Load `<base>/?wake=1` (a wake): the fallback for a page that does not
         * take the event. The web app opens the mic for the flag and strips it
         * with `history.replaceState` on load.
         */
        LoadWake,

        /** Leave the page alone. */
        Nothing,
    }

    /**
     * A `wake` or `home` event the page did not handle (a page of the origin
     * without the web app's listener, such as a gateway page): the shell goes to
     * the web app itself. Home on the start page stays put rather than reload it.
     */
    fun onUnhandledEvent(isWake: Boolean, onStartPage: Boolean): UnhandledEvent = when {
        isWake -> UnhandledEvent.LoadWake
        onStartPage -> UnhandledEvent.Nothing
        else -> UnhandledEvent.LoadStart
    }

    /**
     * Whether the WebView is told that its window lost or regained focus.
     * Chromium 155 to 157 hide the soft keyboard whenever the WebView's window
     * loses focus (crbug.com/560321831, fixed in 158), and the keyboard's own
     * popups take that focus: its language list, the input-method picker. So
     * the keyboard vanished the moment such a popup opened, and the popup,
     * which belongs to the keyboard's window, went with it before anything
     * could be picked. While the keyboard is up a loss is kept from the
     * WebView; a gain always reaches it, and one it already assumes changes
     * nothing.
     */
    fun passWindowFocusChange(hasFocus: Boolean, keyboardUp: Boolean): Boolean =
        hasFocus || !keyboardUp
}
