package os.retinue.ara.wake

import os.retinue.ara.data.InstanceState
import os.retinue.ara.data.ShellSettings

/** When the shell listens for the wake phrase (plan §5). Off is the default. */
enum class WakeMode(val key: String) {
    Off("off"),

    /** Only while the shell's home screen is visible (no service, no notification). */
    WhileVisible("visible"),

    /** Also in the background, through a foreground service of type microphone. */
    Always("always"),
    ;

    companion object {
        fun fromKey(key: String?): WakeMode = entries.firstOrNull { it.key == key } ?: Off
    }
}

/**
 * One instance whose wake phrase may be listened for: the endpoint, its wake
 * mode, threshold and phrase model, and whether it is the one shown.
 */
data class WakeTarget(
    val endpointId: String,
    val mode: WakeMode,
    val threshold: Float,
    /** The phrase model's id in the wake model library (null: none chosen). */
    val modelId: String?,
    val foreground: Boolean,
)

/**
 * Whose wake phrases the phone listens for: the instance shown and those
 * working in the background (see [InstanceState]), each with its own mode,
 * threshold and phrase model. A resting (disabled) instance, or one whose
 * mode is off, is not listened for. The order decides between instances that
 * share a phrase: the one shown first, then the background ones in list
 * order, so a phrase two instances share wakes the one on screen.
 */
object WakeTargets {

    fun of(settings: ShellSettings): List<WakeTarget> {
        val set = settings.endpointSet
        return set.endpoints
            .mapNotNull { e ->
                val state = set.stateOf(e)
                if (state == InstanceState.Disabled) return@mapNotNull null
                val wake = e.wake ?: return@mapNotNull null
                val mode = wake.wakeMode
                if (mode == WakeMode.Off) return@mapNotNull null
                WakeTarget(e.id, mode, wake.threshold, wake.modelId, foreground = state == InstanceState.Foreground)
            }
            .sortedByDescending { it.foreground }
    }
}

/**
 * Turns the keyword model's per-frame scores (one every 80 ms) into wake
 * events: a score at or above [threshold] for [consecutiveFrames] frames in a
 * row triggers, and after a trigger nothing triggers again for [refractoryMs].
 * The first [warmupFrames] scores after a (re)start are ignored, as
 * openWakeWord zeroes its first five predictions while its buffers fill.
 */
class WakeScorer(
    val threshold: Float = 0.5f,
    val refractoryMs: Long = 2_000,
    val consecutiveFrames: Int = 1,
    val warmupFrames: Int = 5,
) {
    init {
        require(consecutiveFrames >= 1) { "consecutiveFrames must be at least 1" }
        require(warmupFrames >= 0) { "warmupFrames must not be negative" }
    }

    private var seen = 0
    private var run = 0
    private var lastTriggerMs: Long? = null

    /** Feed one score; true when this frame is a wake. */
    fun onScore(score: Float, nowMs: Long): Boolean {
        if (seen < warmupFrames) {
            seen++
            return false
        }
        val last = lastTriggerMs
        if (last != null && nowMs - last < refractoryMs) {
            // Inside the refractory period nothing accumulates, so the tail of
            // the same utterance cannot trigger as soon as the period ends.
            run = 0
            return false
        }
        if (score.isNaN() || score < threshold) {
            run = 0
            return false
        }
        run++
        if (run < consecutiveFrames) return false
        run = 0
        lastTriggerMs = nowMs
        return true
    }

    /** Forget the stream (mic restarted); the refractory period survives a reset. */
    fun reset() {
        seen = 0
        run = 0
    }
}

/**
 * The scorers of the phrases one listener listens for, in [WakeTargets]
 * order: phrase p has the threshold [thresholds]`[p]` and is scored by the
 * model [modelOf]`[p]`. Instances that share a model share the phrase, and
 * no score can tell which of them was meant: whichever of their thresholds
 * the score reaches, the wake goes to the first of them (the one shown,
 * else the first in the list). Otherwise the lowest threshold would always
 * win, a score rising through it first.
 */
class WakePhraseScorers(thresholds: List<Float>, private val modelOf: List<Int>) {
    init {
        require(thresholds.size == modelOf.size) { "One model per phrase" }
    }

    private val scorers = thresholds.map { WakeScorer(threshold = it) }

    /** Feed one frame's [scores] (one per model); the phrase (its index) the frame wakes, or null. */
    fun onScores(scores: FloatArray, nowMs: Long): Int? {
        var heard = -1
        // Every scorer sees every frame, so warm-up and refractory stay in step.
        for (p in scorers.indices) {
            if (scorers[p].onScore(scores[modelOf[p]], nowMs) && heard < 0) heard = p
        }
        return if (heard < 0) null else modelOf.indexOf(modelOf[heard])
    }
}

/**
 * Whether the microphone should be open for wake detection right now. The mic is
 * never held when the mode is off, without permission or models, while the web
 * app has the mic itself (it reported voice active), or during the hold after a
 * wake (the page is about to open the mic).
 */
object WakeGate {
    data class Inputs(
        val mode: WakeMode,
        val shellVisible: Boolean,
        val serviceRunning: Boolean,
        val voiceActive: Boolean,
        val micPermission: Boolean,
        val modelsReady: Boolean,
        val nowMs: Long,
        val holdUntilMs: Long,
        /** Another app records (or Android silenced ours for one): the mic is theirs. */
        val otherAppRecording: Boolean = false,
    )

    fun shouldListen(i: Inputs): Boolean {
        if (i.mode == WakeMode.Off) return false
        if (!i.micPermission || !i.modelsReady) return false
        if (i.voiceActive) return false
        if (i.otherAppRecording) return false
        if (i.nowMs < i.holdUntilMs) return false
        return i.shellVisible || (i.mode == WakeMode.Always && i.serviceRunning)
    }

