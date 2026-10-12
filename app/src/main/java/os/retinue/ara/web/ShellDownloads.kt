package os.retinue.ara.web

import android.content.ContentValues
import android.content.Context
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
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import os.retinue.ara.R
import os.retinue.ara.net.AraHttp
import os.retinue.ara.shell.FileNames
import os.retinue.ara.shell.UrlRouter

/**
 * Downloads the page starts. A file on the configured origin is fetched with
 * the client certificate (and the WebView's cookies) into the public Downloads
 * collection; any other web URL goes to the browser.
 */
class ShellDownloads(context: Context, private val host: ShellHost) : DownloadListener {

    private companion object {
        const val TAG = "ShellDownloads"
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val TIMEOUT_S = 60L

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
        val name = FileNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimetype))
        if (contentLength > MAX_BYTES) {
            toast(R.string.download_too_large, name)
            return
        }
        val alias = host.certificateAlias
        val cookies = CookieManager.getInstance().getCookie(url)
        toast(R.string.download_started, name)
        executor.execute {
            try {
                fetch(url, router, alias, userAgent, cookies, name, mimetype)
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                toast(R.string.download_failed, name)
            }
        }
    }

    /** Fetch [url], following same-origin redirects by hand (the certificate stays on its origin). */
    private fun fetch(url: String, router: UrlRouter, alias: String?, userAgent: String?, cookies: String?, name: String, mimetype: String?) {
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
                    save(resp, name, mimetype)
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

    /** Stream the body into MediaStore Downloads; a partial file is removed. */
    private fun save(resp: Response, name: String, mimetype: String?) {
        val body = resp.body ?: throw IOException("Empty response")
        if (body.contentLength() > MAX_BYTES) {
            toast(R.string.download_too_large, name)
            return
        }
        val mime = mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
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
            var total = 0L
            val out = resolver.openOutputStream(uri) ?: throw IOException("Downloads is not writable")
            out.use {
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("File too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        toast(R.string.download_done, name)
    }

    private fun toast(res: Int, vararg args: Any) {
        main.post { Toast.makeText(app, app.getString(res, *args), Toast.LENGTH_SHORT).show() }
    }
}
