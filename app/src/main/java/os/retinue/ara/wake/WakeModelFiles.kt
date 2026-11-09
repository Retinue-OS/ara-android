package os.retinue.ara.wake

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.data.credentials
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.UrlRouter

/** Which of the three models a listener needs are on the device (the phrase: the chosen one). */
data class WakeModelStatus(val melspectrogram: Boolean, val embedding: Boolean, val phrase: Boolean) {
    val featuresReady: Boolean get() = melspectrogram && embedding
    val ready: Boolean get() = featuresReady && phrase
}

/**
 * The wake models live in app storage, never in the APK (licence and size, plan
 * §5): the two shared feature models are downloaded from the openWakeWord
 * release; the phrase models form a library ([WakeModelCatalog] plus the
 * user's own uploads and URL downloads, see [WakeModelIndex]), one file per
 * model under `wake/phrases/`, named by its id. Which one is in use is a
 * setting, not a file. Callers serialise changes (one model job at a time).
 */
object WakeModelFiles {

    private const val DIR = "wake"
    private const val PHRASES = "phrases"
    private const val INDEX = "index.json"
    private const val MEL = "melspectrogram.onnx"
    private const val EMBEDDING = "embedding_model.onnx"

    /** The one phrase model of builds before the library; [migrateLegacy] moves it in. */
    private const val LEGACY_PHRASE = "phrase.onnx"

    /** Refuse anything larger: the stock models are about 1.3 MB each. */
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun phrasesDir(context: Context): File = File(dir(context), PHRASES).apply { mkdirs() }
    fun melFile(context: Context) = File(dir(context), MEL)
    fun embeddingFile(context: Context) = File(dir(context), EMBEDDING)

    /** The file of the phrase model [id] (present or not). */
    fun phraseFile(context: Context, id: String) = File(phrasesDir(context), WakeModelNames.fileName(id))

    /** The feature models and the phrase model [id] (null: none chosen, so never ready). */
    fun status(context: Context, id: String?) = WakeModelStatus(
        melspectrogram = melFile(context).length() > 0,
        embedding = embeddingFile(context).length() > 0,
        phrase = id != null && phraseFile(context, id).length() > 0,
    )

    /** Every model of the list, with whether its file is here. */
    fun library(context: Context): List<WakeModelEntry> =
        wakeModelList(userModels(context)) { phraseFile(context, it.id).length() > 0 }

    /** The list's model [id], or null when there is none such. */
    fun model(context: Context, id: String): WakePhraseModel? =
        WakeModelCatalog.builtin(id) ?: userModels(context).firstOrNull { it.id == id }

    private fun indexFile(context: Context) = File(phrasesDir(context), INDEX)

    private fun userModels(context: Context): List<WakePhraseModel> {
        val f = indexFile(context)
        return if (f.exists()) WakeModelIndex.decode(runCatching { f.readText() }.getOrNull()) else emptyList()
    }

    private fun writeUserModels(context: Context, models: List<WakePhraseModel>) {
        val f = indexFile(context)
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(WakeModelIndex.encode(models))
        commit(tmp, f)
    }

    private fun newUserId() = WakeModelIndex.USER_PREFIX + UUID.randomUUID().toString()

    /**
     * Move the single `wake/phrase.onnx` of earlier builds into the library,
     * once: as the catalog model it is byte for byte, else as a model of the
     * user's own. Returns the id it became (to be chosen in its stead), or
     * null when there was nothing to move. Synchronous and cheap (one hash of a
     * small file), so it can run before anything reads the library.
     */
    fun migrateLegacy(context: Context): String? {
        val legacy = File(dir(context), LEGACY_PHRASE)
        if (legacy.length() <= 0) {
            legacy.delete()
            return null
        }
        return try {
            val known = WakeModelCatalog.bySha256(sha256(legacy))
            val id = known?.id ?: newUserId()
            commit(legacy, phraseFile(context, id))
            if (known == null) {
                writeUserModels(context, userModels(context) + WakePhraseModel(id, "Earlier model", WakeModelSource.File))
            }
            id
        } catch (e: Exception) {
            Log.w(TAG, "could not move the earlier phrase model into the library", e)
            null
        }
    }

    /** Download melspectrogram.onnx and embedding_model.onnx from the openWakeWord release. */
    suspend fun downloadFeatureModels(context: Context, settings: ShellSettings) {
        val mel = melFile(context)
        val embedding = embeddingFile(context)
        val melTmp = download(context, settings, WakeModelCatalog.MELSPECTROGRAM_URL, mel)
        try {
            val embeddingTmp = download(context, settings, WakeModelCatalog.EMBEDDING_URL, embedding)
            try {
                check(melTmp, embeddingTmp, null)
                commit(melTmp, mel)
                commit(embeddingTmp, embedding)
            } finally {
                embeddingTmp.delete()
            }
        } finally {
            melTmp.delete()
        }
    }

