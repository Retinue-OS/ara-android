package os.retinue.ara.state

/**
 * Pure decision logic for Ara's behavior. Everything here is framework-free and
 * unit-tested; the ViewModel and services translate these decisions into calls
 * on the Android speech / TTS / networking components.
 *
 * Centralizing the rules here is what keeps the many invariants in the spec
 * (mic inactive while waiting, no double submit, stale callbacks ignored, …)
 * consistent and checkable without an emulator.
 */
object AraDecisions {

    // ── When may Ara begin a new listening session? ──────────────────────────

    /**
     * All conditions that must hold before continuous mode starts a new
     * recognition session. Encodes the invariant list from the spec.
     */
    data class ListeningGate(
        val continuousConversation: Boolean,
        val inForeground: Boolean,
        val micPermissionGranted: Boolean,
        val initialized: Boolean,
        /** On-device recognizer, or the cloud disclosure has been acknowledged. */
        val cloudReady: Boolean,
        val recognitionActive: Boolean,
        val submitting: Boolean,
        val jobPending: Boolean,
        val speaking: Boolean,
        val decisionPending: Boolean,
    )

    fun canStartListening(g: ListeningGate): Boolean =
        g.continuousConversation &&
            g.inForeground &&
            g.micPermissionGranted &&
            g.initialized &&
            g.cloudReady &&
            !g.recognitionActive &&
            !g.submitting &&
            !g.jobPending &&
            !g.speaking &&
            !g.decisionPending

    // ── Microphone toggle (continuous-conversation mode) ─────────────────────

    sealed interface MicToggleOutcome {
        /** Disabled with no usable capture: stop any empty session, stay inactive. */
        data object DisableNoInput : MicToggleOutcome

        /** Disabled while usable speech/partial text exists: ask Send or Discard. */
        data object DisableRequireDecision : MicToggleOutcome

        /** Enabled while idle: attempt to start listening (subject to the gate). */
        data object EnableTryListen : MicToggleOutcome

        /** Enabled while busy (waiting/speaking): persist only; do not interrupt. */
        data object EnablePersistOnly : MicToggleOutcome
    }

    /**
     * @param hasUsableInput whether the current capture already produced usable
     *   final or partial text.
     * @param busy whether a request is in flight or an answer is being spoken —
     *   in which case enabling must not interrupt anything.
     */
    fun onMicToggle(newEnabled: Boolean, hasUsableInput: Boolean, busy: Boolean): MicToggleOutcome =
        if (!newEnabled) {
            if (hasUsableInput) MicToggleOutcome.DisableRequireDecision
            else MicToggleOutcome.DisableNoInput
        } else {
            if (busy) MicToggleOutcome.EnablePersistOnly
            else MicToggleOutcome.EnableTryListen
        }

    // ── Speaker toggle (automatic playback) ──────────────────────────────────

    sealed interface SpeakerToggleOutcome {
        /** Enabled: future answers may play; no replay of the current one. */
        data object EnableFuturePlayback : SpeakerToggleOutcome

        /** Disabled while not speaking: just persist. */
        data object DisablePersistOnly : SpeakerToggleOutcome

        /** Disabled while speaking: stop the utterance immediately (the stop control). */
        data object InterruptPlayback : SpeakerToggleOutcome
    }

    fun onSpeakerToggle(newEnabled: Boolean, speaking: Boolean): SpeakerToggleOutcome =
        when {
            newEnabled -> SpeakerToggleOutcome.EnableFuturePlayback
            speaking -> SpeakerToggleOutcome.InterruptPlayback
            else -> SpeakerToggleOutcome.DisablePersistOnly
        }

    // ── Send: which text (if any) to submit ──────────────────────────────────

    sealed interface SendResolution {
        data class Submit(val text: String) : SendResolution
        data object NothingUsable : SendResolution
    }

    /** Prefer the recognizer's final result; fall back to the best partial. */
    fun resolveSendText(finalText: String?, bestPartial: String?): SendResolution {
        val chosen = finalText?.trim()?.takeIf { it.isNotEmpty() }
            ?: bestPartial?.trim()?.takeIf { it.isNotEmpty() }
        return if (chosen != null) SendResolution.Submit(chosen) else SendResolution.NothingUsable
    }

    /**
     * Guard against double submission. A prompt may be submitted only when no
     * submission has already happened for the current capture and the text is
     * non-empty.
     */
    fun shouldSubmit(alreadySubmitted: Boolean, text: String): Boolean =
        !alreadySubmitted && text.isNotBlank()

