package os.retinue.ara.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import os.retinue.ara.state.PrivacyState

/**
 * Thin wrapper over Android's [SpeechRecognizer].
 *
 * The caller decides — once — which recognizer to use and passes that decision
 * into [start]; this class never re-derives the recognizer type on its own, so
 * it can never silently create a cloud-backed recognizer when the caller
 * approved an on-device one. It reports the confirmed privacy posture —
 * [PrivacyState.OnDevice] only when an on-device recognizer is actually in use.
 *
 * Every callback carries the session token it was started with so the caller can
 * ignore stale callbacks from a superseded session. All methods must be called
 * on the main thread.
 */
class SpeechController(private val context: Context) {

    enum class Availability { OnDevice, Cloud, None }

    /** Which recognizer the caller wants for a session. */
    enum class Mode { OnDevice, Cloud }

    enum class SpeechError {
        NoMatch, NoSpeech, Timeout, Busy, Unavailable,
        InsufficientPermissions, Network, TemporaryFailure,
    }

    interface Listener {
        fun onReady(token: Long)
        fun onBeginningOfSpeech(token: Long)
        fun onPartial(token: Long, text: String)
        fun onFinal(token: Long, text: String)
        fun onEndOfSpeech(token: Long)
        fun onError(token: Long, error: SpeechError)
    }

    var privacyState: PrivacyState = PrivacyState.Unknown
        private set

    var active: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var token: Long = 0

    /** What kind of recognition, if any, this device can do right now. */
    fun availability(): Availability = when {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> Availability.OnDevice
        SpeechRecognizer.isRecognitionAvailable(context) -> Availability.Cloud
        else -> Availability.None
    }

    /**
     * Start a recognition session in the recognizer [mode] the caller chose.
     * Only one session exists at a time; any previous recognizer is torn down
     * first.
     *
     * The [mode] is authoritative: [Mode.OnDevice] always creates the on-device
     * recognizer and [Mode.Cloud] always creates the cloud one. We deliberately
     * do NOT fall back from on-device to cloud here — a fallback would send audio
     * to Google while the caller (and the UI) still believe recognition is local.
     * If [Mode.OnDevice] is requested while on-device recognition is unavailable,
     * the session fails closed with [SpeechError.Unavailable] rather than leaking
     * audio to the cloud.
     */
    fun start(sessionToken: Long, mode: Mode, listener: Listener, silenceTimeoutMs: Long = 0) {
        teardown()
        this.listener = listener
        this.token = sessionToken

        val onDevice = mode == Mode.OnDevice
        // Fail closed: never substitute the cloud recognizer for a requested
        // on-device one. Better to error than to silently transmit audio.
        if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            privacyState = PrivacyState.Unknown
            active = false
            listener.onError(sessionToken, SpeechError.Unavailable)
            return
        }

        val rec = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        privacyState = if (onDevice) PrivacyState.OnDevice else PrivacyState.Cloud
        recognizer = rec
        active = true
        rec.setRecognitionListener(adapter)
        rec.startListening(buildIntent(onDevice, silenceTimeoutMs))
    }

    /**
     * Ask the recognizer to finalize: stop capturing more audio and deliver its
     * best final result via [Listener.onFinal]. This finalizes — it does not
     * discard — the current capture.
     */
    fun stopAndFinalize() {
        recognizer?.stopListening()
    }

    /** Discard the current capture entirely. */
    fun cancel() {
        teardown()
    }

    fun destroy() {
        teardown()
    }

    private fun teardown() {
        active = false
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun buildIntent(onDevice: Boolean, silenceTimeoutMs: Long): Intent {
        // Use the device's current locale; never hardcode a language.
        val lang = Locale.getDefault().toLanguageTag()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Lengthen the end-of-speech silence window when the user configured
            // one. These extras are advisory — a recognizer may ignore them — so
            // the ViewModel also restarts recognition when auto-send is disabled.
            if (silenceTimeoutMs > 0) {
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    silenceTimeoutMs,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    silenceTimeoutMs,
                )
            }
        }
    }

    private fun firstResult(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()

    private val adapter = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReady(token)
        }

        override fun onBeginningOfSpeech() {
            listener?.onBeginningOfSpeech(token)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listener?.onEndOfSpeech(token)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotEmpty()) listener?.onPartial(token, text)
        }

        override fun onResults(results: Bundle?) {
            active = false
            listener?.onFinal(token, firstResult(results))
        }

        override fun onError(error: Int) {
            active = false
            listener?.onError(token, mapError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun mapError(code: Int): SpeechError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NoMatch
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NoSpeech
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.Timeout
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.Busy
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.InsufficientPermissions
        SpeechRecognizer.ERROR_NETWORK -> SpeechError.Network
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_SERVER,
        -> SpeechError.TemporaryFailure
        else -> SpeechError.Unavailable
    }
}
