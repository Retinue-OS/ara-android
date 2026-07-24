package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.protocol.PollingPolicy

class PollingPolicyTest {

    @Test
    fun nextInterval_backs_off_geometrically() {
        assertEquals(6_000, PollingPolicy.nextInterval(3_000, 2.0, 300_000))
        assertEquals(12_000, PollingPolicy.nextInterval(6_000, 2.0, 300_000))
    }

    @Test
    fun nextInterval_is_capped_at_max() {
        assertEquals(300_000, PollingPolicy.nextInterval(200_000, 2.0, 300_000))
        assertEquals(300_000, PollingPolicy.nextInterval(300_000, 2.0, 300_000))
    }

    @Test
    fun isExpired_at_and_after_deadline() {
        assertFalse(PollingPolicy.isExpired(startMs = 0, nowMs = 3_599_000, overallTimeoutMs = 3_600_000))
        assertTrue(PollingPolicy.isExpired(startMs = 0, nowMs = 3_600_000, overallTimeoutMs = 3_600_000))
        assertTrue(PollingPolicy.isExpired(startMs = 0, nowMs = 4_000_000, overallTimeoutMs = 3_600_000))
    }

    @Test
    fun shouldRetry_until_max_consecutive_failures() {
        assertTrue(PollingPolicy.shouldRetryAfterNetworkError(consecutiveFailures = 1, maxConsecutive = 10))
        assertTrue(PollingPolicy.shouldRetryAfterNetworkError(consecutiveFailures = 9, maxConsecutive = 10))
        assertFalse(PollingPolicy.shouldRetryAfterNetworkError(consecutiveFailures = 10, maxConsecutive = 10))
    }
}
