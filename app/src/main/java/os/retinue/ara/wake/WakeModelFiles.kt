package os.retinue.ara.wake

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.UrlRouter

/** Which of the three models are on the device. */
data class WakeModelStatus(val melspectrogram: Boolean, val embedding: Boolean, val phrase: Boolean) {
    val featuresReady: Boolean get() = melspectrogram && embedding
    val ready: Boolean get() = featuresReady && phrase
}

/**
 * The wake models live in app storage, never in the APK (licence and size, plan
 * §5): the two shared feature models are downloaded from the openWakeWord
 * release on request; the phrase model comes from a URL or a picked file.
 */
object WakeModelFiles {

    private const val DIR = "wake"
    private const val MEL = "melspectrogram.onnx"
    private const val EMBEDDING = "embedding_model.onnx"
    private const val PHRASE = "phrase.onnx"

    /** Refuse anything larger: the stock models are about 1.3 MB each. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    fun melFile(context: Context) = File(dir(context), MEL)
    fun embeddingFile(context: Context) = File(dir(context), EMBEDDING)
    fun phraseFile(context: Context) = File(dir(context), PHRASE)

    fun status(context: Context) = WakeModelStatus(
        melspectrogram = melFile(context).length() > 0,
        embedding = embeddingFile(context).length() > 0,
        phrase = phraseFile(context).length() > 0,
    )

    /** Download melspectrogram.onnx and embedding_model.onnx from the openWakeWord release. */
    suspend fun downloadFeatureModels(context: Context, settings: ShellSettings) {
        download(context, settings, ShellSettings.MELSPECTROGRAM_URL, melFile(context))
        download(context, settings, ShellSettings.EMBEDDING_URL, embeddingFile(context))
    }

    /** Download the phrase (keyword) model from [url], then check that it loads. */
    suspend fun downloadPhraseModel(context: Context, settings: ShellSettings, url: String) {
        download(context, settings, url.trim(), phraseFile(context))
        validate(context)
    }

    /** Copy a phrase model the user picked (Storage Access Framework). */
    suspend fun importPhraseModel(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
        input.use { writeAtomically(it, phraseFile(context)) }
        validate(context)
    }

    /**
     * Try to open all three models together, so a wrong or corrupt file is
     * reported in settings rather than when listening starts. Only runs when all
     * three are present.
     */
    suspend fun validate(context: Context) = withContext(Dispatchers.IO) {
        if (!status(context).ready) return@withContext
        try {
            OnnxWakeModels(melFile(context), embeddingFile(context), phraseFile(context)).close()
        } catch (e: Exception) {
            throw IOException("The model files could not be loaded: ${e.message}", e)
        }
    }

    private suspend fun download(context: Context, settings: ShellSettings, url: String, dest: File) =
        withContext(Dispatchers.IO) {
            val scheme = UrlRouter.schemeOf(url)
            if (scheme != "https" && scheme != "http") throw IOException("Not an http(s) URL: $url")
            val request = Request.Builder().url(url).get().build()
            clientFor(context, settings, url).newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                val body = resp.body ?: throw IOException("Empty response for $url")
                if (body.contentLength() > MAX_BYTES) throw IOException("File too large")
                body.byteStream().use { writeAtomically(it, dest) }
            }
        }

    /**
     * A model hosted on the configured Retinue origin (e.g. a trained "Hey
     * Retinue") is fetched with the client certificate; anything else with a
     * plain client.
     */
    private fun clientFor(context: Context, settings: ShellSettings, url: String): OkHttpClient {
        val alias = settings.certificateAlias
        if (alias != null && UrlRouter(settings.baseUrl).isConfiguredOrigin(url)) {
            return AraHttp.build(context, alias, TimeUnit.SECONDS.toMillis(60))
        }
        return OkHttpClient.Builder()
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun writeAtomically(input: InputStream, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var total = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    tmp.delete()
                    throw IOException("File too large")
                }
                out.write(buf, 0, n)
            }
        }
        if (total == 0L) {
            tmp.delete()
            throw IOException("The file is empty")
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not store ${dest.name}")
        }
    }
}
