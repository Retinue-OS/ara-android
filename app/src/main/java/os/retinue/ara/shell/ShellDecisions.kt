package os.retinue.ara.shell

/**
 * The shell's branching rules for Back, Home and wake, framework-free so they
 * are unit-tested without an emulator. MainActivity only gathers the inputs and
 * carries out the answer.
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
     */
    fun pendingWake(
        ageMs: Long,
        settingsLoaded: Boolean,
        resumed: Boolean,
        windowFocused: Boolean,
        keyguardLocked: Boolean,
        maxAgeMs: Long = PENDING_WAKE_MAX_MS,
    ): PendingWake = when {
        ageMs > maxAgeMs -> PendingWake.Drop
        settingsLoaded && resumed && windowFocused && !keyguardLocked -> PendingWake.Deliver
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
}