    /**
     * Whether the foreground service should run: only in [WakeMode.Always] with
     * the models present (without them it could never listen), and it may only
     * be *started* while the shell is visible (Android 14 refuses a microphone
     * foreground service started from the background).
     */
    fun shouldStartService(mode: WakeMode, shellVisible: Boolean, serviceRunning: Boolean, modelsReady: Boolean): Boolean =
        mode == WakeMode.Always && modelsReady && shellVisible && !serviceRunning

    fun shouldStopService(mode: WakeMode, serviceRunning: Boolean, modelsReady: Boolean): Boolean =
        serviceRunning && (mode != WakeMode.Always || !modelsReady)
}

/**
 * The one wake the detector has announced and the shell has not yet accepted.
 * The wake action reaches an exported activity, so any app could send it; the
 * activity honours it only with the token issued here, within [maxAgeMs]
 * (long enough to unlock the phone or tap the notification). A token is good
 * for one wake.
 */
class WakeTicket(
    private val maxAgeMs: Long = 60_000L,
    private val newToken: () -> String,
) {
    private var token: String? = null
    private var issuedAtMs = 0L
    private var endpointId: String? = null

    /** A wake the shell accepted: the instance whose phrase was heard (null: the one shown). */
    data class Taken(val endpointId: String?)

    /**
     * Announce a wake at [nowMs] for the instance [endpoint]; returns the
     * token to put into the wake intent. The instance stays here, with the
     * token, so an intent cannot name another one.
     */
    @Synchronized
    fun issue(nowMs: Long, endpoint: String? = null): String {
        val t = newToken()
        token = t
        issuedAtMs = nowMs
        endpointId = endpoint
        return t
    }

    /**
     * The wake, when [presented] is the pending token and still fresh; the
     * token is then used up. A wrong token leaves the pending one alone.
     */
    @Synchronized
    fun take(presented: String?, nowMs: Long): Taken? {
        val expected = token ?: return null
        if (presented == null || !constantTimeEquals(expected, presented)) return null
        token = null
        val age = nowMs - issuedAtMs
        return if (age in 0..maxAgeMs) Taken(endpointId) else null
    }

    /** True when [presented] is the pending token and still fresh (see [take]). */
    fun consume(presented: String?, nowMs: Long): Boolean = take(presented, nowMs) != null

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/**
 * Retry timing after a failure: the first retry after [initialMs], each further
 * failure doubling the wait up to [maxMs]; a success starts over.
 */
class Backoff(private val initialMs: Long = 30_000L, private val maxMs: Long = 10 * 60_000L) {
    private var delayMs = 0L
    private var retryAtMs = 0L

    /** A failure at [nowMs]; returns when the next attempt may be made. */
    fun failed(nowMs: Long): Long {
        delayMs = if (delayMs == 0L) initialMs else minOf(delayMs * 2, maxMs)
        retryAtMs = nowMs + delayMs
        return retryAtMs
    }

    fun succeeded() {
        delayMs = 0L
        retryAtMs = 0L
    }

    /** True when an attempt may be made at [nowMs]. */
    fun ready(nowMs: Long): Boolean = nowMs >= retryAtMs

    /** When the next attempt may be made (0 when it may be made now or never failed). */
    val retryAt: Long get() = retryAtMs
}

/**
 * Whether the web app is using the microphone, for [WakeGate]. What the page
 * reports through `RetinueShell.setVoiceActive` holds until it reports again.
 * A microphone grant to the page counts as use for [inferredMs] on its own, as
 * the page may open the mic before (or without) reporting it.
 */
class VoiceActivity(private val inferredMs: Long = 10_000L) {
    private var reported = false
    private var inferredUntilMs = 0L

    /** The page reported the mic in use or not; this replaces any inference. */
    fun pageReported(active: Boolean) {
        reported = active
        inferredUntilMs = 0L
    }

    /** The page was just granted the microphone. */
    fun micGranted(nowMs: Long) {
        inferredUntilMs = nowMs + inferredMs
    }

    /** A new document: nothing of the old page's state carries over. */
    fun reset() {
        reported = false
        inferredUntilMs = 0L
    }

    fun active(nowMs: Long): Boolean = reported || nowMs < inferredUntilMs

    /** When [active] may change by itself (the inference ends), or null. */
    fun changesAt(nowMs: Long): Long? = inferredUntilMs.takeIf { !reported && nowMs < it }
}

/**
 * Yielding the microphone to other apps. Android lets several apps record at
 * once but gives the audio to one of them (and some devices refuse a second
 * recording outright), so the wake listener steps aside whenever another app
 * records, or whenever Android silences the listener. It comes back only after
 * the other recording has been gone for [resumeAfterMs], so a voice message
 * recorded in several takes does not see the mic flap between them.
 */
class MicYield(private val resumeAfterMs: Long = 3_000L) {
    private var othersActive = false
    private var freeSinceMs = 0L

    /**
     * The active recordings changed at [nowMs]: [othersRecording] is true when
     * any recording but the listener's own is active, or the listener's own is
     * silenced in favour of another.
     */
    fun recordingsChanged(othersRecording: Boolean, nowMs: Long) {
        if (othersActive && !othersRecording) freeSinceMs = nowMs
        othersActive = othersRecording
    }

    /** True while the mic belongs to another app (including the grace time after it let go). */
    fun yielding(nowMs: Long): Boolean =
        othersActive || (freeSinceMs != 0L && nowMs < freeSinceMs + resumeAfterMs)

    /** When [yielding] ends by itself, or null. */
    fun changesAt(nowMs: Long): Long? =
        (freeSinceMs + resumeAfterMs).takeIf { !othersActive && freeSinceMs != 0L && nowMs < it }
}
