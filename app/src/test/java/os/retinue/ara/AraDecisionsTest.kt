package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.state.AraDecisions
import os.retinue.ara.state.AraDecisions.MicToggleOutcome
import os.retinue.ara.state.AraDecisions.NotificationDecision
import os.retinue.ara.state.AraDecisions.RecoveryDecision
import os.retinue.ara.state.AraDecisions.SendResolution
import os.retinue.ara.state.AraDecisions.SpeakerToggleOutcome
import os.retinue.ara.state.AraState
import os.retinue.ara.state.AraPreferences
import os.retinue.ara.state.JobRecord
import os.retinue.ara.state.JobStatus

class AraDecisionsTest {

    private fun gate(
        continuous: Boolean = true,
        foreground: Boolean = true,
        mic: Boolean = true,
        initialized: Boolean = true,
        cloudReady: Boolean = true,
        recognitionActive: Boolean = false,
        submitting: Boolean = false,
        jobPending: Boolean = false,
        speaking: Boolean = false,
        decisionPending: Boolean = false,
    ) = AraDecisions.ListeningGate(
        continuousConversation = continuous,
        inForeground = foreground,
        micPermissionGranted = mic,
        initialized = initialized,
        cloudReady = cloudReady,
        recognitionActive = recognitionActive,
        submitting = submitting,
        jobPending = jobPending,
        speaking = speaking,
        decisionPending = decisionPending,
    )

    private fun job(
        status: JobStatus = JobStatus.Pending,
        answer: String? = null,
        answerPresented: Boolean = false,
        playbackCompleted: Boolean = false,
        cancelled: Boolean = false,
        deadlineMs: Long = Long.MAX_VALUE,
        submittedAtMs: Long = 0,
    ) = JobRecord(
        jobUri = "https://h/jobs/1",
        prompt = "p",
        submittedAtMs = submittedAtMs,
        timeoutDeadlineMs = deadlineMs,
        status = status,
        answer = answer,
        answerPresented = answerPresented,
        playbackCompleted = playbackCompleted,
        cancelled = cancelled,
    )

    // ── Listening gate (the core invariants) ─────────────────────────────────

    @Test
    fun canStartListening_all_conditions_met() {
        assertTrue(AraDecisions.canStartListening(gate()))
    }

    @Test
    fun canStartListening_blocked_by_each_condition() {
        assertFalse(AraDecisions.canStartListening(gate(continuous = false)))
        assertFalse(AraDecisions.canStartListening(gate(foreground = false)))
        assertFalse(AraDecisions.canStartListening(gate(mic = false)))
        assertFalse(AraDecisions.canStartListening(gate(cloudReady = false)))
        assertFalse(AraDecisions.canStartListening(gate(recognitionActive = true)))
        assertFalse(AraDecisions.canStartListening(gate(submitting = true)))
        assertFalse(AraDecisions.canStartListening(gate(jobPending = true)))
        assertFalse(AraDecisions.canStartListening(gate(speaking = true)))
        assertFalse(AraDecisions.canStartListening(gate(decisionPending = true)))
    }

    // ── Mic toggle ───────────────────────────────────────────────────────────

    @Test
    fun micToggle_disable_without_input_needs_no_decision() {
        assertEquals(
            MicToggleOutcome.DisableNoInput,
            AraDecisions.onMicToggle(newEnabled = false, hasUsableInput = false, busy = false),
        )
    }

    @Test
    fun micToggle_disable_with_input_requires_decision() {
        assertEquals(
            MicToggleOutcome.DisableRequireDecision,
            AraDecisions.onMicToggle(newEnabled = false, hasUsableInput = true, busy = false),
        )
    }

    @Test
    fun micToggle_enable_while_idle_tries_to_listen() {
        assertEquals(
            MicToggleOutcome.EnableTryListen,
            AraDecisions.onMicToggle(newEnabled = true, hasUsableInput = false, busy = false),
        )
    }

    @Test
    fun micToggle_enable_while_busy_only_persists() {
        assertEquals(
            MicToggleOutcome.EnablePersistOnly,
            AraDecisions.onMicToggle(newEnabled = true, hasUsableInput = false, busy = true),
        )
    }

    // ── Speaker toggle ───────────────────────────────────────────────────────

    @Test
    fun speakerToggle_enable_is_future_only() {
        assertEquals(
            SpeakerToggleOutcome.EnableFuturePlayback,
            AraDecisions.onSpeakerToggle(newEnabled = true, speaking = false),
        )
    }

    @Test
    fun speakerToggle_disable_while_speaking_interrupts() {
        assertEquals(
            SpeakerToggleOutcome.InterruptPlayback,
            AraDecisions.onSpeakerToggle(newEnabled = false, speaking = true),
        )
    }

    @Test
    fun speakerToggle_disable_while_silent_only_persists() {
        assertEquals(
            SpeakerToggleOutcome.DisablePersistOnly,
            AraDecisions.onSpeakerToggle(newEnabled = false, speaking = false),
        )
    }

    // ── Send resolution & duplicate guard ────────────────────────────────────

    @Test
    fun resolveSendText_prefers_final_then_partial() {
        assertEquals(SendResolution.Submit("final"), AraDecisions.resolveSendText("final", "partial"))
        assertEquals(SendResolution.Submit("partial"), AraDecisions.resolveSendText("  ", "partial"))
        assertEquals(SendResolution.Submit("partial"), AraDecisions.resolveSendText(null, "partial"))
        assertEquals(SendResolution.NothingUsable, AraDecisions.resolveSendText("  ", null))
        assertEquals(SendResolution.NothingUsable, AraDecisions.resolveSendText(null, ""))
    }

