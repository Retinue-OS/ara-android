package os.retinue.ara.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import os.retinue.ara.audio.Chime
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.ui.MainActivity

/** What the wake detector is doing, for the settings screen. */
sealed class WakeStatus {
    data object Idle : WakeStatus()
    data object Listening : WakeStatus()
    data object PausedForVoice : WakeStatus()
    data object PausedForOtherApp : WakeStatus()
    data class Error(val message: String) : WakeStatus()
}

/**
 * Process-wide owner of wake detection. It gathers the inputs of [WakeGate]
 * (each instance's mode, shell visibility, service, the web app's
 * voice-active flag, mic permission, models) and opens or closes the mic
 * accordingly; while any instance listens in [WakeMode.Always] it also starts
 * and stops [WakeService]. The instances listened for are the [WakeTargets]:
 * the one shown and those working in the background, each with its own
 * phrase model and threshold, all in one listener. A wake reaches the shell
 * directly while it is visible, otherwise through [WakeAlert]; either way it
 * names the instance whose phrase was heard, and the shell brings that one to
 * the front. The wake intent carries a one-time token ([takePendingWake]).
 * Everything runs on the main thread except the listener's own audio thread.
 */
object WakeRuntime {

    /** After a wake the mic stays closed this long, so the page can take it. */
    private const val HOLD_AFTER_WAKE_MS = 10_000L

    private const val TAG = "WakeRuntime"

    /** The wake intent's token extra (see [consumePendingWake]). */
    const val EXTRA_WAKE_TOKEN = "os.retinue.ara.extra.WAKE_TOKEN"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var chime: Chime? = null

    private var targets: List<WakeTarget> = emptyList()
    private var shellVisible = false
    private var shellResumed = false
    private var serviceRunning = false
    private var serviceRequested = false
    private var holdUntilMs = 0L
    private var listener: WakeListener? = null

    /** The last stopped listener: the next one waits for it to let go of the mic. */
    private var retired: WakeListener? = null
    /** What the running listener listens for: it is replaced when this changes. */
    private var listenerPhrases: List<WakePhrase> = emptyList()
    private var listenerGen = 0
    private var lastError: String? = null

    /** Retries after a mic error, and after the service failed to enter the foreground. */
    private val micBackoff = Backoff()
    private val serviceBackoff = Backoff()

