package os.retinue.ara

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.wake.WakeFeatures
import os.retinue.ara.wake.WakeGate
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeScorer

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
        assertTrue(WakeGate.shouldStartService(WakeMode.Always, shellVisible = true, serviceRunning = false))
        assertFalse(WakeGate.shouldStartService(WakeMode.Always, shellVisible = false, serviceRunning = false))
        assertFalse(WakeGate.shouldStartService(WakeMode.Always, shellVisible = true, serviceRunning = true))
        assertFalse(WakeGate.shouldStartService(WakeMode.WhileVisible, shellVisible = true, serviceRunning = false))
        assertTrue(WakeGate.shouldStopService(WakeMode.Off, serviceRunning = true))
        assertTrue(WakeGate.shouldStopService(WakeMode.WhileVisible, serviceRunning = true))
        assertFalse(WakeGate.shouldStopService(WakeMode.Always, serviceRunning = true))
        assertFalse(WakeGate.shouldStopService(WakeMode.Off, serviceRunning = false))
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
    fun windowRowsAreIndependentCopies() {
        val f = WakeFeatures(fakeMel, fakeEmbed, embeddingFrames = 1)
        f.push(ShortArray(1280)) { }
        f.push(ShortArray(1280)) { }
        // Second window is the first shifted by 8 frames.
        assertContentEquals(windows[0][8], windows[1][0])
    }
}
