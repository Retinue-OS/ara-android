package os.retinue.ara

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import os.retinue.ara.health.ClientInfo
import os.retinue.ara.health.Collected
import os.retinue.ara.health.HealthRecords
import os.retinue.ara.health.HealthRound
import os.retinue.ara.health.HealthRoundStore
import os.retinue.ara.health.HealthSource
import os.retinue.ara.health.HealthSyncState
import os.retinue.ara.health.HealthSyncer
import os.retinue.ara.health.HealthTrigger
import os.retinue.ara.health.HealthType
import os.retinue.ara.health.Letterbox
import os.retinue.ara.health.PermissionState
import os.retinue.ara.health.ProbeResult
import os.retinue.ara.health.SkipReason
import os.retinue.ara.health.SyncKind
import os.retinue.ara.health.SyncOutcome
import os.retinue.ara.health.UploadResult

/** The order of a sync (see [HealthSyncer]) against a fake Health Connect and letterbox. */
class HealthSyncerTest {

    private val originA = "https://a.example"
    private val originB = "https://b.example"
    private lateinit var dir: File
    private lateinit var rounds: HealthRoundStore

    /** A Health Connect that offers [granted] and returns [changes] for every read. */
    private class FakeSource(
        var available: Boolean = true,
        var granted: Set<String> = HealthRecords.READ_PERMISSIONS,
        var changes: Int = 0,
    ) : HealthSource {
        var reads = 0
        override fun available() = available
        override suspend fun grantedPermissions() = granted
        override suspend fun backgroundPermission() = emptySet<String>()
        override suspend fun collect(types: List<HealthType>, storedToken: String?): Collected {
            reads++
            val list = List(changes) { HealthRecords.delete("id-$it") }
            val kind = if (storedToken == null) SyncKind.Initial else SyncKind.Changes
            return Collected(kind, list, "token-${reads}", null)
        }
    }

    /** A letterbox that answers the probe with [probe] and records every PUT ([puts]: URL → body). */
    private class FakeLetterbox(var probe: ProbeResult = ProbeResult.Present, var put: UploadResult = UploadResult.Stored) : Letterbox {
        var probes = 0
        val puts = mutableListOf<Pair<String, String>>()
        override suspend fun probe(origin: String): ProbeResult { probes++; return probe }
        override suspend fun put(url: String, body: ByteArray): UploadResult {
            puts += url to body.toString(Charsets.UTF_8)
            return put
        }
    }

    private class State(var value: HealthSyncState = HealthSyncState()) {
        val gates = mutableListOf<Pair<Boolean, PermissionState>>()
    }

    private suspend fun sync(
        source: HealthSource,
        letterbox: Letterbox,
        origin: String? = originA,
        state: State = State(),
        trigger: HealthTrigger = HealthTrigger.Resume,
    ): SyncOutcome = HealthSyncer(source, letterbox, rounds, ClientInfo("os.retinue.ara", "test", 35)).sync(
        trigger = trigger,
        requester = null,
        origin = origin,
        state = { state.value },
        update = { change -> state.value = change(state.value) },
        onGates = { a, p -> state.gates += a to p },
    )

    /** A round of [parts] parts for [origin] on the device, [sent] of them stored already. */
    private fun leaveRound(origin: String, parts: Int, sent: Int): HealthRound {
        val round = HealthRound("batch-1", origin, 1_700_000_000_000L, "initial", "StepsRecord", parts, 0, 7, "token-left")
        var r = rounds.begin(round) { part -> "part $part".toByteArray() }
        for (p in 1..sent) r = rounds.sent(r, p)
        return r
    }

