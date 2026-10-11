package os.retinue.ara

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.wake.Backoff
import os.retinue.ara.wake.MicYield
import os.retinue.ara.wake.VoiceActivity
import os.retinue.ara.wake.WakeFeatures
import os.retinue.ara.wake.WakeGate
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeScorer
import os.retinue.ara.wake.WakeTicket

class WakeScorerTest {

    /** Feed scores at 80 ms spacing; return the frame indices that triggered. */
    private fun run(scorer: WakeScorer, scores: List<Float>, startMs: Long = 0): List<Int> =
        scores.mapIndexedNotNull { i, s -> if (scorer.onScore(s, startMs + i * 80L)) i else null }

    @Test
    fun triggersAtThreshold() {
        val s = WakeScorer(threshold = 0.5f, warmupFrames = 0)
        assertEquals(listOf(2), run(s, listOf(0.1f, 0.49f, 0.5f)))
    }

    @Test
    fun warmupIgnoresFirstFrames() {
        val s = WakeScorer(warmupFrames = 5)
        assertEquals(listOf(5), run(s, List(6) { 0.9f }))
    }

    @Test
    fun refractoryPeriodSuppressesRepeats() {
        val s = WakeScorer(threshold = 0.5f, refractoryMs = 2_000, warmupFrames = 0)
        // 40 frames = 3.2 s of high scores: one trigger at 0, the next once 2 s have passed (frame 25 = 2000 ms).
        assertEquals(listOf(0, 25), run(s, List(40) { 0.9f }))
    }

    @Test
    fun consecutiveFramesRequired() {
        val s = WakeScorer(threshold = 0.5f, consecutiveFrames = 3, warmupFrames = 0)
        assertEquals(listOf(5), run(s, listOf(0.9f, 0.9f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f)))
    }

    @Test
    fun nanNeverTriggers() {
        val s = WakeScorer(warmupFrames = 0)
        assertEquals(emptyList(), run(s, listOf(Float.NaN, Float.NaN)))
    }

    @Test
    fun resetRestartsWarmupButKeepsRefractory() {
        val s = WakeScorer(refractoryMs = 2_000, warmupFrames = 1)
        assertFalse(s.onScore(0.9f, 0))
        assertTrue(s.onScore(0.9f, 80))
        s.reset()
        assertFalse(s.onScore(0.9f, 160)) // warm-up again
        assertFalse(s.onScore(0.9f, 240)) // still refractory
        assertTrue(s.onScore(0.9f, 2_080))
    }
}

class WakeGateTest {

    private fun inputs(
        mode: WakeMode = WakeMode.WhileVisible,
        visible: Boolean = true,
        service: Boolean = false,
        voice: Boolean = false,
        mic: Boolean = true,
        models: Boolean = true,
        now: Long = 1_000,
        hold: Long = 0,
    ) = WakeGate.Inputs(mode, visible, service, voice, mic, models, now, hold)

    @Test
    fun offNeverListens() {
        assertFalse(WakeGate.shouldListen(inputs(mode = WakeMode.Off)))
        assertFalse(WakeGate.shouldListen(inputs(mode = WakeMode.Off, service = true)))
    }

    @Test
    fun visibleMode() {
        assertTrue(WakeGate.shouldListen(inputs()))
        assertFalse(WakeGate.shouldListen(inputs(visible = false)))
        assertFalse(WakeGate.shouldListen(inputs(visible = false, service = true)))
    }

    @Test
    fun alwaysModeNeedsTheServiceInBackground() {
        assertTrue(WakeGate.shouldListen(inputs(mode = WakeMode.Always, visible = false, service = true)))
        assertFalse(WakeGate.shouldListen(inputs(mode = WakeMode.Always, visible = false, service = false)))
        assertTrue(WakeGate.shouldListen(inputs(mode = WakeMode.Always, visible = true, service = false)))
    }

