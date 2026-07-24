package os.retinue.ara.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wrapper over Android [TextToSpeech].
 *
 * Distinguishes normal completion, user interruption, and failure via the
 * utterance-progress callbacks, carrying the utterance token so stale callbacks
 * (from a superseded utterance) can be ignored. Audio focus is requested while
 * speaking and released as soon as speech ends, so the recognizer can take the
 * mic cleanly afterwards.
 */
class TtsController(
    context: Context,
    private val onInitDone: (ready: Boolean) -> Unit,
) {

    interface Listener {
        /** Normal completion. */
        fun onDone(token: Long)

        /** Interrupted (a new utterance flushed this one, or stop() was called). */
        fun onStopped(token: Long)
        fun onError(token: Long)
    }

    var ready: Boolean = false
        private set

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var listener: Listener? = null

    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // Use the device locale where the engine supports it; otherwise
                // let the engine keep its default rather than forcing a language.
                val locale = Locale.getDefault()
                val supported = tts.isLanguageAvailable(locale)
                if (supported >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
                tts.setOnUtteranceProgressListener(progress)
            }
            onInitDone(ready)
        }
    }

    fun speak(token: Long, text: String, listener: Listener) {
        this.listener = listener
        requestFocus()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, token.toString())
        if (result != TextToSpeech.SUCCESS) {
            abandonFocus()
            listener.onError(token)
        }
    }

    /** Stop the current utterance immediately (no completion callback fires). */
    fun stop() {
        tts.stop()
        abandonFocus()
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        abandonFocus()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            abandonFocus()
            utteranceId?.toLongOrNull()?.let { listener?.onDone(it) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            abandonFocus()
            utteranceId?.toLongOrNull()?.let { listener?.onStopped(it) }
        }

        @Deprecated("Superseded by onError(String?, Int)")
        override fun onError(utteranceId: String?) {
            abandonFocus()
            utteranceId?.toLongOrNull()?.let { listener?.onError(it) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            abandonFocus()
            utteranceId?.toLongOrNull()?.let { listener?.onError(it) }
        }
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