    @Before
    fun setUp() {
        dir = createTempDirectory("health-round").toFile()
        rounds = HealthRoundStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aPendingRoundIsFinishedEvenWithPermissionsRevoked() = runBlocking {
        leaveRound(originA, parts = 3, sent = 1)
        val source = FakeSource(granted = emptySet())
        val letterbox = FakeLetterbox()
        val state = State()
        val outcome = sync(source, letterbox, state = state)
        // Parts 2 and 3 went out under the round's own batch id and sent_at, part 1 not again.
        assertEquals(
            listOf(
                "https://a.example/inbox/hc-20231114T221320Z-batch-1-p2of3.health-connect.json" to "part 2",
                "https://a.example/inbox/hc-20231114T221320Z-batch-1-p3of3.health-connect.json" to "part 3",
            ),
            letterbox.puts,
        )
        assertNull(rounds.pending())
        assertFalse(dir.exists())
        // Its token is stored for this origin; the sync itself then stops at the permission gate.
        assertEquals("token-left", state.value.token)
        assertEquals(originA, state.value.tokenOrigin)
        assertEquals(SyncOutcome.Skipped(SkipReason.NoPermission), outcome)
        assertEquals(0, source.reads)
        assertEquals(1, letterbox.probes)
    }

    @Test
    fun aPendingRoundIsFinishedWithHealthConnectGone() = runBlocking {
        leaveRound(originA, parts = 1, sent = 0)
        val letterbox = FakeLetterbox()
        val outcome = sync(FakeSource(available = false), letterbox)
        assertEquals(1, letterbox.puts.size)
        assertNull(rounds.pending())
        assertEquals(SyncOutcome.Skipped(SkipReason.Unavailable), outcome)
    }

    @Test
    fun aPendingRoundForAnotherOriginIsDiscarded() = runBlocking {
        leaveRound(originA, parts = 2, sent = 1)
        val source = FakeSource(changes = 0)
        val letterbox = FakeLetterbox()
        val state = State()
        val outcome = sync(source, letterbox, origin = originB, state = state)
        // Nothing of A's round went to B; B's sync then ran on its own.
        assertEquals(emptyList(), letterbox.puts)
        assertNull(rounds.pending())
        assertEquals(1, source.reads)
        assertEquals(SyncOutcome.Done(0, "token-1", HealthRecords.TYPES.map { it.name }.sorted().joinToString(","), originB), outcome)
        assertNull(state.value.token) // the runtime stores the outcome's token
    }

    @Test
    fun noBaseUrlDiscardsThePendingRound() = runBlocking {
        leaveRound(originA, parts = 1, sent = 0)
        val letterbox = FakeLetterbox()
        assertEquals(SyncOutcome.Failed("No Retinue base URL is set"), sync(FakeSource(), letterbox, origin = null))
        assertEquals(emptyList(), letterbox.puts)
        assertNull(rounds.pending())
    }

    @Test
    fun aFailedPartKeepsTheRoundAndTheRestIsSentNextTime() = runBlocking {
        val source = FakeSource(changes = 10_001)
        val letterbox = FakeLetterbox(put = UploadResult.Failed("HTTP 500"))
        val state = State()
        assertEquals(SyncOutcome.Failed("Part 1 of 3: HTTP 500"), sync(source, letterbox, state = state))
        val pending = rounds.pending()!!
        assertEquals(0, pending.partsSent)
        assertEquals(originA, pending.origin)
        val first = Json.parseToJsonElement(letterbox.puts.single().second).jsonObject
        assertEquals(pending.batchId, first["batch_id"]!!.jsonPrimitive.content)
        // Next time: the same batch, parts 1 to 3 (the failed PUT of part 1 is
        // the first entry of the log), then the new changes as a round of their own.
        letterbox.put = UploadResult.Stored
        source.changes = 1
        val outcome = sync(source, letterbox, state = state)
        assertEquals(5, letterbox.puts.size)
        val batches = letterbox.puts.map { Json.parseToJsonElement(it.second).jsonObject["batch_id"]!!.jsonPrimitive.content }
        assertEquals(List(4) { pending.batchId }, batches.take(4))
        assertTrue(batches[4] != pending.batchId)
        assertEquals(
            listOf("p1of3", "p1of3", "p2of3", "p3of3", "p1of1"),
            letterbox.puts.map { Regex("p\\d+of\\d+").find(it.first)!!.value },
        )
        assertNull(rounds.pending())
        assertEquals(SyncOutcome.Done(10_002, "token-2", (outcome as SyncOutcome.Done).types, originA), outcome)
    }

    @Test
    fun aServerWithoutPropfindStillGetsThePut() = runBlocking {
        val letterbox = FakeLetterbox(probe = ProbeResult.Unknown)
        val outcome = sync(FakeSource(changes = 1), letterbox)
        assertEquals(1, letterbox.puts.size)
        assertTrue(outcome is SyncOutcome.Done)
    }

    @Test
    fun noLetterboxMeansNoHealthConnectRead() = runBlocking {
        val source = FakeSource(changes = 1)
        val letterbox = FakeLetterbox(probe = ProbeResult.Absent)
        assertEquals(SyncOutcome.Skipped(SkipReason.NoBackendSupport), sync(source, letterbox))
        assertEquals(0, source.reads)
        assertEquals(emptyList(), letterbox.puts)
        assertEquals(SyncOutcome.Failed("Letterbox: HTTP 401"), sync(source, FakeLetterbox(probe = ProbeResult.Failed("HTTP 401"))))
    }

    @Test
    fun theTokenIsPerOrigin() = runBlocking {
        val source = FakeSource(changes = 0)
        val types = HealthRecords.TYPES.map { it.name }.sorted().joinToString(",")
        val state = State(HealthSyncState(token = "old", tokenTypes = types, tokenOrigin = originA))
        sync(source, FakeLetterbox(), origin = originB, state = state)
        // No token for B: an initial read, not the changes since A's token.
        assertEquals(1, source.reads)
        val collected = source.collect(emptyList(), null)
        assertEquals(SyncKind.Initial, collected.kind)
    }

    @Test
    fun envelopeOfAPart() = runBlocking {
        val letterbox = FakeLetterbox()
        sync(FakeSource(changes = 2), letterbox, trigger = HealthTrigger.Link)
        val body = Json.parseToJsonElement(letterbox.puts.single().second).jsonObject
        assertEquals("retinue-health-connect/1", body["schema"]!!.jsonPrimitive.content)
        val sync = body["sync"]!!.jsonObject
        assertEquals("initial", sync["kind"]!!.jsonPrimitive.content)
        assertEquals("link", sync["trigger"]!!.jsonPrimitive.content)
        assertEquals("os.retinue.ara", body["client"]!!.jsonObject["app"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(buildJsonObject { put("op", "delete"); put("id", "id-0") }, buildJsonObject { put("op", "delete"); put("id", "id-1") }),
            body["changes"]!!.jsonArray.toList(),
        )
    }
}