    @Test
    fun pausesForVoiceHoldPermissionAndModels() {
        assertFalse(WakeGate.shouldListen(inputs(voice = true)))
        assertFalse(WakeGate.shouldListen(inputs(mic = false)))
        assertFalse(WakeGate.shouldListen(inputs(models = false)))
        assertFalse(WakeGate.shouldListen(inputs(now = 1_000, hold = 5_000)))
        assertTrue(WakeGate.shouldListen(inputs(now = 5_000, hold = 5_000)))
    }

    @Test
    fun serviceLifecycle() {
        fun start(mode: WakeMode = WakeMode.Always, visible: Boolean = true, running: Boolean = false, models: Boolean = true) =
            WakeGate.shouldStartService(mode, shellVisible = visible, serviceRunning = running, modelsReady = models)
        fun stop(mode: WakeMode = WakeMode.Always, running: Boolean = true, models: Boolean = true) =
            WakeGate.shouldStopService(mode, serviceRunning = running, modelsReady = models)
        assertTrue(start())
        assertFalse(start(visible = false))
        assertFalse(start(running = true))
        assertFalse(start(mode = WakeMode.WhileVisible))
        // Without models the service could never listen: not started, and stopped.
        assertFalse(start(models = false))
        assertTrue(stop(mode = WakeMode.Off))
        assertTrue(stop(mode = WakeMode.WhileVisible))
        assertFalse(stop())
        assertTrue(stop(models = false))
        assertFalse(stop(mode = WakeMode.Off, running = false))
        assertFalse(stop(running = false, models = false))
    }

    @Test
    fun modeKeys() {
        assertEquals(WakeMode.Off, WakeMode.fromKey(null))
        assertEquals(WakeMode.Off, WakeMode.fromKey("bogus"))
        assertEquals(WakeMode.Always, WakeMode.fromKey("always"))
        assertEquals(WakeMode.WhileVisible, WakeMode.fromKey(WakeMode.WhileVisible.key))
    }
}

class WakeFeaturesTest {

    /** A fake melspectrogram: 8 frames per call, each frame holding the call's input length and sample sum. */
    private val melInputs = mutableListOf<FloatArray>()
    private val fakeMel: (FloatArray) -> Array<FloatArray> = { input ->
        melInputs += input
        Array(8) { FloatArray(WakeFeatures.MEL_BINS) { input.size.toFloat() } }
    }
    private val windows = mutableListOf<Array<FloatArray>>()
    private var embeddingCalls = 0
    private val fakeEmbed: (Array<FloatArray>) -> FloatArray = { window ->
        windows += window
        embeddingCalls++
        FloatArray(96) { embeddingCalls.toFloat() }
    }

