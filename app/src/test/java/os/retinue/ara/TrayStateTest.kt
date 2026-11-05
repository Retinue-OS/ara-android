package os.retinue.ara

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import os.retinue.ara.ui.TrayState

/**
 * The tray's animations give way: to a finger on the lines, to a snap down and
 * to a collapse, also one asked for before an opening animation's first frame.
 */
class TrayStateTest {

    /** A frame every time the animation asks, 16 ms apart, letting the test run in between. */
    private class StepClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    private fun tray() = TrayState().apply { setBounds(max = 1_000f, line = 100f, flingVelocity = 1_000f) }

    private fun withClock(block: suspend CoroutineScope.() -> Unit) = runBlocking(StepClock()) { block() }

    @Test
    fun aFingerOnTheLinesStopsAnAnimation() = withClock {
        val tray = tray()
        val opening = launch { tray.animateTo(1_000f) }
        repeat(5) { yield() }
        val at = tray.expansion
        assertTrue(at > 0f && at < 1_000f, "mid-animation, at $at")
        // The finger moves down 50 px: the tray follows it, and the animation no longer moves it.
        assertEquals(50f, tray.pullByUser(50f))
        opening.join()
        assertEquals(at - 50f, tray.expansion)
    }

    @Test
    fun aSnapStopsAnOpeningAnimationBeforeItsFirstFrame() = withClock {
        val tray = tray()
        val opening = launch { tray.animateTo(1_000f) }
        yield()
        assertEquals(0f, tray.expansion)
        tray.snapCollapsed()
        opening.join()
        assertEquals(0f, tray.expansion)
    }

    @Test
    fun aSnapStopsAnAnimationOnItsWay() = withClock {
        val tray = tray()
        val opening = launch { tray.animateTo(1_000f) }
        repeat(5) { yield() }
        tray.snapCollapsed()
        opening.join()
        assertEquals(0f, tray.expansion)
    }

    @Test
    fun collapseIsAskedForEvenAtZeroWhileOpening() = withClock {
        val tray = tray()
        val opening = launch { tray.animateTo(1_000f) }
        yield()
        tray.collapse()
        assertEquals(1, tray.collapseRequests)
        opening.cancel()
    }

    @Test
    fun collapseOfAStillTrayAtZeroAsksNothing() {
        val tray = tray()
        tray.collapse()
        assertEquals(0, tray.collapseRequests)
    }
}
