package os.retinue.ara.apps

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.credentials
import os.retinue.ara.net.AraHttp
import java.util.concurrent.TimeUnit

/**
 * The few requests the shell makes for chamber apps, to one endpoint, with its
 * client certificate and stored sign-in (the same client every native request
 * of the shell uses, [AraHttp]): the app list, the reminder listing, and the
 * reminder actions. Only paths on the endpoint's own origin; no redirect is
 * followed. Network and KeyChain run on IO.
 */
object AppsClient {

    private const val TAG = "AppsClient"
    private const val TIMEOUT_S = 20L
    private const val MAX_BODY = 1L shl 20
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** GET [path] on [endpoint]; the body, or null when it could not be had. */
    suspend fun get(context: Context, endpoint: Endpoint, path: String): String? = withContext(Dispatchers.IO) {
        val url = endpoint.base?.let { AppsLogic.sameOriginUrl(it, path) } ?: return@withContext null
        try {
            client(context, endpoint).newCall(
                Request.Builder().url(url).header("Accept", "application/json").build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $path on ${endpoint.hostLabel}: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                if (body.contentLength() > MAX_BODY) return@withContext null
                val buffer = Buffer()
                val source = body.source()
                while (buffer.size < MAX_BODY) {
                    if (source.read(buffer, MAX_BODY - buffer.size) == -1L) break
                }
                buffer.readUtf8()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $path on ${endpoint.hostLabel}: ${e.message}")
            null
        }
    }

    /**
     * The answers that end an action for good: the request is malformed (400),
     * the reminder or app is gone (404, 410), or too large (413). Anything else —
     * a refused sign-in (401, 403), a conflict, a server or network error —
     * keeps it in the outbox for the next fetch.
     */
    private val TERMINAL = setOf(400, 404, 410, 413, 422)

    /**
     * POST [body] (JSON) to [path] on [endpoint]. True when the server took it,
     * or gave an answer a retry would not change ([TERMINAL]).
     */
    suspend fun post(context: Context, endpoint: Endpoint, path: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = endpoint.base?.let { AppsLogic.sameOriginUrl(it, path) } ?: return@withContext true
            try {
                client(context, endpoint).newCall(
                    Request.Builder().url(url).post(body.toRequestBody(JSON)).build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) Log.w(TAG, "POST $path on ${endpoint.hostLabel}: HTTP ${resp.code}")
                    resp.isSuccessful || resp.code in TERMINAL
                }
            } catch (e: Exception) {
                Log.w(TAG, "POST $path on ${endpoint.hostLabel}: ${e.message}")
                false
            }
        }

    private fun client(context: Context, endpoint: Endpoint) = AraHttp.build(
        context.applicationContext,
        endpoint.certificateAlias,
        TimeUnit.SECONDS.toMillis(TIMEOUT_S),
        endpoint.credentials(),
        endpoint.origin,
    )
}
