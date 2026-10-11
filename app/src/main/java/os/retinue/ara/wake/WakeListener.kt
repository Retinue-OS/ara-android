package os.retinue.ara.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * One listening session: opens the microphone (16 kHz mono PCM16), runs
 * openWakeWord's pipeline on a dedicated thread, and reports a detection. The
 * mic is held only between [start] and [stop]; [WakeRuntime] decides when.
 * [stop] only signals: the thread lets go of the mic within one read (80 ms).
 * A listener started right after another waits for [previous] to let go first,
 * on its own thread, so the main thread never blocks on the audio thread.
 */
class WakeListener(
    context: Context,
    private val threshold: Float,
    private var previous: WakeListener? = null,
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val melFile: File = WakeModelFiles.melFile(context)
    private val embeddingFile: File = WakeModelFiles.embeddingFile(context)
    private val phraseFile: File = WakeModelFiles.phraseFile(context)

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "wake-listener").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** Stop and release the mic. Returns at once; the thread lets go of the mic shortly. */
    fun stop() {
        running = false
        thread?.interrupt()
    }

    /** Wait (off the main thread) until this listener's thread has released the mic. */
    private fun awaitReleased(timeoutMs: Long) {
        val t = thread ?: return
        if (t === Thread.currentThread()) return
        try {
            t.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // The caller (WakeRuntime) only starts a listener with RECORD_AUDIO granted.
    @SuppressLint("MissingPermission")
    private fun loop() {
        var models: OnnxWakeModels? = null
        var record: AudioRecord? = null
        try {
            previous?.awaitReleased(1_000)
            previous = null
            // Clear an interrupt from stop(); `running` says whether to go on.
            Thread.interrupted()
            if (!running) return
            models = OnnxWakeModels(melFile, embeddingFile, phraseFile)
            val m = models
            val features = WakeFeatures(m::melspectrogram, m::embedding, m.keywordFrames)
            val scorer = WakeScorer(threshold = threshold)

            val minBuffer = AudioRecord.getMinBufferSize(
                WakeFeatures.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBuffer, WakeFeatures.CHUNK * 2 * 4)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeFeatures.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("The microphone could not be opened")
            }
            record.startRecording()
            val buf = ShortArray(WakeFeatures.CHUNK)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("Microphone read failed ($n)")
                if (n == 0) continue
                var heard = false
                features.push(buf, n) { window ->
                    if (!heard && scorer.onScore(m.score(window), SystemClock.elapsedRealtime())) heard = true
                }
                if (heard && running) {
                    running = false
                    onDetected()
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "wake listening failed", e)
                running = false
                onError(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            record?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            models?.close()
        }
    }

    private companion object {
        const val TAG = "WakeListener"
    }
}
