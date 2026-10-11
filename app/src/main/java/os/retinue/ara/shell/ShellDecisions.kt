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

        /** Not loaded (or elsewhere): load `<base>/?wake=1`. */
        LoadWakeUrl,

        /** No base URL configured: open native settings instead. */
        OpenSettings,
    }

    fun wakeDelivery(baseConfigured: Boolean, pageLoadedOnOrigin: Boolean): WakeDelivery = when {
        !baseConfigured -> WakeDelivery.OpenSettings
        pageLoadedOnOrigin -> WakeDelivery.DispatchEvent
        else -> WakeDelivery.LoadWakeUrl
    }
}
