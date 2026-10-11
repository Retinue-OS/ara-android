package os.retinue.ara.wake

/**
 * openWakeWord's streaming feature pipeline (openwakeword/utils.py,
 * `AudioFeatures._streaming_features`), with the two ONNX models injected so
 * the buffering is unit-tested without them:
 *
 *  - audio arrives as 16 kHz mono PCM16 and is processed in 1280-sample (80 ms)
 *    chunks; leftovers wait for the next push;
 *  - per chunk, the melspectrogram model gets the last 1280 + 480 samples (three
 *    extra 10 ms hops of context, as `list(raw_data_buffer)[-n_samples-160*3:]`)
 *    as float values of the int16 samples (no scaling), and its frames are
 *    transformed with `x / 10 + 2` and appended to the mel buffer;
 *  - the mel buffer starts as 76 rows of ones (as in openWakeWord) and keeps
 *    at most 970 rows (10 s);
 *  - per chunk, the last 76 mel rows (a [76 x 32] window, the embedding model's
 *    `[1, 76, 32, 1]` input) give one 96-value embedding; consecutive chunks
 *    step the window by 8 mel frames;
 *  - the keyword model reads the last [embeddingFrames] embeddings (16 for the
 *    stock models, `[1, 16, 96]`).
 *
 * Difference from openWakeWord: it seeds the embedding buffer with embeddings
 * of 4 s of random noise so the classifier runs from the first chunk; here a
 * window is only produced once [embeddingFrames] real embeddings exist
 * (about 1.3 s after the mic opens).
 */
class WakeFeatures(
    private val melspectrogram: (FloatArray) -> Array<FloatArray>,
    private val embedding: (Array<FloatArray>) -> FloatArray,
    val embeddingFrames: Int = 16,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        const val MEL_CONTEXT = 480
        const val MEL_BINS = 32
        const val WINDOW = 76
        const val MEL_MAX = 970
        const val EMBEDDING_MAX = 120

        /** openWakeWord's default melspectrogram transform. */
        fun transform(x: Float): Float = x / 10f + 2f
    }

    private val raw = ShortArray(CHUNK + MEL_CONTEXT)
    private var rawFilled = 0
    private val pending = ShortArray(CHUNK)
    private var pendingCount = 0

    private val mel = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    init {
        resetBuffers()
    }

    /** Clear all state (the mic was reopened). */
    fun reset() {
        rawFilled = 0
        pendingCount = 0
        resetBuffers()
    }

    private fun resetBuffers() {
        mel.clear()
        repeat(WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        embeddings.clear()
    }

    /** Number of embeddings currently buffered (for tests and diagnostics). */
    val embeddingCount: Int get() = embeddings.size

    /**
     * Push [count] samples from [pcm]. For every completed 80 ms chunk that has
     * enough history, [onWindow] gets the keyword model's input: the last
     * [embeddingFrames] embeddings, oldest first.
     */
    fun push(pcm: ShortArray, count: Int = pcm.size, onWindow: (Array<FloatArray>) -> Unit) {
        var i = 0
        while (i < count) {
            val n = minOf(CHUNK - pendingCount, count - i)
            System.arraycopy(pcm, i, pending, pendingCount, n)
            pendingCount += n
            i += n
            if (pendingCount == CHUNK) {
                pendingCount = 0
                processChunk()?.let(onWindow)
            }
        }
    }

    private fun processChunk(): Array<FloatArray>? {
        appendRaw(pending)
        val input = FloatArray(rawFilled) { raw[raw.size - rawFilled + it].toFloat() }
        for (frame in melspectrogram(input)) {
            mel.addLast(FloatArray(frame.size) { transform(frame[it]) })
        }
        while (mel.size > MEL_MAX) mel.removeFirst()
        if (mel.size < WINDOW) return null
        val window = Array(WINDOW) { mel[mel.size - WINDOW + it] }
        embeddings.addLast(embedding(window))
        while (embeddings.size > EMBEDDING_MAX) embeddings.removeFirst()
        if (embeddings.size < embeddingFrames) return null
        return Array(embeddingFrames) { embeddings[embeddings.size - embeddingFrames + it] }
    }

    /** Shift [chunk] into the tail of the raw ring (kept right-aligned). */
    private fun appendRaw(chunk: ShortArray) {
        val keep = raw.size - chunk.size
        System.arraycopy(raw, chunk.size, raw, 0, keep)
        System.arraycopy(chunk, 0, raw, keep, chunk.size)
        rawFilled = minOf(raw.size, rawFilled + chunk.size)
    }
}
