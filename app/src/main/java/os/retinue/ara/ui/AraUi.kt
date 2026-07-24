package os.retinue.ara.ui

import os.retinue.ara.state.AraPreferences
import os.retinue.ara.state.AraState
import os.retinue.ara.state.PrivacyState

/** A recoverable error can be retried/cleared; a terminal one is informational. */
data class UiError(val message: String, val terminal: Boolean)

/** Immutable snapshot the Compose UI renders. */
data class AraUi(
    val state: AraState = AraState.Initializing,
    /** Current or most recently recognized prompt (partial while capturing). */
    val prompt: String = "",
    val partial: Boolean = false,
    val answer: String? = null,
    val entryUrl: String? = null,
    val error: UiError? = null,
    val status: String? = null,
    val privacy: PrivacyState = PrivacyState.Unknown,
    val prefs: AraPreferences = AraPreferences(),
    val showCloudDisclosure: Boolean = false,
    val showCapturedDecision: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val endpointConfigured: Boolean = false,
) {
    /** Whether the screen should stay awake in this state. */
    val keepScreenOn: Boolean
        get() = state in KEEP_AWAKE

    companion object {
        private val KEEP_AWAKE = setOf(
            AraState.Listening,
            AraState.Recognizing,
            AraState.Submitting,
            AraState.WaitingForResponseForeground,
            AraState.Speaking,
        )
    }
}
