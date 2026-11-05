package os.retinue.ara

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import os.retinue.ara.health.HealthRound
import os.retinue.ara.health.HealthRoundStore
import os.retinue.ara.health.HealthRounds

/** The rounds on the device, one per server (see [HealthRounds]). */
class HealthRoundsTest {

    private val originA = "https://a.example"
    private val originB = "https://b.example:8443"
    private lateinit var root: File
    private lateinit var rounds: HealthRounds

    private fun round(origin: String, batch: String = "batch-$origin") =
        HealthRound(batch, origin, 1_700_000_000_000L, "changes", "StepsRecord", 2, 0, 3, "next-$origin")

    private fun leave(store: HealthRoundStore, round: HealthRound): HealthRound =
        store.begin(round) { part -> "part $part of ${round.origin}".toByteArray() }

    @Before
    fun setUp() {
        root = createTempDirectory("health-rounds").toFile()
        rounds = HealthRounds(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun eachOriginHasItsOwnRound() {
        leave(rounds.of(originA), round(originA))
        leave(rounds.of(originB), round(originB))
        assertEquals(originA, rounds.of(originA).pending()!!.origin)
        assertEquals(originB, rounds.of(originB).pending()!!.origin)
        // Finishing one leaves the other.
        rounds.of(originA).clear()
        assertNull(rounds.of(originA).pending())
        assertEquals("part 1 of $originB", rounds.of(originB).partFile(1).readText())
        // The folder is a fixed, file-name-safe key of the origin.
        assertEquals(HealthRounds.key(originB), rounds.of(originB).dir.name)
        assertTrue(Regex("[0-9a-f]{32}").matches(HealthRounds.key(originB)))
        assertNotEquals(HealthRounds.key(originA), HealthRounds.key(originB))
    }

    @Test
    fun retainOnlyDropsTheRoundsOfServersThatTakeNoHealthData() {
        leave(rounds.of(originA), round(originA))
        leave(rounds.of(originB), round(originB))
        // A folder without a readable round goes too.
        File(root, "stray").mkdirs()
        rounds.retainOnly(setOf(originB))
        assertNull(rounds.of(originA).pending())
        assertFalse(rounds.of(originA).dir.exists())
        assertEquals(originB, rounds.of(originB).pending()!!.origin)
        assertFalse(File(root, "stray").exists())
        rounds.retainOnly(emptySet())
        assertNull(rounds.of(originB).pending())
        // Nothing there at all is fine.
        root.deleteRecursively()
        rounds.retainOnly(setOf(originA))
    }

    @Test
    fun anOlderBuildsRoundMovesIntoItsOriginsFolder() {
        // The layout of builds with one round: round.json and the parts in the root itself.
        val legacy = HealthRoundStore(root)
        var r = leave(legacy, round(originA, "legacy-batch"))
        r = legacy.sent(r, 1)
        rounds.migrateLegacy()
        assertFalse(File(root, "round.json").exists())
        assertFalse(File(root, "part-2.json").exists())
        val moved = rounds.of(originA).pending()!!
        assertEquals("legacy-batch", moved.batchId)
        assertEquals(1, moved.partsSent)
        assertEquals("part 2 of $originA", rounds.of(originA).partFile(2).readText())
        // Once moved, there is nothing left to migrate, and a second call changes nothing.
        rounds.migrateLegacy()
        assertEquals(r, rounds.of(originA).pending())
    }

    @Test
    fun anUnreadableLegacyRoundIsDropped() {
        root.mkdirs()
        File(root, "round.json").writeText("not a round")
        File(root, "part-1.json").writeText("orphan")
        rounds.migrateLegacy()
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