    private val random = SecureRandom()
    // The token lives as long as the shell keeps an accepted wake pending.
    private val ticket = WakeTicket(maxAgeMs = ShellDecisions.PENDING_WAKE_MAX_MS) {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val voice = VoiceActivity()

    /** Another app records: the listener lets go of the mic (see [MicYield]). */
    private val micYield = MicYield()

    /**
     * Audio sessions of the listeners' recordings, from just before they start
     * until they are released, to tell them apart from other apps'.
     */
    private val ownSessions = mutableSetOf<Int>()

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            recordingsChanged(configs)
        }
    }
    private val _voiceActive = MutableStateFlow(false)

    /**
     * The web app has the mic (`RetinueShell.setVoiceActive`), or was just
     * granted it ([pageMicGranted]).
     */
    val voiceActive: StateFlow<Boolean> = _voiceActive.asStateFlow()

    private val _status = MutableStateFlow<WakeStatus>(WakeStatus.Idle)
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    private val _listening = MutableStateFlow<Set<String>>(emptySet())

    /** The instances (endpoint ids) whose phrase is listened for right now. */
    val listening: StateFlow<Set<String>> = _listening.asStateFlow()

    /**
     * Set by the shell (ShellPage) while a window of it exists: takes a wake
     * directly while the shell is visible. Otherwise a wake goes out as a wake
     * intent with a one-time token, through [WakeAlert] (or a direct start while
     * visible).
     */
    var onWake: ((endpointId: String) -> Unit)? = null

    private val reevaluate = Runnable { evaluate() }

    fun init(context: Context) {
        app = context.applicationContext
        // SoundPool loads asynchronously: load the chime now so a wake can play it.
        if (chime == null) {
            chime = Chime(app)
            app.getSystemService(AudioManager::class.java)?.registerAudioRecordingCallback(recordingCallback, main)
        }
    }

    /**
     * The active recordings changed (main thread). Any recording that is not
     * the listener's own, or the listener's own silenced by Android in favour
     * of another app, means the mic belongs to someone else now.
     */
    private fun recordingsChanged(configs: List<AudioRecordingConfiguration>) {
        val others = configs.any { c ->
            if (c.clientAudioSessionId in ownSessions) c.isClientSilenced else true
        }
        micYield.recordingsChanged(others, SystemClock.elapsedRealtime())
        evaluate()
    }

    /** The instances to listen for (see [WakeTargets]), from the settings. */
    fun configure(targets: List<WakeTarget>) = onMain {
        this.targets = targets
        if (targets.isEmpty()) lastError = null
        // A changed phrase set replaces the listener in evaluate().
        evaluate()
    }

    fun setShellVisible(visible: Boolean) = onMain {
        shellVisible = visible
        if (visible && lastError != null) {
            // Coming back to the shell is a fresh chance for a mic that failed.
            lastError = null
            micBackoff.succeeded()
        }
        evaluate()
    }

    /** The shell activity is resumed: the only time the wake service is started. */
    fun setShellResumed(resumed: Boolean) = onMain {
        shellResumed = resumed
        evaluate()
    }

    /** The page reported the mic in use or not; this holds until it reports again. */
    fun setVoiceActive(active: Boolean) = onMain {
        voice.pageReported(active)
        evaluate()
    }

    /**
     * The page was just granted the microphone: close the wake mic before the
     * page opens its own, and count the page as using it until it reports (or
     * for a few seconds if it never does).
     */
    fun pageMicGranted() = onMain {
        voice.micGranted(SystemClock.elapsedRealtime())
        evaluate()
    }

    /** Microphone permission or the model files changed: re-check from scratch. */
    fun refresh() = onMain {
        lastError = null
        micBackoff.succeeded()
        serviceBackoff.succeeded()
        restartListener()
    }

    /**
     * The shell received a wake intent: the wake, with the instance whose
     * phrase was heard, when it carries the token of the wake announced last
     * within its lifetime; null otherwise. Main thread.
     */
    fun takePendingWake(token: String?): WakeTicket.Taken? =
        ticket.take(token, SystemClock.elapsedRealtime())

    /**
     * The shell took a wake (or is about to hand it to the page): the wake
     * notification goes, and the mic stays closed a while longer so the page
     * can take it.
     */
    fun wakeAccepted() = onMain {
        if (!::app.isInitialized) return@onMain
        WakeAlert.cancel(app)
        holdUntilMs = SystemClock.elapsedRealtime() + HOLD_AFTER_WAKE_MS
        evaluate()
    }

    internal fun serviceStarted() = onMain {
        serviceRunning = true
        serviceRequested = false
        serviceBackoff.succeeded()
        evaluate()
    }

    /** The service could not enter the foreground: try again later, not in a loop. */
    internal fun serviceFailed() = onMain {
        serviceRequested = false
        serviceBackoff.failed(SystemClock.elapsedRealtime())
        evaluate()
    }

    internal fun serviceStopped() = onMain {
        serviceRunning = false
        serviceRequested = false
        evaluate()
    }

    private fun restartListener() {
        stopListener()
        evaluate()
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun evaluate() {
        if (!::app.isInitialized) return
        main.removeCallbacks(reevaluate)
        val now = SystemClock.elapsedRealtime()

        val mic = micGranted()
        val featuresReady = WakeModelFiles.status(app, null).featuresReady
        // The instances that could listen: those whose phrase model is in the library and on the device.
        val phrases = if (!featuresReady) emptyMap() else targets.mapNotNull { t ->
            WakeModelFiles.phraseFileFor(app, t.modelId)?.let { t to WakePhrase(t.endpointId, it, t.threshold) }
        }.toMap()
        // The service runs while any of them listens in the background.
        val serviceMode = if (phrases.keys.any { it.mode == WakeMode.Always }) WakeMode.Always else WakeMode.Off
        val anyOn = targets.isNotEmpty()

        // A mic error is retried with a backoff, without a settings change.
        if (lastError != null && micBackoff.ready(now)) lastError = null

        if (WakeGate.shouldStopService(serviceMode, serviceRunning, modelsReady = true)) {
            app.stopService(Intent(app, WakeService::class.java))
        } else if (!serviceRequested && mic && serviceBackoff.ready(now) &&
            // Android 14 lets a microphone service start only from the foreground.
            WakeGate.shouldStartService(serviceMode, shellResumed, serviceRunning, modelsReady = true)
        ) {
            serviceRequested = true
            try {
                ContextCompat.startForegroundService(app, Intent(app, WakeService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses some background starts; listening still works while visible.
                serviceRequested = false
                serviceBackoff.failed(now)
                Log.w(TAG, "wake service could not start", e)
            }
        }

        val voiceActive = voice.active(now)
        _voiceActive.value = voiceActive
        val yielding = micYield.yielding(now)
        val active = if (lastError != null) emptyList() else phrases.filter { (t, _) ->
            WakeGate.shouldListen(
                WakeGate.Inputs(
                    mode = t.mode,
                    shellVisible = shellVisible,
                    serviceRunning = serviceRunning,
                    voiceActive = voiceActive,
                    micPermission = mic,
                    modelsReady = true,
                    nowMs = now,
                    holdUntilMs = holdUntilMs,
                    otherAppRecording = yielding,
                ),
            )
        }.values.toList()
        val listen = active.isNotEmpty()
        if (listen) startListener(active) else stopListener()
        _listening.value = active.map { it.endpointId }.toSet()

        val error = lastError
        _status.value = when {
            error != null -> WakeStatus.Error(error)
            listen -> WakeStatus.Listening
            anyOn && voiceActive -> WakeStatus.PausedForVoice
            anyOn && yielding -> WakeStatus.PausedForOtherApp
            else -> WakeStatus.Idle
        }

        // Look again when something changes by the clock alone.
        val next = listOfNotNull(
            holdUntilMs.takeIf { it > now },
            voice.changesAt(now),
            micYield.changesAt(now),
            micBackoff.retryAt.takeIf { error != null && it > now },
            serviceBackoff.retryAt.takeIf { serviceMode == WakeMode.Always && !serviceRunning && it > now },
        ).minOrNull()
        if (next != null) main.postDelayed(reevaluate, next - now)
    }

    private fun startListener(phrases: List<WakePhrase>) {
        if (listener != null && listenerPhrases == phrases) return
        stopListener()
        listenerPhrases = phrases
        val gen = ++listenerGen
        listener = WakeListener(
            app,
            phrases,
            previous = retired,
            onDetected = { endpointId -> main.post { if (gen == listenerGen) detected(endpointId) } },
            onError = { message ->
                main.post {
                    if (gen == listenerGen) {
                        lastError = message
                        micBackoff.failed(SystemClock.elapsedRealtime())
                        listener = null
                        evaluate()
                    }
                }
            },
            // Posted before the recording starts, so it is handled before the callback reporting it.
            onRecording = { sessionId -> main.post { ownSessions.add(sessionId) } },
            onReleased = { sessionId -> main.post { ownSessions.remove(sessionId) } },
        ).also { it.start() }
    }

    private fun stopListener() {
        listenerGen++
        listener?.let {
            // Signals only: the listener's thread releases the mic on its own.
            it.stop()
            retired = it
        }
        listener = null
    }

    /** The phrase of the instance [endpointId] was heard. */
    private fun detected(endpointId: String) {
        // The listener ended itself and released the mic.
        listenerGen++
        retired = listener
        listener = null
        val now = SystemClock.elapsedRealtime()
        holdUntilMs = now + HOLD_AFTER_WAKE_MS
        micBackoff.succeeded()
        evaluate()
        if (!shellVisible && !WakeAlert.notificationsAllowed(app)) {
            // No way to the shell from the background: no chime for a wake that goes nowhere.
            Log.w(TAG, "wake heard, but notifications are off: it cannot reach the shell")
            return
        }
        chime?.play()
        val direct = onWake
        if (shellVisible && direct != null) {
            direct(endpointId)
            return
        }
        val intent = Intent(app, MainActivity::class.java)
            .setAction(MainActivity.ACTION_WAKE)
            // The instance stays with the ticket: the intent carries the token only.
            .putExtra(EXTRA_WAKE_TOKEN, ticket.issue(now, endpointId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (shellVisible) {
            // A visible app may still start its activity directly.
            try {
                app.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not bring the shell to front", e)
            }
        }
        // From the background, Android 10+ blocks activity starts unless the
        // app may draw over others; with that permission the start goes
        // through. The block is silent (no exception), so the notification is
        // posted as well and cancelled the moment the shell takes the wake.
        if (WakeAlert.canDrawOverlays(app)) {
            try {
                app.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not bring the shell to front", e)
            }
        }
        // A full-screen notification opens the shell on a locked or dark
        // screen; unlocked and in use it is a heads-up the user taps.
        WakeAlert.show(app, intent)
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