    @Test
    fun shouldSubmit_blocks_second_submission_and_empty_text() {
        assertTrue(AraDecisions.shouldSubmit(alreadySubmitted = false, text = "hi"))
        assertFalse(AraDecisions.shouldSubmit(alreadySubmitted = true, text = "hi"))
        assertFalse(AraDecisions.shouldSubmit(alreadySubmitted = false, text = "   "))
    }

    @Test
    fun isCurrentCallback_rejects_stale_tokens() {
        assertTrue(AraDecisions.isCurrentCallback(callbackToken = 5, currentToken = 5))
        assertFalse(AraDecisions.isCurrentCallback(callbackToken = 4, currentToken = 5))
    }

    // ── Foreground / background transitions ──────────────────────────────────

    @Test
    fun background_state_depends_on_pending_job() {
        assertEquals(AraState.WaitingForResponseBackground, AraDecisions.stateOnEnterBackground(true))
        assertEquals(AraState.Inactive, AraDecisions.stateOnEnterBackground(false))
    }

    @Test
    fun foreground_pending_resumes_waiting() {
        val plan = AraDecisions.onEnterForeground(job(JobStatus.Pending), AraPreferences())
        assertTrue(plan.resumeWaiting)
        assertFalse(plan.presentAnswer)
    }

    @Test
    fun foreground_done_presents_and_speaks_when_playback_enabled() {
        val plan = AraDecisions.onEnterForeground(
            job(JobStatus.Done, answer = "a", playbackCompleted = false),
            AraPreferences(automaticPlayback = true),
        )
        assertTrue(plan.presentAnswer)
        assertTrue(plan.speakAnswer)
    }

    @Test
    fun foreground_done_presents_text_only_when_playback_disabled() {
        val plan = AraDecisions.onEnterForeground(
            job(JobStatus.Done, answer = "a"),
            AraPreferences(automaticPlayback = false),
        )
        assertTrue(plan.presentAnswer)
        assertFalse(plan.speakAnswer)
    }

    @Test
    fun foreground_done_does_not_replay_completed_playback() {
        val plan = AraDecisions.onEnterForeground(
            job(JobStatus.Done, answer = "a", playbackCompleted = true),
            AraPreferences(automaticPlayback = true),
        )
        assertTrue(plan.presentAnswer)
        assertFalse(plan.speakAnswer)
    }

    @Test
    fun foreground_cancelled_job_does_nothing() {
        val plan = AraDecisions.onEnterForeground(job(cancelled = true), AraPreferences())
        assertFalse(plan.presentAnswer)
        assertFalse(plan.resumeWaiting)
        assertFalse(plan.presentFailure)
    }

    // ── Notification decisions ───────────────────────────────────────────────

    @Test
    fun notification_none_in_foreground() {
        assertEquals(NotificationDecision.None, AraDecisions.decideNotification(true, job(JobStatus.Pending)))
    }

    @Test
    fun notification_active_when_pending_in_background() {
        assertEquals(NotificationDecision.ActiveJob, AraDecisions.decideNotification(false, job(JobStatus.Pending)))
    }

    @Test
    fun notification_answer_ready_when_done_unpresented_in_background() {
        assertEquals(
            NotificationDecision.AnswerReady,
            AraDecisions.decideNotification(false, job(JobStatus.Done, answer = "a", answerPresented = false)),
        )
    }

    @Test
    fun notification_none_when_answer_already_presented() {
        assertEquals(
            NotificationDecision.None,
            AraDecisions.decideNotification(false, job(JobStatus.Done, answer = "a", answerPresented = true)),
        )
    }

    // ── Process-death recovery ───────────────────────────────────────────────

    @Test
    fun recovery_nothing_without_job() {
        assertEquals(RecoveryDecision.Nothing, AraDecisions.decideRecovery(null, 1_000))
    }

    @Test
    fun recovery_never_resumes_cancelled_job() {
        assertEquals(RecoveryDecision.Nothing, AraDecisions.decideRecovery(job(cancelled = true), 1_000))
    }

    @Test
    fun recovery_resumes_fresh_pending_job() {
        val j = job(JobStatus.Pending, deadlineMs = 10_000, submittedAtMs = 0)
        assertEquals(RecoveryDecision.ResumePolling, AraDecisions.decideRecovery(j, nowMs = 5_000))
    }

    @Test
    fun recovery_times_out_expired_pending_job() {
        val j = job(JobStatus.Pending, deadlineMs = 10_000, submittedAtMs = 0)
        assertEquals(RecoveryDecision.MarkTimedOut, AraDecisions.decideRecovery(j, nowMs = 10_000))
    }

    @Test
    fun recovery_presents_unpresented_answer() {
        val j = job(JobStatus.Done, answer = "a", answerPresented = false)
        assertEquals(RecoveryDecision.PresentAnswer, AraDecisions.decideRecovery(j, 1_000))
    }

    @Test
    fun recovery_ignores_already_presented_answer() {
        val j = job(JobStatus.Done, answer = "a", answerPresented = true)
        assertEquals(RecoveryDecision.Nothing, AraDecisions.decideRecovery(j, 1_000))
    }

    // ── Explicit task removal ────────────────────────────────────────────────

    @Test
    fun taskRemoval_cancels_and_preserves_preferences() {
        val a = AraDecisions.onTaskRemoved()
        assertTrue(a.cancelPolling)
        assertTrue(a.stopService)
        assertTrue(a.discardCapturedInput)
        assertTrue(a.markJobCancelled)
        assertTrue(a.preservePreferences)
    }
}
