package os.retinue.ara.keyboard

/**
 * When the page hears a keyboard event: the end of a request ([isFinal])
 * that comes while the dashboard is not in front (ShellDecisions.mayType,
 * the gate's own notion) waits until it is in front again, or [HOLD_MS]
 * after it came, whichever is first. The dashboard sends its next step as
 * soon as it hears the end of the one before; a key approved on the phone is
 * typed in 24 ms, while the confirmation (or the password dialog) is still
 * closing and the dashboard not yet resumed and focused, so that step would
 * be refused as `not-in-front` and the rest of the sequence dropped.
 *
 * Order is kept: an event that comes while one waits goes behind it, final
 * or not (a `status` too, so the page never reads a status of a moment after
 * an end it has not heard yet); once nothing before it waits, an event that
 * is not final goes at once. What waits is only held here: whether the page
 * gets it at all is decided when it goes ([KeyboardLogic.eventForPage]).
 * [clock]: elapsed ms. Main thread.
 */
class PageEventHold<E>(private val isFinal: (E) -> Boolean, private val clock: () -> Long) {

    private class Waiting<E>(val event: E, val at: Long)

    private val waiting = ArrayDeque<Waiting<E>>()

    val isEmpty: Boolean get() = waiting.isEmpty()

    /** [event] happened: what goes to the page now, in order — [event] itself, or nothing while it waits. */
    fun offer(event: E, inFront: Boolean): List<E> {
        if (waiting.isEmpty() && (inFront || !isFinal(event))) return listOf(event)
        waiting.addLast(Waiting(event, clock()))
        return release(inFront)
    }

    /** The dashboard may have come to the front, or time has passed: what goes now, in order. */
    fun release(inFront: Boolean): List<E> {
        val now = clock()
        val out = ArrayList<E>()
        while (waiting.isNotEmpty()) {
            val head = waiting.first()
            if (!inFront && isFinal(head.event) && now - head.at < HOLD_MS) break
            out += waiting.removeFirst().event
        }
        return out
    }

    /** When the event at the head stops waiting by itself (elapsed ms), or null when none waits. */
    fun dueAt(): Long? = waiting.firstOrNull()?.let { it.at + HOLD_MS }

    /** The page is gone with its last window: what waits goes nowhere, as an event would then. */
    fun clear() = waiting.clear()

    companion object {
        /** The longest an end waits for the dashboard to come to the front. */
        const val HOLD_MS = 1500L
    }
}
