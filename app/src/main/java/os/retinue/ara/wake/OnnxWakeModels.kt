package os.retinue.ara.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * The three openWakeWord ONNX models, run with ONNX Runtime. Only the ORT Java
 * API is used (identical on Android and the JVM), so this class is exercised
 * against the real release models in a plain JVM as well.
 *
 * Shapes (openWakeWord v0.5.1 release models):
 *  - melspectrogram.onnx: input `[1, n]` float (int16 sample values), output
 *    `[1, 1, frames, 32]`;
 *  - embedding_model.onnx: input `[1, 76, 32, 1]`, output `[1, 1, 1, 96]`;
 *  - the keyword model (e.g. hey_jarvis_v0.1.onnx): input `[1, 16, 96]`,
 *    output `[1, 1]` — the wake score in 0..1.
 * Input names are read from the sessions rather than assumed.
 */
class OnnxWakeModels(melFile: File, embeddingFile: File, keywordFile: File) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel: OrtSession = env.createSession(melFile.absolutePath, options)
    private val embed: OrtSession = env.createSession(embeddingFile.absolutePath, options)
    private val keyword: OrtSession = env.createSession(keywordFile.absolutePath, options)

    private val melInput = mel.inputNames.first()
    private val embedInput = embed.inputNames.first()
    private val keywordInput = keyword.inputNames.first()

    /** How many embeddings the keyword model reads (16 for the stock models). */
    val keywordFrames: Int = run {
        val info = keyword.inputInfo[keywordInput]?.info as? TensorInfo
        val shape = info?.shape
        val n = if (shape != null && shape.size >= 2) shape[1] else -1L
        if (n > 0) n.toInt() else 16
    }

    /** Mel frames (each [WakeFeatures.MEL_BINS] values, untransformed) for raw samples. */
    fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        val out = runOne(mel, melInput, samples, longArrayOf(1, samples.size.toLong()))
        val bins = WakeFeatures.MEL_BINS
        val frames = out.size / bins
        return Array(frames) { f -> out.copyOfRange(f * bins, (f + 1) * bins) }
    }

    /** One embedding for a [76 x 32] mel window. */
    fun embedding(window: Array<FloatArray>): FloatArray {
        val bins = WakeFeatures.MEL_BINS
        val flat = FloatArray(window.size * bins)
        window.forEachIndexed { r, row -> System.arraycopy(row, 0, flat, r * bins, bins) }
        return runOne(embed, embedInput, flat, longArrayOf(1, window.size.toLong(), bins.toLong(), 1))
    }

    /** The wake score for the last [keywordFrames] embeddings. */
    fun score(features: Array<FloatArray>): Float {
        val dim = features.firstOrNull()?.size ?: return 0f
        val flat = FloatArray(features.size * dim)
        features.forEachIndexed { r, row -> System.arraycopy(row, 0, flat, r * dim, dim) }
        val out = runOne(keyword, keywordInput, flat, longArrayOf(1, features.size.toLong(), dim.toLong()))
        return out.firstOrNull() ?: 0f
    }

    private fun runOne(session: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val value = result.get(0) as OnnxTensor
                val buffer = value.floatBuffer
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                return out
            }
        }
    }

    override fun close() {
        runCatching { keyword.close() }
        runCatching { embed.close() }
        runCatching { mel.close() }
        runCatching { options.close() }
    }
}
