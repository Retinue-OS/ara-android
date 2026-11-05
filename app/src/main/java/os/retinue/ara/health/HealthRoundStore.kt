package os.retinue.ara.health

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The round of parts on its way to the letterbox, on the device (see
 * [HealthRound]): `round.json` and one `part-<n>.json` per part, the file
 * exactly as it is PUT. Written before the first upload, so a sync that fails
 * at part 3 of 5 (or a process that dies there) resumes the round next time
 * from part 4, under the same batch id, instead of re-reading Health Connect
 * and sending parts 1 and 2 again under new names. In the app's own files
 * (not the cache), so Android does not clear it. One per server, see
 * [HealthRounds]. Call off the main thread.
 */
class HealthRoundStore(internal val dir: File) {

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

    internal companion object {
        const val TAG = "HealthRoundStore"
        const val META = "round.json"
    }
}

/**
 * The rounds on their way to each server, one [HealthRoundStore] per origin
 * (a folder named after a hash of the origin under [root]): a round left
 * half-sent to one server waits for that server while another is shown, and
 * is resumed when it is shown again — its token has not moved, so nothing is
 * lost and nothing is read twice. A round is dropped only when its server no
 * longer takes health data ([retainOnly]). Call off the main thread.
 */
class HealthRounds(private val root: File) {

    /** The round store of [origin]. */
    fun of(origin: String): HealthRoundStore = HealthRoundStore(File(root, key(origin)))

    /**
     * Drop every round that is not for one of [origins] (the servers that
     * take health data: configured, with the switch on): what was read for a
     * server that is gone, or that was switched off, must not stay on the
     * device or go anywhere else. Decided by folder name alone, never by
     * reading a round a sync may be writing at that moment: the folders of
     * [origins] are left as they are (their own sync checks what is in them),
     * every other folder goes.
     */
    fun retainOnly(origins: Set<String>) {
        val keep = origins.mapTo(HashSet()) { key(it) }
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            if (dir.name in keep) continue
            val round = HealthRound.decode(runCatching { File(dir, HealthRoundStore.META).readText() }.getOrNull())
            if (round != null) Log.w(HealthRoundStore.TAG, "round ${round.batchId} for ${round.origin} discarded")
            dir.deleteRecursively()
        }
    }

    /**
     * An older build kept its one round in [root] itself: move it into its
     * origin's folder, where the next sync to that server resumes it. A round
     * that cannot be read (or moved) is dropped, as one with lost parts is.
     */
    fun migrateLegacy() {
        val meta = File(root, HealthRoundStore.META)
        val files = root.listFiles { f -> f.isFile } ?: return
        if (files.isEmpty()) return
        val round = HealthRound.decode(runCatching { meta.readText() }.getOrNull())
        if (round != null) {
            val target = of(round.origin).dir
            target.deleteRecursively()
            if (target.mkdirs() && files.all { it.renameTo(File(target, it.name)) }) return
            Log.w(HealthRoundStore.TAG, "round ${round.batchId} could not be moved; dropped")
            target.deleteRecursively()
        }
        files.forEach { it.delete() }
    }

    companion object {
        /** The folder of [origin]'s round: the first 16 bytes of its SHA-256, in hex (an origin is no file name). */
        fun key(origin: String): String =
            MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
    }
}