    /** Ignore recognizer/TTS callbacks that belong to a superseded session. */
    fun isCurrentCallback(callbackToken: Long, currentToken: Long): Boolean =
        callbackToken == currentToken

    // ── Foreground / background transitions ──────────────────────────────────

    /** Target state when Ara leaves the foreground. */
    fun stateOnEnterBackground(jobPending: Boolean): AraState =
        if (jobPending) AraState.WaitingForResponseBackground else AraState.Inactive

    data class ForegroundPlan(
        /** There is a completed answer to show. */
        val presentAnswer: Boolean,
        /** The answer should be (re)spoken from the beginning. */
        val speakAnswer: Boolean,
        /** A job is still pending: resume the waiting UI. */
        val resumeWaiting: Boolean,
        /** A terminal failure should be surfaced. */
        val presentFailure: Boolean,
    )

    /**
     * What to do when Ara returns to the foreground, given the persisted job and
     * current preferences. Listening afterwards is left to [canStartListening].
     */
    fun onEnterForeground(job: JobRecord?, prefs: AraPreferences): ForegroundPlan {
        if (job == null || job.cancelled) {
            return ForegroundPlan(false, false, false, false)
        }
        return when (job.status) {
            JobStatus.Pending -> ForegroundPlan(
                presentAnswer = false, speakAnswer = false,
                resumeWaiting = true, presentFailure = false,
            )
            JobStatus.Done -> {
                val hasAnswer = job.answer != null
                ForegroundPlan(
                    presentAnswer = hasAnswer,
                    // Speak when playback is enabled and this answer's playback
                    // hasn't completed — which also restarts an answer whose
                    // playback was interrupted by backgrounding.
                    speakAnswer = hasAnswer && prefs.automaticPlayback && !job.playbackCompleted,
                    resumeWaiting = false,
                    presentFailure = false,
                )
            }
            JobStatus.Error, JobStatus.TimedOut ->
                ForegroundPlan(false, false, false, presentFailure = true)
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    enum class NotificationDecision { None, ActiveJob, AnswerReady }

    /**
     * Which (privacy-safe) notification should be showing. Notifications are a
     * background concern: while Ara is in the foreground there is none.
     */
    fun decideNotification(inForeground: Boolean, job: JobRecord?): NotificationDecision {
        if (job == null || job.cancelled || inForeground) return NotificationDecision.None
        return when (job.status) {
            JobStatus.Pending -> NotificationDecision.ActiveJob
            JobStatus.Done -> if (!job.answerPresented) NotificationDecision.AnswerReady
            else NotificationDecision.None
            JobStatus.Error, JobStatus.TimedOut -> NotificationDecision.None
        }
    }

    // ── Process-death recovery ───────────────────────────────────────────────

    sealed interface RecoveryDecision {
        data object Nothing : RecoveryDecision
        data object ResumePolling : RecoveryDecision
        data object PresentAnswer : RecoveryDecision
        data object MarkTimedOut : RecoveryDecision
        data object PresentFailure : RecoveryDecision
    }

    /**
     * Decide how to recover a persisted job after an unexpected process restart.
     * An explicitly cancelled job is never resumed — that is how a Recents-swipe
     * dismissal is told apart from system process death.
     */
    fun decideRecovery(job: JobRecord?, nowMs: Long): RecoveryDecision = when {
        job == null -> RecoveryDecision.Nothing
        job.cancelled -> RecoveryDecision.Nothing
        job.status == JobStatus.Done && !job.answerPresented -> RecoveryDecision.PresentAnswer
        job.status == JobStatus.Done -> RecoveryDecision.Nothing
        job.status == JobStatus.Error -> RecoveryDecision.PresentFailure
        job.status == JobStatus.TimedOut -> RecoveryDecision.PresentFailure
        // Pending:
        nowMs >= job.timeoutDeadlineMs -> RecoveryDecision.MarkTimedOut
        else -> RecoveryDecision.ResumePolling
    }

    // ── Explicit task removal (Recents swipe) ────────────────────────────────

    /**
     * Actions for an explicit task removal. Preferences are always preserved;
     * the active job (if any) is marked cancelled so a later process recreation
     * does not resume it.
     */
    data class TaskRemovalActions(
        val stopRecognition: Boolean = true,
        val stopTts: Boolean = true,
        val cancelPolling: Boolean = true,
        val stopService: Boolean = true,
        val dismissNotifications: Boolean = true,
        val discardCapturedInput: Boolean = true,
        val clearPendingDecision: Boolean = true,
        val markJobCancelled: Boolean = true,
        val preservePreferences: Boolean = true,
    )

    fun onTaskRemoved(): TaskRemovalActions = TaskRemovalActions()
}
