package os.retinue.ara.state

import kotlinx.serialization.Serializable

/**
 * The explicit UI/audio state model. Exactly one of these is active at a time.
 * Persistent preferences and durable job state are modeled separately (see
 * [AraPreferences] and [JobRecord]) so they survive independently of the
 * transient UI state.
 */
enum class AraState {
    Initializing,
    Inactive,
    Listening,
    Recognizing,
    CapturedInputDecision,
    Submitting,
    WaitingForResponseForeground,
    WaitingForResponseBackground,
    AnswerPendingPresentation,
    Speaking,
    Error,
}

/** What the recognizer's privacy posture is, as far as the Android API confirms. */
enum class PrivacyState { OnDevice, Cloud, Unknown }

/**
 * User preferences that persist across launches. First-launch defaults:
 * continuous conversation ON, automatic playback OFF.
 */
data class AraPreferences(
    val continuousConversation: Boolean = true,
    val automaticPlayback: Boolean = false,
    val cloudRecognitionDisclosureAcknowledged: Boolean = false,
    val selectedCertificateAlias: String? = null,
)

/** Terminal vs recoverable status of a durable Retinue job. */
enum class JobStatus { Pending, Done, Error, TimedOut }

/**
 * Durable state for the single active Retinue request (version one supports one
 * at a time). Persisted so the app can recover after process death and tell an
 * intentional cancellation apart from a system-killed process.
 */
@Serializable
data class JobRecord(
    val jobUri: String,
    val prompt: String,
    val submittedAtMs: Long,
    val timeoutDeadlineMs: Long,
    val status: JobStatus = JobStatus.Pending,
    val answer: String? = null,
    val entryUrl: String? = null,
    val errorMessage: String? = null,
    val answerPresented: Boolean = false,
    val playbackCompleted: Boolean = false,
    /** True only for an explicit local cancellation (Recents swipe / user cancel). */
    val cancelled: Boolean = false,
)
