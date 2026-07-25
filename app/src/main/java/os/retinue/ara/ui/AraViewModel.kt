package os.retinue.ara.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.audio.Chime
import os.retinue.ara.data.AraSettings
import os.retinue.ara.data.AraStore
import os.retinue.ara.protocol.RetinueProtocol
import os.retinue.ara.service.AraForeground
import os.retinue.ara.service.AraNotifications
import os.retinue.ara.service.JobBus
import os.retinue.ara.service.PollingService
import os.retinue.ara.service.ServiceError
import os.retinue.ara.service.ServiceErrorKind
import os.retinue.ara.speech.SpeechController
import os.retinue.ara.speech.TtsController
import os.retinue.ara.state.AraDecisions
import os.retinue.ara.state.AraPreferences
import os.retinue.ara.state.AraState
import os.retinue.ara.state.JobRecord
import os.retinue.ara.state.JobStatus
import os.retinue.ara.state.PrivacyState

/**
 * The single orchestrator. It holds the transient UI/audio state, drives the
 * Android speech / TTS / chime components, and starts the polling service, while
 * delegating every branching decision to the pure [AraDecisions] so the rules
 * stay in one tested place.
 *
 * Recognition and TTS run only while in the foreground; polling is owned by the
 * service and continues in the background.
 */
class AraViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AraStore(app)
    private val speech = SpeechController(app)
    private val chime = Chime(app)
    private var ttsReady = false
    private val tts = TtsController(app) { ready ->
        ttsReady = ready
    }

    // ── Internal transient state (rendered via [emit]) ───────────────────────
    private var state: AraState = AraState.Initializing
    private var promptText: String = ""
    private var partial: Boolean = false
    private var answer: String? = null
    private var entryUrl: String? = null
    private var status: String? = null
    private var error: UiError? = null
    private var privacy: PrivacyState = PrivacyState.Unknown
    private var prefs: AraPreferences = AraPreferences()
    private var settings: AraSettings = AraSettings()
    private var showCloudDisclosure = false
    private var showCapturedDecision = false
    private var micPermissionGranted = false

    // Capture / session bookkeeping.
    private var finalText: String? = null
    private var bestPartial: String? = null
    private var submittedForCapture = false
    private var pendingSend = false
    private var capturedText: String = ""

    private var recognitionToken: Long = 0
    private var ttsToken: Long = 0

    private var inForeground = false
    private var initialized = false
    private var bootstrapped = false
    private var autoListenSuppressed = false
    private var currentJob: JobRecord? = null
    private var handledDoneKey: String? = null

    private val _ui = MutableStateFlow(AraUi())
    val ui: StateFlow<AraUi> = _ui.asStateFlow()

    val settingsFlow: StateFlow<AraSettings>
        get() = _settings
    private val _settings = MutableStateFlow(AraSettings())

    init {
        viewModelScope.launch {
            prefs = store.preferencesNow()
            settings = store.settingsNow()
            _settings.value = settings
            initialized = true
            if (state == AraState.Initializing) state = AraState.Inactive
            emit()
        }
        // Keep preferences/settings live.
        viewModelScope.launch { store.preferences.collect { prefs = it; emit() } }
        viewModelScope.launch { store.settings.collect { settings = it; _settings.value = it; emit() } }
        // Observe the polling service.
        viewModelScope.launch { JobBus.job.collect { onJobUpdate(it) } }
        viewModelScope.launch {
            JobBus.error.collect { err -> if (err != null) { onServiceError(err); JobBus.clearError() } }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun onForeground() {
        inForeground = true
        AraForeground.inForeground = true
        autoListenSuppressed = false
        AraNotifications.cancelAll(getApplication())
        if (!bootstrapped) {
            bootstrapped = true
            bootstrap()
        } else {
            syncOnForeground()
        }
    }

    fun onBackground() {
        inForeground = false
        AraForeground.inForeground = false
        // Foreground-only components stop; polling keeps running in the service.
        recognitionToken++
        speech.cancel()
        // Background stop of TTS is NOT a user speaker-toggle change: invalidate
        // callbacks but leave playbackCompleted unset so it can replay on return.
        ttsToken++
        tts.stop()
        val pending = currentJob?.let { it.status == JobStatus.Pending && !it.cancelled } ?: false
        state = AraDecisions.stateOnEnterBackground(pending)
        emit()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val job = store.jobNow()
            currentJob = job
            when (AraDecisions.decideRecovery(job, System.currentTimeMillis())) {
                AraDecisions.RecoveryDecision.Nothing -> maybeListen()
                AraDecisions.RecoveryDecision.ResumePolling -> {
                    PollingService.resume(getApplication())
                    state = AraState.WaitingForResponseForeground
                    promptText = job?.prompt ?: promptText
                    emit()
                }
                AraDecisions.RecoveryDecision.PresentAnswer ->
                    job?.let { deliverAnswer(it, allowSpeak = prefs.automaticPlayback) }
                AraDecisions.RecoveryDecision.MarkTimedOut -> {
                    job?.let { store.saveJob(it.copy(status = JobStatus.TimedOut)) }
                    showError(getString(R.string.msg_job_timeout), terminal = true)
                }
                AraDecisions.RecoveryDecision.PresentFailure ->
                    showError(job?.errorMessage ?: getString(R.string.msg_job_failed), terminal = true)
            }
        }
    }

    private fun syncOnForeground() {
        val job = JobBus.job.value ?: currentJob
        currentJob = job
        val plan = AraDecisions.onEnterForeground(job, prefs)
        when {
            plan.resumeWaiting -> { state = AraState.WaitingForResponseForeground; emit() }
            plan.presentAnswer && job != null -> deliverAnswer(job, allowSpeak = plan.speakAnswer)
            plan.presentFailure ->
                showError(job?.errorMessage ?: getString(R.string.msg_job_failed), terminal = true)
            else -> maybeListen()
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    fun onMicPermissionResult(granted: Boolean) {
        micPermissionGranted = granted
        if (granted) {
            error = null
            maybeListen()
        } else {
            showError(getString(R.string.msg_mic_permission_denied), terminal = false)
        }
        emit()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) status = getString(R.string.msg_notif_permission_denied)
        emit()
    }

    // ── Toggles ──────────────────────────────────────────────────────────────

    fun toggleMic() {
        val newEnabled = !prefs.continuousConversation
        val hasUsable = (state == AraState.Listening || state == AraState.Recognizing) &&
            (!bestPartial.isNullOrBlank() || !finalText.isNullOrBlank()) && !submittedForCapture
        val busy = state in BUSY_STATES
        prefs = prefs.copy(continuousConversation = newEnabled)
        viewModelScope.launch { store.setContinuousConversation(newEnabled) }
        when (AraDecisions.onMicToggle(newEnabled, hasUsable, busy)) {
            AraDecisions.MicToggleOutcome.DisableNoInput -> {
                if (speech.active) { recognitionToken++; speech.cancel() }
                clearCapture()
                if (!busy) state = AraState.Inactive
            }
            AraDecisions.MicToggleOutcome.DisableRequireDecision -> {
                capturedText = finalText?.takeIf { it.isNotBlank() } ?: (bestPartial ?: "")
                recognitionToken++
                speech.cancel() // stop accepting more mic input; text is preserved above
                showCapturedDecision = true
                state = AraState.CapturedInputDecision
            }
            AraDecisions.MicToggleOutcome.EnableTryListen -> {
                autoListenSuppressed = false
                startListeningIfAllowed()
            }
            AraDecisions.MicToggleOutcome.EnablePersistOnly -> {
                autoListenSuppressed = false // will listen once the current op finishes
            }
        }
        emit()
    }

    fun toggleSpeaker() {
        val newEnabled = !prefs.automaticPlayback
        val speaking = state == AraState.Speaking
        prefs = prefs.copy(automaticPlayback = newEnabled)
        viewModelScope.launch { store.setAutomaticPlayback(newEnabled) }
        when (AraDecisions.onSpeakerToggle(newEnabled, speaking)) {
            AraDecisions.SpeakerToggleOutcome.EnableFuturePlayback -> { /* no replay of current */ }
            AraDecisions.SpeakerToggleOutcome.DisablePersistOnly -> { /* just persisted */ }
            AraDecisions.SpeakerToggleOutcome.InterruptPlayback -> {
                ttsToken++ // stale-guard: ignore this utterance's callbacks
                tts.stop()
                currentJob?.let { markPlaybackCompleted(it) }
                // The answer stays visible. Resume listening only if continuous.
                state = AraState.Inactive
                if (prefs.continuousConversation && inForeground) {
                    val token = ++recognitionToken
                    viewModelScope.launch {
                        // Short technical delay so we don't capture Ara's own tail.
                        delay(300)
                        if (recognitionToken == token && inForeground && !speech.active) beginListening()
                    }
                }
            }
        }
        emit()
    }

    // ── Primary action (Send / Start listening) ──────────────────────────────

    fun onPrimaryAction() {
        when (state) {
            AraState.Listening, AraState.Recognizing -> onSend()
            AraState.Inactive, AraState.Error -> {
                autoListenSuppressed = false
                error = null
                beginListening()
            }
            else -> {}
        }
    }

    private fun onSend() {
        pendingSend = true
        // Finalize (do not discard) the current capture; onFinal/onError routes it.
        speech.stopAndFinalize()
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    fun acceptCloudDisclosure() {
        prefs = prefs.copy(cloudRecognitionDisclosureAcknowledged = true)
        viewModelScope.launch { store.setCloudDisclosureAcknowledged(true) }
        showCloudDisclosure = false
        autoListenSuppressed = false
        beginListening()
    }

    fun declineCloudDisclosure() {
        showCloudDisclosure = false
        autoListenSuppressed = true
        status = getString(R.string.msg_cloud_declined)
        state = AraState.Inactive
        emit()
    }

    fun capturedDecisionSend() {
        showCapturedDecision = false
        val text = capturedText
        if (text.isNotBlank()) submit(text) else { state = AraState.Inactive; emit() }
    }

    fun capturedDecisionDiscard() {
        showCapturedDecision = false
        recognitionToken++
        speech.cancel()
        clearCapture()
        capturedText = ""
        state = AraState.Inactive
        emit()
    }

    fun dismissError() {
        error = null
        if (state == AraState.Error) state = AraState.Inactive
        emit()
    }

    fun retry() {
        error = null
        status = null
        autoListenSuppressed = false
        state = AraState.Inactive
        emit()
        startListeningIfAllowed()
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun saveSettings(newSettings: AraSettings) {
        viewModelScope.launch { store.setSettings(newSettings) }
    }

    fun setCertificateAlias(alias: String?) {
        prefs = prefs.copy(selectedCertificateAlias = alias)
        viewModelScope.launch { store.setCertificateAlias(alias) }
        emit()
    }

    // ── Listening ────────────────────────────────────────────────────────────

    private fun maybeListen() {
        if (state == AraState.Speaking) return
        startListeningIfAllowed()
    }

    private fun startListeningIfAllowed() {
        if (autoListenSuppressed) { emit(); return }
        val jobPending = currentJob?.let { it.status == JobStatus.Pending && !it.cancelled } ?: false
        val baseOk = prefs.continuousConversation && inForeground && micPermissionGranted &&
            initialized && !speech.active && state != AraState.Submitting && !jobPending &&
            state != AraState.Speaking && !showCapturedDecision
        if (!baseOk) { emit(); return }
        when (speech.availability()) {
            SpeechController.Availability.OnDevice -> beginListening()
            SpeechController.Availability.Cloud ->
                if (prefs.cloudRecognitionDisclosureAcknowledged) beginListening()
                else { showCloudDisclosure = true; state = AraState.Inactive; emit() }
            SpeechController.Availability.None -> { state = AraState.Inactive; emit() }
        }
    }

    private fun beginListening() {
        if (!micPermissionGranted) {
            showError(getString(R.string.msg_mic_permission_denied), terminal = false)
            return
        }
        // Decide the recognizer once, here, and pass that decision to the
        // controller. The controller never re-derives it, so it can never
        // silently fall back to the cloud recognizer for a session we approved
        // as on-device.
        val mode = when (speech.availability()) {
            SpeechController.Availability.None -> {
                privacy = PrivacyState.Unknown
                showError(getString(R.string.msg_recognizer_unavailable), terminal = true)
                autoListenSuppressed = true
                return
            }
            SpeechController.Availability.Cloud -> {
                if (!prefs.cloudRecognitionDisclosureAcknowledged) {
                    showCloudDisclosure = true; state = AraState.Inactive; emit(); return
                }
                privacy = PrivacyState.Cloud
                SpeechController.Mode.Cloud
            }
            SpeechController.Availability.OnDevice -> {
                privacy = PrivacyState.OnDevice
                SpeechController.Mode.OnDevice
            }
        }
        clearCapture()
        error = null
        status = null
        state = AraState.Listening
        emit()
        val token = ++recognitionToken
        chime.play()
        viewModelScope.launch {
            // Start after the chime so Ara never captures its own chime.
            delay(chime.approxDurationMs + 150)
            if (recognitionToken == token && inForeground) {
                speech.start(token, mode, speechListener)
                // Reflect the recognizer actually in use, not the one requested.
                privacy = speech.privacyState
                emit()
            }
        }
    }

    private val speechListener = object : SpeechController.Listener {
        override fun onReady(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            if (state == AraState.Submitting) return
            state = AraState.Listening
            emit()
        }

        override fun onBeginningOfSpeech(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            state = AraState.Recognizing
            emit()
        }

        override fun onPartial(token: Long, text: String) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            bestPartial = text
            promptText = text
            partial = true
            if (state == AraState.Listening) state = AraState.Recognizing
            emit()
        }

        override fun onFinal(token: Long, text: String) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            when (val res = AraDecisions.resolveSendText(text, bestPartial)) {
                is AraDecisions.SendResolution.Submit -> submit(res.text)
                AraDecisions.SendResolution.NothingUsable -> {
                    status = getString(
                        if (pendingSend) R.string.msg_empty_prompt else R.string.msg_no_speech,
                    )
                    pendingSend = false
                    resumeAfterUnusable()
                }
            }
        }

        override fun onEndOfSpeech(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            // Recognizer is finalizing; stay in Recognizing until a result arrives.
        }

        override fun onError(token: Long, error: SpeechController.SpeechError) {
            if (!AraDecisions.isCurrentCallback(token, recognitionToken)) return
            handleSpeechError(error)
        }
    }

    private fun handleSpeechError(err: SpeechController.SpeechError) {
        when (err) {
            SpeechController.SpeechError.InsufficientPermissions -> {
                micPermissionGranted = false
                autoListenSuppressed = true
                showError(getString(R.string.msg_mic_permission_denied), terminal = false)
            }
            SpeechController.SpeechError.Unavailable -> {
                // Includes the fail-closed case where an on-device session was
                // requested but on-device recognition is not actually available.
                // Clear the privacy posture so the label can never keep claiming
                // "on-device" next to an "unavailable" error.
                privacy = PrivacyState.Unknown
                autoListenSuppressed = true
                showError(getString(R.string.msg_recognizer_unavailable), terminal = true)
            }
            SpeechController.SpeechError.Busy -> {
                status = getString(R.string.msg_recognizer_busy)
                scheduleRestart()
            }
            SpeechController.SpeechError.NoMatch,
            SpeechController.SpeechError.NoSpeech,
            SpeechController.SpeechError.Timeout,
            -> {
                if (pendingSend && !bestPartial.isNullOrBlank()) { submit(bestPartial!!); return }
                pendingSend = false
                status = getString(R.string.msg_no_speech)
                scheduleRestart()
            }
            SpeechController.SpeechError.Network -> {
                status = getString(R.string.msg_network_recognition)
                scheduleRestart()
            }
            SpeechController.SpeechError.TemporaryFailure -> {
                status = getString(R.string.msg_recognition_failed)
                scheduleRestart()
            }
        }
    }

    private fun resumeAfterUnusable() {
        state = AraState.Inactive
        emit()
        scheduleRestart()
    }

    private fun scheduleRestart() {
        state = AraState.Inactive
        emit()
        val token = ++recognitionToken // supersede any in-flight session
        viewModelScope.launch {
            delay(700) // avoid a tight restart loop on repeated recognizer errors
            if (recognitionToken == token && !autoListenSuppressed) startListeningIfAllowed()
        }
    }

    // ── Submission ───────────────────────────────────────────────────────────

    private fun submit(text: String) {
        if (!AraDecisions.shouldSubmit(submittedForCapture, text)) return
        val messageUrl = settings.messageUrl()
        if (messageUrl == null) {
            showError(getString(R.string.msg_submit_failed), terminal = true)
            autoListenSuppressed = true
            return
        }
        if (prefs.selectedCertificateAlias == null) {
            showError(getString(R.string.msg_no_certificate), terminal = true)
            autoListenSuppressed = true
            return
        }
        submittedForCapture = true
        pendingSend = false
        recognitionToken++ // any late recognizer callback is now stale
        speech.cancel()
        promptText = text
        partial = false
        status = null
        error = null
        state = AraState.Submitting
        emit()
        val prompt = RetinueProtocol.buildPrompt(text, Locale.getDefault().toLanguageTag())
        PollingService.submit(getApplication(), prompt)
    }

    // ── Job updates from the service ─────────────────────────────────────────

    private fun onJobUpdate(record: JobRecord?) {
        if (record == null) return
        currentJob = record
        if (record.cancelled) {
            if (state == AraState.WaitingForResponseForeground ||
                state == AraState.WaitingForResponseBackground
            ) {
                state = AraState.Inactive
                emit()
            }
            return
        }
        when (record.status) {
            JobStatus.Pending -> {
                promptText = record.prompt
                state = if (inForeground) AraState.WaitingForResponseForeground
                else AraState.WaitingForResponseBackground
                emit()
            }
            JobStatus.Done -> if (inForeground) deliverAnswer(record, allowSpeak = true)
            // else: leave for the answer-ready notification; presented on return.
            JobStatus.Error ->
                showError(record.errorMessage ?: getString(R.string.msg_job_failed), terminal = true)
            JobStatus.TimedOut ->
                showError(getString(R.string.msg_job_timeout), terminal = true)
        }
    }

    private fun onServiceError(err: ServiceError) {
        if (err.kind == ServiceErrorKind.ServiceStartFailed) {
            // Non-fatal: polling continues best-effort while foregrounded.
            status = getString(R.string.msg_service_failed)
            emit()
            return
        }
        autoListenSuppressed = true
        val message = when (err.kind) {
            ServiceErrorKind.MissingCertificate -> getString(R.string.msg_no_certificate)
            ServiceErrorKind.CertificateUnavailable -> getString(R.string.msg_cert_unavailable)
            ServiceErrorKind.Tls -> getString(R.string.msg_tls_failed)
            ServiceErrorKind.Submit -> getString(R.string.msg_submit_failed)
            ServiceErrorKind.BadJobUri -> getString(R.string.msg_bad_job_uri)
            ServiceErrorKind.Blocked -> err.message ?: getString(R.string.msg_blocked)
            ServiceErrorKind.NoEndpoint -> getString(R.string.msg_submit_failed)
            ServiceErrorKind.ServiceStartFailed -> getString(R.string.msg_service_failed)
        }
        showError(message, terminal = true)
    }

    // ── Answer presentation & playback ───────────────────────────────────────

    private fun deliverAnswer(record: JobRecord, allowSpeak: Boolean) {
        val text = record.answer
        answer = text
        entryUrl = record.entryUrl
        promptText = record.prompt
        status = null
        error = null
        currentJob = record
        val key = jobKey(record)
        // Mark presented (idempotent) so a background notification isn't raised.
        if (!record.answerPresented) {
            viewModelScope.launch { store.saveJob(record.copy(answerPresented = true)) }
        }
        val canSpeak = allowSpeak && prefs.automaticPlayback && ttsReady &&
            !text.isNullOrBlank() && inForeground && !record.playbackCompleted
        if (canSpeak) {
            handledDoneKey = key
            state = AraState.Speaking
            emit()
            val token = ++ttsToken
            tts.speak(token, text!!, ttsListener)
        } else {
            // Text-only: mark playback complete so it won't try to replay later.
            markPlaybackCompleted(record)
            handledDoneKey = key
            state = AraState.AnswerPendingPresentation
            emit()
            finishAnswerAndMaybeListen()
        }
    }

    private val ttsListener = object : TtsController.Listener {
        override fun onDone(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, ttsToken)) return
            currentJob?.let { markPlaybackCompleted(it) }
            finishAnswerAndMaybeListen()
        }

        override fun onStopped(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, ttsToken)) return
            // An unexpected engine stop (not our intentional stop, which bumps the
            // token first). Treat as finished and continue.
            currentJob?.let { markPlaybackCompleted(it) }
            finishAnswerAndMaybeListen()
        }

        override fun onError(token: Long) {
            if (!AraDecisions.isCurrentCallback(token, ttsToken)) return
            status = getString(R.string.msg_tts_failed)
            currentJob?.let { markPlaybackCompleted(it) }
            finishAnswerAndMaybeListen()
        }
    }

    private fun finishAnswerAndMaybeListen() {
        state = AraState.Inactive
        emit()
        startListeningIfAllowed()
    }

    private fun markPlaybackCompleted(record: JobRecord) {
        val updated = record.copy(answerPresented = true, playbackCompleted = true)
        currentJob = updated
        viewModelScope.launch { store.saveJob(updated) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun clearCapture() {
        finalText = null
        bestPartial = null
        submittedForCapture = false
        pendingSend = false
        promptText = if (state == AraState.CapturedInputDecision) promptText else ""
        partial = false
    }

    private fun showError(message: String, terminal: Boolean) {
        error = UiError(message, terminal)
        state = AraState.Error
        emit()
    }

    private fun jobKey(record: JobRecord): String =
        if (record.jobUri.isNotEmpty()) record.jobUri else "inline:${record.submittedAtMs}"

    private fun getString(resId: Int): String = getApplication<Application>().getString(resId)

    private fun emit() {
        _ui.value = AraUi(
            state = state,
            prompt = promptText,
            partial = partial,
            answer = answer,
            entryUrl = entryUrl,
            error = error,
            status = status,
            privacy = privacy,
            prefs = prefs,
            showCloudDisclosure = showCloudDisclosure,
            showCapturedDecision = showCapturedDecision,
            micPermissionGranted = micPermissionGranted,
            endpointConfigured = settings.messageUrl() != null && prefs.selectedCertificateAlias != null,
        )
    }

    override fun onCleared() {
        speech.destroy()
        tts.shutdown()
        chime.release()
        super.onCleared()
    }

    private companion object {
        val BUSY_STATES = setOf(
            AraState.Submitting,
            AraState.WaitingForResponseForeground,
            AraState.WaitingForResponseBackground,
            AraState.Speaking,
        )
    }
}