    @Test
    fun chunksOf1280WithContext() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 2)
        val out = mutableListOf<Array<FloatArray>>()
        // 1000 samples: no chunk yet.
        f.push(ShortArray(1000) { 1 }) { out += it }
        assertEquals(0, melInputs.size)
        // 280 more complete the first chunk: mel sees 1280 samples (no history yet).
        f.push(ShortArray(280) { 2 }) { out += it }
        assertEquals(1, melInputs.size)
        assertEquals(1280, melInputs[0].size)
        assertEquals(1f, melInputs[0][0])
        assertEquals(2f, melInputs[0][1279])
        // Only one embedding so far; a window needs two.
        assertTrue(out.isEmpty())
        // A second chunk: mel sees 1280 + 480 samples, the oldest 480 from the first chunk.
        f.push(ShortArray(1280) { 3 }) { out += it }
        assertEquals(1760, melInputs[1].size)
        // Samples 800..1279 of the first chunk: 800..999 were ones, 1000..1279 twos.
        assertEquals(1f, melInputs[1][0])
        assertEquals(1f, melInputs[1][199])
        assertEquals(2f, melInputs[1][200])
        assertEquals(2f, melInputs[1][479])
        assertEquals(3f, melInputs[1][480])
        assertEquals(1, out.size)
        assertEquals(2, out[0].size)
        // Oldest embedding first.
        assertEquals(1f, out[0][0][0])
        assertEquals(2f, out[0][1][0])
    }

    @Test
    fun melTransformAndWindow() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 1)
        f.push(ShortArray(1280)) { }
        // The first window: 68 rows of the initial ones, then 8 transformed frames (1280 / 10 + 2 = 130).
        val w = windows[0]
        assertEquals(WakeFeatures.WINDOW, w.size)
        assertEquals(1f, w[67][0])
        assertEquals(130f, w[68][0])
        assertEquals(130f, w[75][31])
        assertEquals(WakeFeatures.transform(1760f), 178f)
    }

    @Test
    fun oneEmbeddingPerChunkAndBounded() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 16)
        var produced = 0
        // 200 chunks in odd-sized pushes.
        val total = 200 * 1280
        var sent = 0
        while (sent < total) {
            val n = minOf(777, total - sent)
            f.push(ShortArray(n)) { produced++ }
            sent += n
        }
        assertEquals(200, embeddingCalls)
        assertEquals(200 - 15, produced)
        assertEquals(WakeFeatures.EMBEDDING_MAX, f.embeddingCount)
    }

    @Test
    fun resetStartsOver() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 1)
        f.push(ShortArray(1280)) { }
        f.push(ShortArray(640)) { }
        f.reset()
        assertEquals(0, f.embeddingCount)
        f.push(ShortArray(640)) { }
        assertEquals(1, melInputs.size) // the half chunk before reset was dropped
        f.push(ShortArray(640)) { }
        assertEquals(2, melInputs.size)
        assertEquals(1280, melInputs[1].size)
    }

    @Test
    fun consecutiveWindowsShiftByEightMelFrames() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 1)
        f.push(ShortArray(1280)) { }
        f.push(ShortArray(1280)) { }
        // Second window is the first shifted by 8 frames.
        assertContentEquals(windows[0][8], windows[1][0])
    }
}

class WakeTicketTest {

    @Test
    fun defaultLifetimeIsThePendingWakeWindow() {
        val t = WakeTicket { "only" }
        val token = t.issue(nowMs = 0)
        assertTrue(t.consume(token, nowMs = ShellDecisions.PENDING_WAKE_MAX_MS))
        val late = WakeTicket { "late" }
        val lateToken = late.issue(nowMs = 0)
        assertFalse(late.consume(lateToken, nowMs = ShellDecisions.PENDING_WAKE_MAX_MS + 1))
    }

    private var counter = 0
    private fun ticket(maxAgeMs: Long = 30_000) = WakeTicket(maxAgeMs) { "token-${++counter}" }

    @Test
    fun acceptsTheIssuedTokenOnce() {
        val t = ticket()
        val token = t.issue(nowMs = 1_000)
        assertTrue(t.consume(token, nowMs = 2_000))
        assertFalse(t.consume(token, nowMs = 2_001))
    }

    @Test
    fun refusesWithoutAWake() {
        val t = ticket()
        assertFalse(t.consume(null, nowMs = 0))
        assertFalse(t.consume("token-1", nowMs = 0))
    }

    @Test
    fun wrongTokensDoNotBurnThePendingOne() {
        val t = ticket()
        val token = t.issue(nowMs = 0)
        assertFalse(t.consume(null, nowMs = 10))
        assertFalse(t.consume("forged", nowMs = 10))
        assertTrue(t.consume(token, nowMs = 20))
    }

    @Test
    fun expiresAfterMaxAge() {
        val t = ticket(maxAgeMs = 30_000)
        val onTime = t.issue(nowMs = 0)
        assertTrue(t.consume(onTime, nowMs = 30_000))
        val late = t.issue(nowMs = 0)
        assertFalse(t.consume(late, nowMs = 30_001))
        // The expired token is used up too.
        assertFalse(t.consume(late, nowMs = 30_002))
    }