    /** The feature models are fetched along with the first phrase model, so one tap is enough. */
    private suspend fun ensureFeatureModels(context: Context, settings: ShellSettings) {
        if (!status(context, null).featuresReady) downloadFeatureModels(context, settings)
    }

    /** Download the list's model [id] (a catalog or URL model) into its file. */
    suspend fun downloadModel(context: Context, settings: ShellSettings, id: String) {
        val model = model(context, id) ?: throw IOException("No such model")
        val url = model.url ?: throw IOException("${model.name} has no download address")
        ensureFeatureModels(context, settings)
        fetchInto(context, settings, url, phraseFile(context, id))
    }

    /**
     * Add a model from [url] to the list and download it; returns its id. A URL
     * already on the list is downloaded again into that entry.
     */
    suspend fun addFromUrl(context: Context, settings: ShellSettings, url: String): String {
        val trimmed = url.trim()
        requireHttp(trimmed)
        ensureFeatureModels(context, settings)
        val existing = userModels(context).firstOrNull { it.source == WakeModelSource.Url && it.url == trimmed }
        val id = existing?.id ?: newUserId()
        fetchInto(context, settings, trimmed, phraseFile(context, id))
        if (existing == null) {
            writeUserModels(context, userModels(context) + WakePhraseModel(id, WakeModelNames.ofUrl(trimmed), WakeModelSource.Url, trimmed))
        }
        return id
    }

    /**
     * Copy a model the user picked (Storage Access Framework) into the list,
     * under the file's name; returns its id. It is added only if it loads.
     */
    suspend fun importModel(context: Context, settings: ShellSettings, uri: Uri): String {
        val name = WakeModelNames.ofFile(displayName(context, uri))
        val id = newUserId()
        val dest = phraseFile(context, id)
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
        writeUserModels(context, userModels(context) + WakePhraseModel(id, name, WakeModelSource.File))
        // After the model is safely in: without the feature models it cannot be used yet.
        runCatching { ensureFeatureModels(context, settings) }.onFailure { Log.w(TAG, "feature models not downloaded", it) }
        return id
    }

    /**
     * Take model [id] off the phone: a catalog model's download is deleted (it
     * stays listed, to download again); a model of the user's own leaves the list.
     */
    suspend fun remove(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            phraseFile(context, id).delete()
            val user = userModels(context)
            if (user.any { it.id == id }) writeUserModels(context, user.filterNot { it.id == id })
        }
    }

    private suspend fun fetchInto(context: Context, settings: ShellSettings, url: String, dest: File) {
        val tmp = download(context, settings, url, dest)
        try {
            check(melFile(context), embeddingFile(context), tmp)
            commit(tmp, dest)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Try to open the feature models with the phrase model [id], so a wrong or
     * corrupt file is reported in settings rather than when listening starts.
     * Only runs when all three are present.
     */
    suspend fun validate(context: Context, id: String?) = withContext(Dispatchers.IO) {
        if (id == null || !status(context, id).ready) return@withContext
        try {
            OnnxWakeModels(melFile(context), embeddingFile(context), phraseFile(context, id)).close()
        } catch (e: Exception) {
            throw IOException("The model files could not be loaded: ${e.message}", e)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Check candidate files before they replace anything: all three together
     * when all are present, otherwise each present one on its own.
     */
    private suspend fun check(mel: File, embedding: File, phrase: File?) = withContext(Dispatchers.IO) {
        val files = listOfNotNull(mel, embedding, phrase)
        try {
            if (phrase != null && files.all { it.length() > 0 }) {
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
     * Retinue") is fetched as the WebView reaches it — the endpoint's client
     * certificate and stored password — by a client that follows no redirects,
     * so neither goes to another host; anything else with a plain client.
     */
    private fun clientFor(context: Context, settings: ShellSettings, url: String): OkHttpClient {
        val endpoint = settings.current
        if (endpoint != null && UrlRouter(endpoint.baseUrl).isConfiguredOrigin(url)) {
            return AraHttp.build(context, endpoint.certificateAlias, TimeUnit.SECONDS.toMillis(60), endpoint.credentials(), endpoint.origin)
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

    private const val TAG = "WakeModelFiles"
}
