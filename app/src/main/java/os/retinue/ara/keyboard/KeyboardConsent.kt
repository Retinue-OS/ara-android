package os.retinue.ara.keyboard

/**
 * What the phone asks before a page of the dashboard types on a device, set
 * per device on the keyboard screen (KeyboardPrefs, by Bluetooth address).
 */
enum class ConfirmMode(val key: String) {
    /** Every request of the page is confirmed on the phone. */
    Always("always"),

    /** Plain one-line text types by itself; what acts is confirmed (KeyboardLogic.onlyWrites). Where every device starts. */
    OneLine("one-line"),

    /** Nothing is confirmed (asked once more on the keyboard screen before it is chosen). */
    Never("never");

    companion object {
        val DEFAULT = OneLine

        fun fromKey(key: String?): ConfirmMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** A keyboard request of a page (the RetinueShell members), as the gate sees it. */
sealed class PageRequest {
    open val id: String? get() = null

    data class Type(val text: String, val enter: Boolean, override val id: String?) : PageRequest()

    data class Key(val name: String, override val id: String?) : PageRequest()

    data class Secret(val label: String?, val enter: Boolean, override val id: String?) : PageRequest()

    /** The native keyboard screen. */
    data object Open : PageRequest()

    data object Cancel : PageRequest()
}

/** What the gate ([KeyboardLogic.decide]) makes of a page's request. */
sealed class PageDecision {
    /** Answered with [error] at once; nothing happens on the device. */
    data class Refuse(val error: KeyboardError) : PageDecision()

    /** Typed (or pressed) now, without asking. */
    data object TypeNow : PageDecision()

    /** Typed only once the user approves it on the phone (ConfirmTypeActivity). */
    data object Confirm : PageDecision()

    /** The password dialog (SecretTypeActivity), which is itself the user's say. */
    data object OpenSecret : PageDecision()

    data object OpenScreen : PageDecision()

    /** Stop what is being typed for the page, close its open window. */
    data object Cancel : PageDecision()
}

/**
 * Confirmations that were not approved, in a row: after [IN_A_ROW] of them,
 * requests that would need one are refused without a window for [PAUSE_MS],
 * so a page cannot keep the phone busy with them. [clock]: elapsed ms.
 */
class ConfirmStreak(private val clock: () -> Long) {
    private var notApproved = 0
    private var pausedUntil = Long.MIN_VALUE

    val paused: Boolean get() = clock() < pausedUntil

    fun approved() {
        notApproved = 0
    }

    /** Declined, left, timed out or closed by the page. */
    fun notApproved() {
        notApproved++
        if (notApproved >= IN_A_ROW) {
            notApproved = 0
            pausedUntil = clock() + PAUSE_MS
        }
    }

    companion object {
        const val IN_A_ROW = 3
        const val PAUSE_MS = 60_000L
    }
}

/**
 * Per device (Bluetooth address), how many characters were typed for a page
 * without a confirmation since the last approval: a count only, nothing of
 * what was typed. An approval and a disconnect reset it. In memory only.
 */
class UnconfirmedCounts {
    private val counts = HashMap<String, Int>()

    fun of(address: String?): Int = address?.let { counts[it] } ?: 0

    fun add(address: String, chars: Int) {
        if (chars <= 0) return
        val sum = of(address).toLong() + chars
        counts[address] = sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun reset(address: String) {
        counts.remove(address)
    }

    fun clear() = counts.clear()
}

/**
 * The one window of the keyboard's own that may be open at a time: the
 * confirmation or the password dialog. It counts as open from the moment it
 * is asked for ([ask]), not only once its activity is up — or a second
 * request sent in the same moment would still get through — and, should the
 * activity never come up, for [KeyboardLogic.WINDOW_OPENING_MS] at most.
 * [clock]: elapsed ms. Main thread.
 */
class KeyboardWindows(private val clock: () -> Long) {
    private var askedAt = 0L
    private var closer: (() -> Unit)? = null

    val open: Boolean get() = closer != null || KeyboardLogic.windowOpening(askedAt, clock())

    /** Reserve the window; false (nothing changes) while one is open or on its way. */
    fun ask(): Boolean {
        if (open) return false
        // 0 means none: a clock that starts there still reserves.
        askedAt = clock().coerceAtLeast(1L)
        return true
    }

    /** The activity is up; [closer] closes it (the page's keyboardCancel). */
    fun opened(closer: () -> Unit) {
        askedAt = 0L
        this.closer = closer
    }

    fun closed(closer: () -> Unit) {
        if (this.closer === closer) this.closer = null
    }

    /** The window asked for will not come (its request was answered first): free at once. */
    fun abandon() {
        askedAt = 0L
    }

    /** Close the open window, if any, as its own Cancel would. */
    fun close() {
        closer?.invoke()
    }
}

/** What the window showing the page knows of it, for the gate ([KeyboardLogic.decide]). */
data class PageFacts(
    /** The page's own server may type on other devices (KeyboardLogic.serverMayType). */
    val serverAllows: Boolean,
    /** The user is looking at the dashboard: in front, focused, unlocked, not covered (ShellDecisions.mayType). */
    val inFront: Boolean,
    /** The host of the server whose page asks (Endpoint.hostLabel, from the app's own settings). */
    val serverHost: String?,
)

/** What the window showing the page still has to do once the runtime has taken a request. */
sealed class PageOutcome {
    /** Nothing: typed, typing, or answered. */
    data object Done : PageOutcome()

    /** Start ConfirmTypeActivity for the confirmation [token] (the window is reserved). */
    data class Confirm(val token: String) : PageOutcome()

    /**
     * Start SecretTypeActivity (the window is reserved) for the connected
     * device [host], naming [serverHost]; [unconfirmed]: characters the page
     * typed there without a confirmation since the last approval.
     */
    data class OpenSecret(val host: String?, val serverHost: String?, val unconfirmed: Int) : PageOutcome()

    data object OpenScreen : PageOutcome()
}

/**
 * A request of a page waiting for the user's approval: exactly this text and
 * Enter, or this key, for this device — approved once, or answered as
 * `cancelled`. [hostName] is the name the device gave itself (cleaned),
 * [serverHost] the asking server's host as the app's settings have it,
 * [unconfirmed] the count of [UnconfirmedCounts] when it was asked.
 */
data class PendingConfirmation(
    val token: String,
    val request: PageRequest,
    val hostAddress: String,
    val hostName: String?,
    val serverHost: String?,
    val unconfirmed: Int,
)
