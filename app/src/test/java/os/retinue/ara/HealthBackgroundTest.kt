package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.health.ChangesToken
import os.retinue.ara.health.HealthSyncLogic
import os.retinue.ara.health.HealthSyncState
import os.retinue.ara.health.PeerSync
import os.retinue.ara.health.SkipReason
import os.retinue.ara.health.SyncResult

/** Health sync for the endpoints working in the background: which servers, and each one's own interval and back-off. */
class HealthBackgroundTest {

    private val minute = 60_000L

    @Test
    fun eachBackgroundServerOnceAndNeverTheShownOneAgain() {
        assertEquals(
            listOf("https://work.example", "https://club.example"),
            HealthSyncLogic.backgroundTargets(
                "https://home.example",
                listOf("https://work.example", "https://home.example", "https://club.example", "https://work.example"),
            ),
        )
        assertEquals(listOf("https://work.example"), HealthSyncLogic.backgroundTargets(null, listOf("https://work.example")))
        assertEquals(emptyList(), HealthSyncLogic.backgroundTargets("https://home.example", emptyList()))
    }

    @Test
    fun aBackgroundServerBacksOffOnItsOwnAndASuccessResetsIt() {
        val t0 = 1_000_000_000L
        val once = PeerSync().failed(t0, SyncResult.Skipped, SkipReason.NoBackendSupport.key, intervalMinutes = 5)
        assertEquals(1, once.failures)
        assertEquals(t0 + 5 * minute, once.backoffUntil)
        assertEquals(SkipReason.NoBackendSupport, once.backoffReason)
        val twice = once.failed(t0, SyncResult.Error, "HTTP 500", intervalMinutes = 5)
        assertEquals(t0 + 10 * minute, twice.backoffUntil)
        assertEquals(SkipReason.Interval, twice.backoffReason)
        val ok = twice.succeeded(t0 + minute, count = 3)
        assertEquals(0, ok.failures)
        assertEquals(0L, ok.backoffUntil)
        assertEquals(t0 + minute, ok.lastSyncAt)
        assertEquals(3, ok.lastCount)
        // No permission is no failure of the server's: no back-off.
        assertEquals(0, PeerSync().skipped(SkipReason.NoPermission).failures)
    }

    @Test
    fun thePeersAreStoredAndForgottenWithTheirServer() {
        val peers = mapOf("https://work.example" to PeerSync(lastAttemptAt = 5, failures = 2, backoffUntil = 9))
        assertEquals(peers, HealthSyncLogic.decodePeers(HealthSyncLogic.encodePeers(peers)))
        assertEquals(emptyMap(), HealthSyncLogic.decodePeers(null))
        assertEquals(emptyMap(), HealthSyncLogic.decodePeers("nonsense"))

        val state = HealthSyncState(
            tokens = mapOf("https://work.example" to ChangesToken("t", "Steps")),
            peers = peers,
        ).withPeer("https://club.example") { it.copy(lastAttemptAt = 7) }
        assertEquals(7L, state.peers["https://club.example"]!!.lastAttemptAt)
        // The shown endpoint's own fields are untouched by a background server.
        assertEquals(0, state.failures)
        val kept = state.retainingTokens(setOf("https://work.example"))
        assertEquals(setOf("https://work.example"), kept.peers.keys)
        assertTrue(kept.tokens.containsKey("https://work.example"))
    }
}
