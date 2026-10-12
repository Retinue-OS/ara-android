package os.retinue.ara.web

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.ShellVoice
import os.retinue.ara.shell.WebMessage

/**
 * Android TextToSpeech behind the page's `speechSynthesis` stand-in (see
 * assets/retinue-shell.js). The page keeps the queue and hands over one
 * utterance at a time; this side speaks it and reports start / boundary / end /
 * error by utterance id. Stops are not reported: the page has already decided
 * what a cancel or pause means for the utterance.
 *
 * [emit] runs a script in the page; it is always called on the main thread.
 */
class NativeSpeech(context: Context, private val emit: (String) -> Unit) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var ready = false
    private var failed = false
    private var pendingSpeak: WebMessage.Speak? = null
    private var voicesRequested = false

    /** The utterance currently handed to the engine (stale callbacks are ignored). */
    @Volatile private var currentId: Long = -1

    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
        if (ready) {
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(progress)
        }
        if (voicesRequested) sendVoices()
        pendingSpeak?.let {
            pendingSpeak = null
            speak(it)
        }
    }

    fun handle(message: WebMessage) {
        when (message) {
            is WebMessage.Speak -> speak(message)
            WebMessage.StopSpeaking -> stop()
            WebMessage.Voices -> sendVoices()
            else -> Unit
        }
    }

    private fun speak(m: WebMessage.Speak) {
        if (failed) {
            emit(ShellMessages.speechError(m.id, "synthesis-unavailable"))
            return
        }
        if (!ready) {
            // The engine binds asynchronously; the latest request wins.
            pendingSpeak?.let { emit(ShellMessages.speechError(it.id, "interrupted")) }
            pendingSpeak = m
            return
        }
        if (m.text.length > TextToSpeech.getMaxSpeechInputLength()) {
            emit(ShellMessages.speechError(m.id, "text-too-long"))
            return
        }
        applyVoice(m)
        tts.setSpeechRate(m.rate)
        tts.setPitch(if (m.pitch <= 0f) 1f else m.pitch)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, m.volume) }
        currentId = m.id
        requestFocus()
        val result = tts.speak(m.text, TextToSpeech.QUEUE_FLUSH, params, m.id.toString())
        if (result != TextToSpeech.SUCCESS) {
            currentId = -1
            abandonFocus()
            emit(ShellMessages.speechError(m.id, "synthesis-failed"))
        }
    }

    private fun applyVoice(m: WebMessage.Speak) {
        val voice = m.voice?.let { name -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == name } }
        if (voice != null) {
            tts.voice = voice
            return
        }
        val locale = m.lang?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = locale
        } else {
            tts.language = Locale.getDefault()
        }
    }

    private fun stop() {
        currentId = -1
        pendingSpeak = null
        if (ready) tts.stop()
        abandonFocus()
    }

    private fun sendVoices() {
        voicesRequested = true
        if (!ready && !failed) return
        val list = if (!ready) {
            emptyList()
        } else {
            val defaultName = runCatching { tts.defaultVoice?.name }.getOrNull()
            runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedBy { it.name }
                .map {
                    ShellVoice(
                        name = it.name,
                        lang = it.locale.toLanguageTag(),
                        localService = !it.isNetworkConnectionRequired,
                        isDefault = it.name == defaultName,
                    )
                }
        }
        emit(ShellMessages.voices(list))
    }

    /** The page went away (reload, navigation): stop speaking for it. */
    fun reset() = stop()

    fun shutdown() {
        currentId = -1
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        abandonFocus()
    }

    // Progress callbacks arrive on a binder thread; hop to main and drop the
    // ones of an utterance that is no longer current.
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = post(utteranceId) { ShellMessages.speechStart(it) }

        override fun onDone(utteranceId: String?) = post(utteranceId, finished = true) { ShellMessages.speechEnd(it) }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Requested by the page (cancel / pause / the next utterance): nothing to report.
        }

        @Deprecated("Superseded by onError(String?, Int)")
        override fun onError(utteranceId: String?) =
            post(utteranceId, finished = true) { ShellMessages.speechError(it, "synthesis-failed") }

        override fun onError(utteranceId: String?, errorCode: Int) =
            post(utteranceId, finished = true) { ShellMessages.speechError(it, errorName(errorCode)) }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
            post(utteranceId) { ShellMessages.speechBoundary(it, start) }
    }

    private fun post(utteranceId: String?, finished: Boolean = false, script: (Long) -> String) {
        val id = utteranceId?.toLongOrNull() ?: return
        main.post {
            if (id != currentId) return@post
            if (finished) {
                currentId = -1
                abandonFocus()
            }
            emit(script(id))
        }
    }

    private fun errorName(code: Int): String = when (code) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "network"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "language-unavailable"
        TextToSpeech.ERROR_INVALID_REQUEST -> "invalid-argument"
        TextToSpeech.ERROR_OUTPUT -> "audio-hardware"
        TextToSpeech.ERROR_SERVICE -> "synthesis-unavailable"
        else -> "synthesis-failed"
    }

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
