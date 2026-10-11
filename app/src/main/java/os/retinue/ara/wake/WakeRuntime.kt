package os.retinue.ara.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.retinue.ara.audio.Chime
import os.retinue.ara.ui.MainActivity

/** What the wake detector is doing, for the settings screen. */
sealed class WakeStatus {
    data object Idle : WakeStatus()
    data object Listening : WakeStatus()
    data object PausedForVoice : WakeStatus()
    data class Error(val message: String) : WakeStatus()
}

/**
 * Process-wide owner of wake detection. It gathers the inputs of [WakeGate]
 * (mode, shell visibility, service, the web app's voice-active flag, mic
 * permission, models) and opens or closes the mic accordingly; in
 * [WakeMode.Always] it also starts and stops [WakeService]. Everything runs on
 * the main thread except the listener's own audio thread.
 */
object WakeRuntime {

    /** After a wake the mic stays closed this long, so the page can take it. */
    private const val HOLD_AFTER_WAKE_MS = 10_000L

    private const val TAG = "WakeRuntime"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var chime: Chime? = null

    private var mode = WakeMode.Off
    private var threshold = 0.5f
    private var shellVisible = false
    private var serviceRunning = false
    private var serviceRequested = false
    private var holdUntilMs = 0L
    private var listener: WakeListener? = null
    private var listenerThreshold = 0f
    private var listenerGen = 0
    private var lastError: String? = null

    private val _voiceActive = MutableStateFlow(false)

    /** The web app reported the mic in use (`RetinueShell.setVoiceActive`). */
    val voiceActive: StateFlow<Boolean> = _voiceActive.asStateFlow()

    private val _status = MutableStateFlow<WakeStatus>(WakeStatus.Idle)
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    /**
     * Set by MainActivity while it exists: delivers a wake to the page directly.
     * Without it (or while the shell is not visible) a wake starts the activity.
     */
    var onWake: (() -> Unit)? = null

    private val reevaluate = Runnable { evaluate() }

    fun init(context: Context) {
        app = context.applicationContext
        // SoundPool loads asynchronously: load the chime now so a wake can play it.
        if (chime == null) chime = Chime(app)
    }

    fun configure(mode: WakeMode, threshold: Float) = onMain {
        val changed = mode != this.mode || threshold != this.threshold
        this.mode = mode
        this.threshold = threshold
        if (mode == WakeMode.Off) lastError = null
        if (changed) restartListener() else evaluate()
    }

    fun setShellVisible(visible: Boolean) = onMain {
        shellVisible = visible
        evaluate()
    }

    fun setVoiceActive(active: Boolean) = onMain {
        _voiceActive.value = active
        evaluate()
    }

    /** Microphone permission or the model files changed: re-check from scratch. */
    fun refresh() = onMain {
        lastError = null
        restartListener()
    }

    internal fun serviceStarted() = onMain {
        serviceRunning = true
        serviceRequested = false
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

        if (WakeGate.shouldStopService(mode, serviceRunning)) {
            app.stopService(Intent(app, WakeService::class.java))
        } else if (!serviceRequested && WakeGate.shouldStartService(mode, shellVisible, serviceRunning) && micGranted()) {
            serviceRequested = true
            try {
                ContextCompat.startForegroundService(app, Intent(app, WakeService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses some background starts; listening still works while visible.
                serviceRequested = false
                Log.w(TAG, "wake service could not start", e)
            }
        }

        val models = WakeModelFiles.status(app).ready
        val listen = lastError == null && WakeGate.shouldListen(
            WakeGate.Inputs(
                mode = mode,
                shellVisible = shellVisible,
                serviceRunning = serviceRunning,
                voiceActive = _voiceActive.value,
                micPermission = micGranted(),
                modelsReady = models,
                nowMs = now,
                holdUntilMs = holdUntilMs,
            ),
        )
        if (listen) startListener() else stopListener()

        _status.value = when {
            lastError != null -> WakeStatus.Error(lastError!!)
            listen -> WakeStatus.Listening
            mode != WakeMode.Off && _voiceActive.value -> WakeStatus.PausedForVoice
            else -> WakeStatus.Idle
        }
        if (now < holdUntilMs) main.postDelayed(reevaluate, holdUntilMs - now)
    }

    private fun startListener() {
        if (listener != null && listenerThreshold == threshold) return
        stopListener()
        listenerThreshold = threshold
        val gen = ++listenerGen
        listener = WakeListener(
            app,
            threshold,
            onDetected = { main.post { if (gen == listenerGen) detected() } },
            onError = { message ->
                main.post {
                    if (gen == listenerGen) {
                        lastError = message
                        listener = null
                        evaluate()
                    }
                }
            },
        ).also { it.start() }
    }

    private fun stopListener() {
        listenerGen++
        listener?.stop()
        listener = null
    }

    private fun detected() {
        // The listener ended itself and released the mic.
        listenerGen++
        listener = null
        holdUntilMs = SystemClock.elapsedRealtime() + HOLD_AFTER_WAKE_MS
        evaluate()
        chime?.play()
        val direct = onWake
        if (shellVisible && direct != null) {
            direct()
        } else {
            val intent = Intent(app, MainActivity::class.java)
                .setAction(MainActivity.ACTION_WAKE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                app.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not bring the shell to front", e)
            }
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