    @Test
    fun aNewWakeReplacesTheOldToken() {
        val t = ticket()
        val first = t.issue(nowMs = 0)
        val second = t.issue(nowMs = 5)
        assertFalse(t.consume(first, nowMs = 10))
        assertTrue(t.consume(second, nowMs = 10))
    }
}

class BackoffTest {

    @Test
    fun doublesUpToTheCapAndResets() {
        val b = Backoff(initialMs = 30_000, maxMs = 100_000)
        assertTrue(b.ready(0))
        assertEquals(30_000L, b.failed(0))
        assertFalse(b.ready(29_999))
        assertTrue(b.ready(30_000))
        assertEquals(90_000L, b.failed(30_000))
        assertEquals(190_000L, b.failed(90_000))
        assertEquals(290_000L, b.failed(190_000))
        b.succeeded()
        assertTrue(b.ready(190_001))
        assertEquals(0L, b.retryAt)
        assertEquals(230_000L, b.failed(200_000))
    }
}

class VoiceActivityTest {

    @Test
    fun reportedActivityHoldsWithoutExpiry() {
        val v = VoiceActivity(inferredMs = 10_000)
        v.pageReported(true)
        assertTrue(v.active(1_000_000))
        assertNull(v.changesAt(0))
        v.pageReported(false)
        assertFalse(v.active(0))
    }

    @Test
    fun aMicGrantCountsAsUseUntilReportedOrExpired() {
        val v = VoiceActivity(inferredMs = 10_000)
        v.micGranted(nowMs = 1_000)
        assertTrue(v.active(1_000))
        assertTrue(v.active(10_999))
        assertEquals(11_000L, v.changesAt(5_000))
        assertFalse(v.active(11_000))
        assertNull(v.changesAt(11_000))
    }

    @Test
    fun aReportReplacesTheInference() {
        val v = VoiceActivity(inferredMs = 10_000)
        v.micGranted(nowMs = 0)
        v.pageReported(false)
        assertFalse(v.active(1))
        v.micGranted(nowMs = 0)
        v.pageReported(true)
        assertTrue(v.active(60_000))
        // A grant while the page reports use leaves it in use.
        v.micGranted(nowMs = 60_000)
        assertTrue(v.active(200_000))
        v.reset()
        assertFalse(v.active(200_000))
    }
}

class MicYieldTest {

    @Test
    fun yieldsWhileAnotherAppRecordsAndForAGraceTimeAfter() {
        val y = MicYield(resumeAfterMs = 3_000)
        assertFalse(y.yielding(0))
        y.recordingsChanged(othersRecording = true, nowMs = 1_000)
        assertTrue(y.yielding(1_000))
        assertNull(y.changesAt(1_000))
        y.recordingsChanged(othersRecording = false, nowMs = 5_000)
        assertTrue(y.yielding(7_999))
        assertEquals(8_000L, y.changesAt(6_000))
        assertFalse(y.yielding(8_000))
        assertNull(y.changesAt(8_000))
    }

    @Test
    fun aNewRecordingDuringTheGraceTimeYieldsAgain() {
        val y = MicYield(resumeAfterMs = 3_000)
        y.recordingsChanged(true, 0)
        y.recordingsChanged(false, 1_000)
        y.recordingsChanged(true, 2_000)
        assertTrue(y.yielding(10_000))
        y.recordingsChanged(false, 10_000)
        assertTrue(y.yielding(12_999))
        assertFalse(y.yielding(13_000))
    }

    @Test
    fun theGateDoesNotListenWhileAnotherAppRecords() {
        val i = WakeGate.Inputs(
            mode = WakeMode.Always, shellVisible = false, serviceRunning = true, voiceActive = false,
            micPermission = true, modelsReady = true, nowMs = 0, holdUntilMs = 0,
        )
        assertTrue(WakeGate.shouldListen(i))
        assertFalse(WakeGate.shouldListen(i.copy(otherAppRecording = true)))
    }
}
