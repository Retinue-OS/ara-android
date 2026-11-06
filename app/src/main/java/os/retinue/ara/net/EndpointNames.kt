package os.retinue.ara.net

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.credentials

/**
 * An endpoint's name is the instance's own: the `short_name` of its web
 * manifest, which the Retinue dashboard sets from its instance name. The shell
 * fetches it with the endpoint's own certificate or password and keeps the
 * last answer ([Endpoint.name]); the menus show that, and the host while it is
 * unknown (see EndpointLabels). Refreshed when an endpoint is saved or switched
 * to, when the endpoint list is shown, and after a page of the endpoint loaded,
 * at most once an hour per endpoint.
 */
object EndpointNames {

    private const val TAG = "EndpointNames"
    private const val TIMEOUT_S = 15L
    private const val MAX_BODY = 64 * 1024L
    private const val MAX_NAME = 60

    /** A fetched name is good for this long before it is asked for again. */
    const val REFRESH_MS = 60 * 60 * 1000L

    /** After a fetch that failed, the next try waits this long. */
    const val RETRY_MS = 5 * 60 * 1000L

    fun manifestUrl(base: String): String = "$base/manifest.webmanifest"

    /**
     * The name a manifest document gives the instance: `short_name`, else
     * `name`; trimmed, cut to [MAX_NAME] characters; null for none or for
     * something that is not a manifest.
     */
    fun parse(document: String?): String? {
        if (document.isNullOrBlank()) return null
        val root = try {
            Json.parseToJsonElement(document) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        for (key in listOf("short_name", "name")) {
            val value = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (value.isNotEmpty()) return value.take(MAX_NAME)
        }
        return null
    }

    /** Whether [endpoint]'s name is due for a fetch at [now] (see [REFRESH_MS]). */
    fun due(endpoint: Endpoint, now: Long, force: Boolean = false): Boolean =
        force || endpoint.name == null || now - endpoint.nameFetchedAt >= REFRESH_MS || endpoint.nameFetchedAt > now

    /** Fetch [endpoint]'s manifest and return its name; null when it cannot be had now. Network and KeyChain on IO. */
    suspend fun fetch(context: Context, endpoint: Endpoint): String? = withContext(Dispatchers.IO) {
        val base = endpoint.base ?: return@withContext null
        try {
            val client = AraHttp.build(
                context,
                endpoint.certificateAlias,
                TimeUnit.SECONDS.toMillis(TIMEOUT_S),
                endpoint.credentials(),
                endpoint.origin,
            )
            val request = Request.Builder()
                .url(manifestUrl(base))
                .header("Accept", "application/manifest+json, application/json;q=0.9, */*;q=0.1")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                if (body.contentLength() > MAX_BODY) return@withContext null
                parse(readUpTo(body.source(), MAX_BODY))
            }
        } catch (e: Exception) {
            Log.w(TAG, "manifest of ${endpoint.hostLabel} not fetched: ${e.message}")
            null
        }
    }

    /** At most [limit] bytes of [source], as text (a manifest is small; anything longer is cut). */
    private fun readUpTo(source: BufferedSource, limit: Long): String {
        val buffer = Buffer()
        while (buffer.size < limit) {
            if (source.read(buffer, limit - buffer.size) == -1L) break
        }
        return buffer.readUtf8()
    }
}
