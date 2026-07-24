package os.retinue.ara.protocol

import kotlin.math.min

/**
 * Pure polling cadence and give-up decisions, mirroring the Signal Gateway:
 * start responsive, back off geometrically up to a ceiling, and stop at an
 * overall deadline. Kept separate from the network code so retry/timeout logic
 * is unit-testable.
 */
object PollingPolicy {

    /** Next interval after a poll, capped at [maxMs]. */
    fun nextInterval(currentMs: Long, backoff: Double, maxMs: Long): Long =
        min((currentMs * backoff).toLong(), maxMs).coerceAtLeast(currentMs.coerceAtMost(maxMs))

    /** Whether the overall job budget has been exhausted. */
    fun isExpired(startMs: Long, nowMs: Long, overallTimeoutMs: Long): Boolean =
        nowMs - startMs >= overallTimeoutMs

    /**
     * After a transient network error, keep retrying until too many failures
     * pile up back-to-back. A successful poll resets the counter (caller's job).
     */
    fun shouldRetryAfterNetworkError(consecutiveFailures: Int, maxConsecutive: Int): Boolean =
        consecutiveFailures < maxConsecutive
}
