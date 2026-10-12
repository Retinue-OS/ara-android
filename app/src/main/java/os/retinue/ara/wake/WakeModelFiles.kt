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
    private const val MAX_REDIRECTS = 5

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
        val mel = melFile(context)
        val embedding = embeddingFile(context)
        val melTmp = download(context, settings, ShellSettings.MELSPECTROGRAM_URL, mel)
        try {
            val embeddingTmp = download(context, settings, ShellSettings.EMBEDDING_URL, embedding)
            try {
                check(melTmp, embeddingTmp, phraseFile(context))
                commit(melTmp, mel)
                commit(embeddingTmp, embedding)
            } finally {
                embeddingTmp.delete()
            }
        } finally {
            melTmp.delete()
        }
    }

    /** Download the phrase (keyword) model from [url]; it replaces the old one only if it loads. */
    suspend fun downloadPhraseModel(context: Context, settings: ShellSettings, url: String) {
        val dest = phraseFile(context)
        val tmp = download(context, settings, url.trim(), dest)
        try {
            check(melFile(context), embeddingFile(context), tmp)
            commit(tmp, dest)
        } finally {
            tmp.delete()
        }
    }

    /** Copy a phrase model the user picked (Storage Access Framework); it replaces the old one only if it loads. */
    suspend fun importPhraseModel(context: Context, uri: Uri) {
        val dest = phraseFile(context)
        val tmp = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
            input.use { writeTemp(it, dest) }
        }
        try {
            check(melFile(context), embeddingFile(context), tmp)
            commit(tmp, dest)
        } finally {
            tmp.delete()
        }
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

    /**
     * Check candidate files before they replace anything: all three together
     * when all are present, otherwise each present one on its own.
     */
    private suspend fun check(mel: File, embedding: File, phrase: File) = withContext(Dispatchers.IO) {
        val files = listOf(mel, embedding, phrase)
        try {
            if (files.all { it.length() > 0 }) {
                OnnxWakeModels(mel, embedding, phrase).close()
            } else {
                files.filter { it.length() > 0 }.forEach { OnnxWakeModels.checkLoads(it) }
            }
        } catch (e: Exception) {
            throw IOException("The model files could not be loaded: ${e.message}", e)
        }
    }

    /**
     * Fetch [url] into a temporary file next to [dest] and return it (the
     * caller checks it, then [commit]s or deletes it). A redirect from the
     * client-certificate client is followed by hand: on the configured origin
     * with the certificate, anywhere else without it.
     */
    private suspend fun download(context: Context, settings: ShellSettings, url: String, dest: File): File =
        withContext(Dispatchers.IO) {
            requireHttp(url)
            var target = url
            repeat(MAX_REDIRECTS + 1) {
                val request = Request.Builder().url(target).get().build()
                val location: String = clientFor(context, settings, target).newCall(request).execute().use { resp ->
                    if (!resp.isRedirect) {
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $target")
                        val body = resp.body ?: throw IOException("Empty response for $target")
                        if (body.contentLength() > MAX_BYTES) throw IOException("File too large")
                        return@withContext body.byteStream().use { writeTemp(it, dest) }
                    }
                    val header = resp.header("Location") ?: throw IOException("Redirect without a location for $target")
                    resp.request.url.resolve(header)?.toString() ?: throw IOException("Bad redirect for $target")
                }
                requireHttp(location)
                target = location
            }
            throw IOException("Too many redirects for $url")
        }

    private fun requireHttp(url: String) {
        val scheme = UrlRouter.schemeOf(url)
        if (scheme != "https" && scheme != "http") throw IOException("Not an http(s) URL: $url")
    }

    /**
     * A model hosted on the configured Retinue origin (e.g. a trained "Hey
     * Retinue") is fetched with the client certificate — by a client that
     * follows no redirects, so the certificate never goes to another host;
     * anything else with a plain client.
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

    /** Write [input] to `<dest>.part`; returns that file. Nothing of [dest] is touched. */
    private fun writeTemp(input: InputStream, dest: File): File {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("File too large")
                    out.write(buf, 0, n)
                }
            }
            if (total == 0L) throw IOException("The file is empty")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return tmp
    }

    /** Replace [dest] with the checked [tmp] (a rename within the same directory). */
    private fun commit(tmp: File, dest: File) {
        if (!tmp.renameTo(dest)) {
            // Some file systems refuse to rename over an existing file.
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not store ${dest.name}")
        }
    }
}
