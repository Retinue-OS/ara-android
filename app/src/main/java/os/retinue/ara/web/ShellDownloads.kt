package os.retinue.ara.web

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import os.retinue.ara.R
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.ContentDisposition
import os.retinue.ara.shell.FileNames
import os.retinue.ara.shell.UrlRouter

/**
 * Files the WebView cannot show itself. A file on the configured origin is
 * fetched with the client certificate (and the WebView's cookies); any other
 * web URL goes to the browser.
 *
 * What becomes of it follows the response's Content-Disposition, as in a
 * browser. `inline` is the page asking to see the file (the web app's view
 * link, for a PDF the WebView cannot render): it opens in the app Android has
 * for its type, from a private copy in the cache, so reading the same invoice
 * twice leaves nothing behind in Downloads. Anything else is saved to the
 * public Downloads collection, and so is a file no installed app opens.
 */
class ShellDownloads(context: Context, private val host: ShellHost) : DownloadListener {

    private companion object {
        const val TAG = "ShellDownloads"
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val TIMEOUT_S = 60L

        /** The private copies opened in a viewer, under the cache (res/xml/shell_files.xml). */
        const val VIEW_DIR = "view"

        /** A viewer may still be reading a copy: one is removed once a day old, when the next is made. */
        val VIEW_KEEP_MS = TimeUnit.DAYS.toMillis(1)

        /** One download at a time, shared by every WebView the shell creates. */
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "download").apply { isDaemon = true }
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        val router = host.router
        if (!router.isConfiguredOrigin(url)) {
            val scheme = UrlRouter.schemeOf(url)
            // blob: and data: downloads exist only inside the page.
            if (scheme == "http" || scheme == "https") ShellIntents.openInBrowser(app, url) else toast(R.string.download_unsupported)
            return
        }
        // URLUtil.guessFileName takes a name only from a bare `attachment; filename="…"`
        // (not from `inline`, nor past an RFC 6266 `filename*`): the header is read here,
        // and URLUtil only names what it does not.
        val disposition = ContentDisposition.parse(contentDisposition)
        val name = FileNames.sanitize(disposition?.filename ?: URLUtil.guessFileName(url, contentDisposition, mimetype))
        val view = disposition?.inline == true
        if (contentLength > MAX_BYTES) {
            toast(R.string.download_too_large, name)
            return
        }
        val alias = host.certificateAlias
        val cookies = CookieManager.getInstance().getCookie(url)
        toast(if (view) R.string.view_started else R.string.download_started, name)
        executor.execute {
            try {
                fetch(url, router, alias, userAgent, cookies) { resp ->
                    if (view) open(resp, name, mimetype) else save(resp, name, mimetype)
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                toast(if (view) R.string.view_failed else R.string.download_failed, name)
            }
        }
    }

    /**
     * Fetch [url], following same-origin redirects by hand (the certificate stays on its origin),
     * and hand the final response to [handle].
     */
    private fun fetch(
        url: String,
        router: UrlRouter,
        alias: String?,
        userAgent: String?,
        cookies: String?,
        handle: (Response) -> Unit,
    ) {
        val client = client(alias)
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(target).get().apply {
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                if (!cookies.isNullOrBlank()) header("Cookie", cookies)
            }.build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isRedirect) {
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    handle(resp)
                    return
                }
                val location = resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() }
                    ?: throw IOException("Bad redirect")
                if (!router.isConfiguredOrigin(location)) {
                    main.post { ShellIntents.openInBrowser(app, location) }
                    return
                }
                target = location
            }
        }
        throw IOException("Too many redirects")
    }

    private fun client(alias: String?): OkHttpClient {
        // No call timeout: a large file may take a while; a stalled one times out on read.
        val base = if (alias != null) {
            AraHttp.build(app, alias, TimeUnit.SECONDS.toMillis(TIMEOUT_S)).newBuilder()
        } else {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        }
        return base.callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** Save the body to Downloads. */
    private fun save(resp: Response, name: String, mimetype: String?) {
        val body = resp.body ?: throw IOException("Empty response")
        if (body.contentLength() > MAX_BYTES) {
            toast(R.string.download_too_large, name)
            return
        }
        body.byteStream().use { store(it, name, mimeOf(body, name, mimetype)) }
        toast(R.string.download_done, name)
    }

    /**
     * Copy the body into the cache and open it in the app Android has for its
     * type; when there is none, the copy goes to Downloads instead.
     */
    private fun open(resp: Response, name: String, mimetype: String?) {
        val body = resp.body ?: throw IOException("Empty response")
        if (body.contentLength() > MAX_BYTES) {
            toast(R.string.download_too_large, name)
            return
        }
        val mime = mimeOf(body, name, mimetype)
        val root = File(app.cacheDir, VIEW_DIR)
        val cutoff = System.currentTimeMillis() - VIEW_KEEP_MS
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        // A directory per copy, so two files of the same name never meet; the
        // viewer is shown the file's own name (FileProvider's display name).
        val dir = File(root, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("Cache is not writable")
        val file = File(dir, name)
        try {
            file.outputStream().use { out -> body.byteStream().use { copyCapped(it, out) } }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try {
                app.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Nothing on the phone shows this type: keep the file where the user finds it.
                executor.execute {
                    try {
                        file.inputStream().use { store(it, name, mime) }
                        toast(R.string.view_no_app, name)
                    } catch (err: Exception) {
                        Log.w(TAG, "could not save $name", err)
                        toast(R.string.download_failed, name)
                    } finally {
                        dir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                // SecurityException and the like: never crash the shell.
                Log.w(TAG, "could not open $name", e)
                toast(R.string.view_failed, name)
            }
        }
    }

    private fun mimeOf(body: ResponseBody, name: String, mimetype: String?): String =
        mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Stream [input] into MediaStore Downloads; a partial file is removed. */
    private fun store(input: InputStream, name: String, mime: String) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads is not available")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Downloads is not writable")
            out.use { copyCapped(input, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Copy [input] to [out], refusing more than [MAX_BYTES] (a body may not declare its length). */
    private fun copyCapped(input: InputStream, out: OutputStream) {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("File too large")
            out.write(buf, 0, n)
        }
    }

    private fun toast(res: Int, vararg args: Any) {
        main.post { Toast.makeText(app, app.getString(res, *args), Toast.LENGTH_SHORT).show() }
    }
}
