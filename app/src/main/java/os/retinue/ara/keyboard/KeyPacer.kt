package os.retinue.ara.keyboard

import kotlinx.coroutines.delay
import os.retinue.ara.keyboard.HidKeys.MOD_NONE
import os.retinue.ara.keyboard.HidKeys.Stroke

/**
 * Turns presses into the report sequence a host takes reliably, and sends it
 * at a steady pace.
 *
 * Every press is released before the next one (so "ll" is two presses), and a
 * press with modifiers first holds the modifiers alone: some hosts read a
 * report with Shift and a key arriving together as the plain key. Sent too
 * fast, hosts drop reports — the Quest among them — so there is a pause after
 * every report ([delayMs], default [DEFAULT_DELAY_MS]).
 */
object KeyPacer {

    const val DEFAULT_DELAY_MS = 12L

    /** The reports for [strokes], in order. */
    fun reports(strokes: List<Stroke>): List<ByteArray> {
        val out = ArrayList<ByteArray>(strokes.size * 3)
        for (s in strokes) {
            if (s.modifiers != MOD_NONE) out += HidKeys.press(Stroke(s.modifiers, 0))
            out += HidKeys.press(s)
            out += HidKeys.release()
        }
        return out
    }

    /**
     * Send [strokes] through [send], pausing [delayMs] after each report.
     * Returns how many presses went out completely; stops at the first report
     * [send] refuses (the host went away), after trying to release all keys
     * so none is left held down on the other device.
     */
    suspend fun send(
        strokes: List<Stroke>,
        delayMs: Long = DEFAULT_DELAY_MS,
        pause: suspend (Long) -> Unit = { delay(it) },
        send: (ByteArray) -> Boolean,
    ): Int {
        var done = 0
        for (s in strokes) {
            for (report in reports(listOf(s))) {
                if (!send(report)) {
                    send(HidKeys.release())
                    return done
                }
                pause(delayMs)
            }
            done++
        }
        return done
    }
}
