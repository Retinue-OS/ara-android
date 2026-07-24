package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.protocol.RetinueProtocol
import os.retinue.ara.protocol.RetinueProtocol.JobPoll
import os.retinue.ara.protocol.RetinueProtocol.SubmitResult

class RetinueProtocolTest {

    private val messageUrl = "https://agents.example.com/message"

    @Test
    fun buildPrompt_appends_language_line() {
        val p = RetinueProtocol.buildPrompt("what is the weather", "de")
        assertTrue(p.startsWith("what is the weather"))
        assertTrue(p.contains("ISO language code: de"))
    }

    // ── parseSubmit ──────────────────────────────────────────────────────────

    @Test
    fun parseSubmit_accepted_resolves_relative_job_url() {
        val body = """{"status":"pending","job_id":"abc","job_url":"/jobs/abc"}"""
        val r = RetinueProtocol.parseSubmit(202, body, messageUrl)
        assertEquals(SubmitResult.Accepted("https://agents.example.com/jobs/abc"), r)
    }

    @Test
    fun parseSubmit_blocked_uses_response_message() {
        val body = """{"error":"forbidden","response":"not authorised"}"""
        val r = RetinueProtocol.parseSubmit(403, body, messageUrl)
        assertEquals(SubmitResult.Blocked("not authorised"), r)
    }

    @Test
    fun parseSubmit_blocked_without_body_uses_default() {
        val r = RetinueProtocol.parseSubmit(403, "", messageUrl)
        assertEquals(SubmitResult.Blocked(RetinueProtocol.DEFAULT_BLOCK_MESSAGE), r)
    }

    @Test
    fun parseSubmit_inline_answer_when_no_job_url() {
        val body = """{"response":"  hello  ","entry_url":"/conversation/2026#e1"}"""
        val r = RetinueProtocol.parseSubmit(200, body, messageUrl)
        assertEquals(SubmitResult.InlineAnswer("hello", "/conversation/2026#e1"), r)
    }

    @Test
    fun parseSubmit_malformed_on_empty_object() {
        assertTrue(RetinueProtocol.parseSubmit(200, "{}", messageUrl) is SubmitResult.Malformed)
    }

    @Test
    fun parseSubmit_malformed_on_server_error() {
        assertTrue(RetinueProtocol.parseSubmit(500, "boom", messageUrl) is SubmitResult.Malformed)
    }

    @Test
    fun parseSubmit_malformed_job_url() {
        val body = """{"job_url":"ftp://evil/x"}"""
        assertTrue(RetinueProtocol.parseSubmit(202, body, messageUrl) is SubmitResult.Malformed)
    }

    // ── parseJobStatus ───────────────────────────────────────────────────────

    @Test
    fun parseJobStatus_pending() {
        assertEquals(JobPoll.Pending, RetinueProtocol.parseJobStatus(200, """{"status":"pending"}"""))
    }

    @Test
    fun parseJobStatus_done_extracts_answer_and_entry() {
        val body = """{"status":"done","response":"the answer","entry_url":"/e"}"""
        assertEquals(JobPoll.Done("the answer", "/e"), RetinueProtocol.parseJobStatus(200, body))
    }

    @Test
    fun parseJobStatus_error_carries_message() {
        val body = """{"status":"error","error":"kaboom"}"""
        assertEquals(JobPoll.Failed("kaboom"), RetinueProtocol.parseJobStatus(200, body))
    }

    @Test
    fun parseJobStatus_expired_on_404() {
        assertEquals(JobPoll.Expired, RetinueProtocol.parseJobStatus(404, ""))
    }

    @Test
    fun parseJobStatus_malformed_on_unknown_status() {
        assertTrue(RetinueProtocol.parseJobStatus(200, """{"status":"weird"}""") is JobPoll.Malformed)
    }

    // ── resolveJobUri ────────────────────────────────────────────────────────

    @Test
    fun resolveJobUri_absolute_path() {
        assertEquals(
            "https://agents.example.com/jobs/x",
            RetinueProtocol.resolveJobUri(messageUrl, "/jobs/x"),
        )
    }

    @Test
    fun resolveJobUri_relative_path() {
        assertEquals(
            "https://agents.example.com/api/jobs/x",
            RetinueProtocol.resolveJobUri("https://agents.example.com/api/message", "jobs/x"),
        )
    }

    @Test
    fun resolveJobUri_absolute_url_wins() {
        assertEquals(
            "https://other.example.com/j",
            RetinueProtocol.resolveJobUri(messageUrl, "https://other.example.com/j"),
        )
    }

    @Test
    fun resolveJobUri_rejects_non_http_scheme() {
        assertNull(RetinueProtocol.resolveJobUri(messageUrl, "ftp://x/y"))
    }

    @Test
    fun resolveJobUri_rejects_garbage() {
        assertNull(RetinueProtocol.resolveJobUri("not a url", "/jobs/x"))
    }
}
