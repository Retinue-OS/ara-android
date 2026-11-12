package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * When the page hears a keyboard event (PageEventHold): the end of a request
 * waits for the dashboard to be in front again, at most HOLD_MS, and nothing
 * overtakes what waits.
 */
class PageEventHoldTest {

    private var now = 10_000L
    private val hold = PageEventHold<KeyboardEvent>(KeyboardLogic::isFinal) { now }

    private val typed = KeyboardEvent.typed("k1", 1)
    private val typed2 = KeyboardEvent.typed("t2", 5)
    private val cancelled = KeyboardEvent.error("t3", KeyboardError.Cancelled)
    private val secretTyped = KeyboardEvent(KeyboardEvent.SECRET_TYPED, "s1")
    private val secretCancelled = KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, "s2")
    private val status = KeyboardEvent.status

    @Test
    fun theEndsOfARequestAreFinalAndAStatusIsNot() {
        for (e in listOf(typed, cancelled, secretTyped, secretCancelled)) assertTrue(KeyboardLogic.isFinal(e), e.toString())
        assertEquals(false, KeyboardLogic.isFinal(status))
    }

    @Test
    fun inFrontEverythingGoesAtOnce() {
        assertEquals(listOf(typed), hold.offer(typed, inFront = true))
        assertEquals(listOf(status), hold.offer(status, inFront = true))
        assertTrue(hold.isEmpty)
        assertNull(hold.dueAt())
    }

    @Test
    fun anEndWaitsUntilTheDashboardIsInFrontAgain() {
        // The approved key is typed while the confirmation is still closing.
        assertEquals(emptyList(), hold.offer(typed, inFront = false))
        assertEquals(now + PageEventHold.HOLD_MS, hold.dueAt())
        now += 200
        assertEquals(emptyList(), hold.release(inFront = false), "not in front yet, and not due")
        now += 100
        assertEquals(listOf(typed), hold.release(inFront = true))
        assertTrue(hold.isEmpty)
        assertNull(hold.dueAt())
    }

    @Test
    fun anEndGoesByItselfOnceItsTimeIsUp() {
        assertEquals(emptyList(), hold.offer(secretCancelled, inFront = false))
        now += PageEventHold.HOLD_MS - 1
        assertEquals(emptyList(), hold.release(inFront = false))
        now += 1
        assertEquals(listOf(secretCancelled), hold.release(inFront = false))
        assertTrue(hold.isEmpty)
    }

    @Test
    fun aStatusAloneIsNotHeld() {
        assertEquals(listOf(status), hold.offer(status, inFront = false))
        assertTrue(hold.isEmpty)
    }

    @Test
    fun twoEndsKeepTheirOrderAndEachItsOwnTime() {
        assertEquals(emptyList(), hold.offer(typed, inFront = false))
        now += 1000
        // A final event behind a waiting one waits behind it.
        assertEquals(emptyList(), hold.offer(typed2, inFront = false))
        now += 500
        // The first is due; the second came 500 ms ago and waits on.
        assertEquals(listOf(typed), hold.release(inFront = false))
        assertEquals(now + 1000, hold.dueAt())
        now += 999
        assertEquals(emptyList(), hold.release(inFront = false))
        now += 1
        assertEquals(listOf(typed2), hold.release(inFront = false))
    }

    @Test
    fun nothingOvertakesAWaitingEnd() {
        assertEquals(emptyList(), hold.offer(typed, inFront = false))
        // A status that comes meanwhile goes after it, not before.
        assertEquals(emptyList(), hold.offer(status, inFront = false))
        assertEquals(emptyList(), hold.offer(cancelled, inFront = false))
        assertEquals(listOf(typed, status, cancelled), hold.release(inFront = true))
    }

    @Test
    fun whatWaitsBehindADueEndGoesWithItUpToTheNextThatMustWait() {
        assertEquals(emptyList(), hold.offer(typed, inFront = false))
        assertEquals(emptyList(), hold.offer(status, inFront = false))
        now += 1000
        assertEquals(emptyList(), hold.offer(typed2, inFront = false))
        now += 500
        // The status went once nothing before it waited; typed2 still waits.
        assertEquals(listOf(typed, status), hold.release(inFront = false))
        // An event that comes while one waits goes behind it, even in front: the release takes both.
        assertEquals(listOf(typed2, secretTyped), hold.offer(secretTyped, inFront = true))
    }

    @Test
    fun clearDropsWhatWaits() {
        hold.offer(typed, inFront = false)
        hold.offer(status, inFront = false)
        hold.clear()
        assertTrue(hold.isEmpty)
        assertNull(hold.dueAt())
        assertEquals(emptyList(), hold.release(inFront = true))
    }
}
