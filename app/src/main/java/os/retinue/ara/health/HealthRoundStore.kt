package os.retinue.ara.health

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The round of parts on its way to the letterbox, on the device (see
 * [HealthRound]): `round.json` and one `part-<n>.json` per part, the file
 * exactly as it is PUT. Written before the first upload, so a sync that fails
 * at part 3 of 5 (or a process that dies there) resumes the round next time
 * from part 4, under the same batch id, instead of re-reading Health Connect
 * and sending parts 1 and 2 again under new names. In the app's own files
 * (not the cache), so Android does not clear it. Call off the main thread.
 */
class HealthRoundStore(private val dir: File) {

    private val meta: File get() = File(dir, META)

    fun partFile(part: Int): File = File(dir, "part-$part.json")

    /**
     * The round left unfinished, or null. A round whose part files are gone
     * cannot be resumed and is dropped (logged): its sent parts become
     * orphans, which is the lesser harm.
     */
    fun pending(): HealthRound? {
        val round = HealthRound.decode(runCatching { meta.readText() }.getOrNull()) ?: return null
        if (round.complete) {
            clear()
            return null
        }
        val missing = (round.partsSent + 1..round.parts).filterNot { partFile(it).isFile }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "round ${round.batchId} has lost parts $missing; dropped")
            clear()
            return null
        }
        return round
    }

    /**
     * Start a round: [parts] files from [body] (part 1 to [round].parts),
     * then the round itself, so a round on disk always has all its parts.
     * Whatever was there before is removed first.
     */
    @Throws(IOException::class)
    fun begin(round: HealthRound, body: (part: Int) -> ByteArray): HealthRound {
        clear()
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create ${dir.path}")
        for (part in 1..round.parts) partFile(part).writeBytes(body(part))
        val started = round.copy(partsSent = 0)
        meta.writeText(started.encode())
        return started
    }

    /** Part [part] of [round] was stored by the server; its file is no longer needed. */
    @Throws(IOException::class)
    fun sent(round: HealthRound, part: Int): HealthRound {
        val next = round.copy(partsSent = part)
        meta.writeText(next.encode())
        partFile(part).delete()
        return next
    }

    /**
     * Drop a round that is not for [origin] (null: none configured): its parts
     * were read for another server and must not go to this one (see
     * [HealthSyncLogic.pendingAction]).
     */
    fun discardUnless(origin: String?) {
        val round = HealthRound.decode(runCatching { meta.readText() }.getOrNull()) ?: return
        if (HealthSyncLogic.pendingAction(round, origin) == PendingAction.Discard) {
            Log.w(TAG, "round ${round.batchId} for ${round.origin} discarded; configured: $origin")
            clear()
        }
    }

    /** The round is complete (or given up): nothing stays on the device. */
    fun clear() {
        dir.deleteRecursively()
    }

    private companion object {
        const val TAG = "HealthRoundStore"
        const val META = "round.json"
    }
}
