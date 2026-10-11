package os.retinue.ara.wake

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
    )

    fun shouldListen(i: Inputs): Boolean {
        if (i.mode == WakeMode.Off) return false
        if (!i.micPermission || !i.modelsReady) return false
        if (i.voiceActive) return false
        if (i.nowMs < i.holdUntilMs) return false
        return i.shellVisible || (i.mode == WakeMode.Always && i.serviceRunning)
    }

    /**
     * Whether the foreground service should run: only in [WakeMode.Always], and
     * it may only be *started* while the shell is visible (Android 14 refuses a
     * microphone foreground service started from the background).
     */
    fun shouldStartService(mode: WakeMode, shellVisible: Boolean, serviceRunning: Boolean): Boolean =
        mode == WakeMode.Always && shellVisible && !serviceRunning

    fun shouldStopService(mode: WakeMode, serviceRunning: Boolean): Boolean =
        serviceRunning && mode != WakeMode.Always
}
