package os.retinue.ara.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import os.retinue.ara.protocol.RetinueProtocol

/**
 * The one Retinue transport. It only knows how to POST a prompt and GET a job;
 * all protocol *parsing* is delegated to [RetinueProtocol]. Nothing else in the
 * app talks HTTP to Retinue.
 *
 * Network/TLS failures surface as [java.io.IOException] (its subclass
 * `SSLException` for handshake/cert problems) so the caller can decide whether a
 * failure is transient (retry) or terminal (surface an error).
 */
class RetinueClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** POST the prompt to `<base>/message`. Returns a parsed submit result. */
    suspend fun submit(
        client: OkHttpClient,
        messageUrl: String,
        prompt: String,
    ): RetinueProtocol.SubmitResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("message", prompt)
            put("async", true)
        }.toString()
        val request = Request.Builder()
            .url(messageUrl)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            RetinueProtocol.parseSubmit(resp.code, body, messageUrl)
        }
    }

    /** GET the job URI once. Returns a parsed poll result. */
    suspend fun poll(
        client: OkHttpClient,
        jobUri: String,
    ): RetinueProtocol.JobPoll = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(jobUri).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            RetinueProtocol.parseJobStatus(resp.code, body)
        }
    }
}
