package os.retinue.ara.protocol

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The single place that speaks the Retinue protocol.
 *
 * This mirrors the contract of the reference Signal Gateway
 * (`scripts/signal-gateway.py` in the retinue repo): a prompt is POSTed to
 * `<base>/message` with `{"message": ..., "async": true}`, the backend returns a
 * job handle (`job_url`), and the client polls `GET <base><job_url>` until the
 * job reports `done` (with a `response`) or `error`.
 *
 * All of this parsing lives here — never in the UI, service, or ViewModel — so
 * there is exactly one implementation of the protocol, and it is pure and
 * unit-testable (no Android, no I/O).
 */
object RetinueProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Fallback message when Retinue refuses (HTTP 403) without a body. */
    const val DEFAULT_BLOCK_MESSAGE: String =
        "This identity is not authorized to use Retinue."

    /**
     * Build the prompt exactly as the Signal Gateway does: the recognized text
     * followed by an instruction to answer in the same language. The language is
     * expressed as an ISO code so the same convention carries across clients.
     */
    fun buildPrompt(text: String, languageTag: String): String =
        "$text\n\nPlease answer in the same language as the question " +
            "(ISO language code: $languageTag)."

    // ── POST /message ────────────────────────────────────────────────────────

    sealed interface SubmitResult {
        /** Backend accepted the job; poll [jobUri]. */
        data class Accepted(val jobUri: String) : SubmitResult

        /** HTTP 403 — requester identity refused. Carries a user-facing message. */
        data class Blocked(val message: String) : SubmitResult

        /** Older synchronous backend that answered inline instead of a job. */
        data class InlineAnswer(val answer: String, val entryUrl: String?) : SubmitResult

        /** The response could not be understood. */
        data class Malformed(val detail: String) : SubmitResult
    }

    /**
     * Parse the `POST /message` response.
     *
     * @param messageUrl the absolute URL the prompt was POSTed to; used to
     *   resolve a relative `job_url` (e.g. "/jobs/abc") into an absolute URI.
     */
    fun parseSubmit(httpCode: Int, body: String, messageUrl: String): SubmitResult {
        if (httpCode == 403) {
            val msg = runCatching {
                json.parseToJsonElement(body).jsonObject["response"]?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return SubmitResult.Blocked(msg ?: DEFAULT_BLOCK_MESSAGE)
        }
        if (httpCode !in 200..299) {
            return SubmitResult.Malformed("HTTP $httpCode")
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return SubmitResult.Malformed("response was not JSON")

        val jobPath = obj["job_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (jobPath != null) {
            val resolved = resolveJobUri(messageUrl, jobPath)
                ?: return SubmitResult.Malformed("invalid job_url: $jobPath")
            return SubmitResult.Accepted(resolved)
        }
        // No job handle: accept an inline synchronous answer if present.
        val inline = obj["response"]?.jsonPrimitive?.content
        if (inline != null) {
            val entry = obj["entry_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            return SubmitResult.InlineAnswer(inline.trim(), entry)
        }
        return SubmitResult.Malformed("no job_url or response field")
    }

    // ── GET /jobs/{id} ───────────────────────────────────────────────────────

    sealed interface JobPoll {
        data object Pending : JobPoll
        data class Done(val answer: String, val entryUrl: String?) : JobPoll
        data class Failed(val error: String?) : JobPoll

        /** HTTP 404 — the job expired or is unknown before completion. */
        data object Expired : JobPoll
        data class Malformed(val detail: String) : JobPoll
    }

    /** Parse a `GET <job_url>` poll response. */
    fun parseJobStatus(httpCode: Int, body: String): JobPoll {
        if (httpCode == 404) return JobPoll.Expired
        if (httpCode !in 200..299) return JobPoll.Malformed("HTTP $httpCode")
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return JobPoll.Malformed("response was not JSON")
        return when (obj["status"]?.jsonPrimitive?.content) {
            "done" -> JobPoll.Done(
                answer = obj["response"]?.jsonPrimitive?.content?.trim().orEmpty(),
                entryUrl = obj["entry_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            )
            "error" -> JobPoll.Failed(obj["error"]?.jsonPrimitive?.content)
            "pending" -> JobPoll.Pending
            else -> JobPoll.Malformed("unknown status")
        }
    }

    /**
     * Resolve a job path against the message URL, the way Python's `urljoin`
     * does. Returns null when the result is not a usable absolute http(s) URI
     * (so callers can surface a "malformed job URI" error).
     */
    fun resolveJobUri(messageUrl: String, jobPath: String): String? {
        val resolved = runCatching { URI(messageUrl).resolve(jobPath) }.getOrNull()
            ?: return null
        val scheme = resolved.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (resolved.host.isNullOrBlank()) return null
        return resolved.toString()
    }
}
