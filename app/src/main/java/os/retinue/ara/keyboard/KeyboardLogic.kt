package os.retinue.ara.keyboard

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import os.retinue.ara.data.EndpointSet

/** A device Ara can type into: a Bluetooth host it is paired with. */
data class KeyboardHost(val name: String, val address: String)

/**
 * What the page's `RetinueShell.keyboardStatus()` answers, and what the
 * settings card and the keyboard screen show.
 */
data class KeyboardStatus(
    /** The phone can act as a Bluetooth keyboard (it has an adapter, and the HID device profile). */
    val available: Boolean = false,
    /** The user switched "Keyboard for other devices" on. */
    val enabled: Boolean = false,
    /** Registered with Android as a keyboard (it may be enabled and not yet registered, or refused). */
    val registered: Boolean = false,
    /** A device is connected, so text can be typed now. */
    val connected: Boolean = false,
    /** The connected device, or else the one Ara reconnects to (none after a Disconnect). */
    val host: KeyboardHost? = null,
    /** The layout Ara types for on [host] (KeyLayouts id). */
    val layout: String = KeyLayouts.US,
    /** Typing is going on (a request now is refused as `busy`). */
    val busy: Boolean = false,
    /** Android's Bluetooth permissions are granted. */
    val permitted: Boolean = false,
    /** The paired devices, for the keyboard screen. */
    val paired: List<KeyboardHost> = emptyList(),
    /** Bluetooth is on (the service and its notification wait for it). */
    val bluetoothOn: Boolean = false,
    /** Android refused the keyboard role (another app holds it). */
    val refused: Boolean = false,
    /** The user disconnected: Ara connects to no device until they connect one again. */
    val held: Boolean = false,
    /** What the phone asks before a page types on [host] (KeyboardPrefs); never the page's business. */
    val confirmMode: ConfirmMode = ConfirmMode.DEFAULT,
) {
    /**
     * The page's JSON (the contract: available, enabled, connected, host,
     * layout). The host is named only — its Bluetooth address is none of the
     * page's business.
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("available", available)
        put("enabled", enabled)
        put("connected", connected)
        if (host != null) {
            put("host", buildJsonObject { put("name", host.name) })
        } else {
            put("host", JsonNull)
        }
        put("layout", layout)
    }
}

/** Why typing did not happen (the `error` of a `keyboard` event). */
enum class KeyboardError(val key: String) {
    NotEnabled("not-enabled"),
    NotConnected("not-connected"),
    /** The page asked while Ara was not on screen: nothing types unseen. */
    NotInFront("not-in-front"),
    TooLong("too-long"),
    UnsupportedChar("unsupported-char"),
    Busy("busy"),
    /** Stopped by the user (keyboardCancel, the keyboard screen's Stop). */
    Cancelled("cancelled"),
    Failed("failed"),
}

/**
 * A `retinue-shell` event of type `keyboard` (contract): `typed` (with
 * `count`, the characters typed), `error` (with `error`), `secret-typed`,
 * `secret-cancelled` or `status` (re-read `keyboardStatus()`). [id] is the
 * request's own, echoed. A secret event never carries what was typed, nor
 * how long it was.
 */
data class KeyboardEvent(
    val state: String,
    val id: String? = null,
    val count: Int? = null,
    val error: KeyboardError? = null,
) {
    fun detail(): JsonObject = buildJsonObject {
        put("state", state)
        id?.let { put("id", it) }
        count?.let { put("count", it) }
        error?.let { put("error", it.key) }
    }

    companion object {
        const val TYPED = "typed"
        const val ERROR = "error"
        const val SECRET_TYPED = "secret-typed"
        const val SECRET_CANCELLED = "secret-cancelled"
        const val STATUS = "status"

        val status = KeyboardEvent(STATUS)
        fun typed(id: String?, count: Int) = KeyboardEvent(TYPED, id, count = count)
        fun error(id: String?, error: KeyboardError) = KeyboardEvent(ERROR, id, error = error)
    }
}

object KeyboardLogic {

    /** The longest text the page may hand over at once (characters). */
    const val MAX_TEXT = 4000

    /** The longest secret the dialog takes. */
    const val MAX_SECRET = 512

    /** The longest label the secret dialog shows. */
    const val MAX_LABEL = 120

    private val idChars = Regex("^[A-Za-z0-9._:-]{1,64}$")

    /** A request id the page may echo back, or null (dropped) for anything else. */
    fun cleanId(id: String?): String? = id?.takeIf { idChars.matches(it) }

    /**
     * A label for the secret dialog: one plain line, bounded, never empty —
     * control, format (bidi, zero-width) and line/paragraph separator
     * characters become spaces, so a page cannot break or spoof the title.
     */
    fun cleanLabel(label: String?): String? =
        label?.replace(LABEL_JUNK, " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LABEL)
            ?.takeIf { it.isNotEmpty() }

    private val LABEL_JUNK = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")

    /**
     * Whether a host that connected may stay connected — Ara types only into
     * the device the user chose, never into another one that connects by
     * itself: while they switch to a device ([pending]), only that one; else
     * a device they paired just now ([recentlyBonded] — pairing takes the
     * user on both sides); none while they hold Ara off (a Disconnect); the
     * first one ever; and otherwise only the one it last typed into.
     */
    fun acceptsHost(address: String, pending: String?, held: Boolean,
                    recentlyBonded: Boolean, lastHost: String?): Boolean = when {
        pending != null -> address == pending
        recentlyBonded -> true
        held -> false
        lastHost == null -> true
        else -> address == lastHost
    }

    /**
     * Whether the page loaded for [pageEndpointId] may type on other devices:
     * its endpoint's own switch (Endpoint.typeOnDevices), decided as for its
     * calls ([EndpointSet.pageEndpoint]) — while that endpoint is shown, and
     * off otherwise. The device-wide keyboard switch must be on as well.
     */
    fun serverMayType(endpoints: EndpointSet, pageEndpointId: String?): Boolean =
        endpoints.pageEndpoint(pageEndpointId)?.typeOnDevices == true

    /**
     * What `keyboardStatus()` tells a page: everything, when its server may
     * type ([serverAllows]); otherwise that the keyboard is not enabled — and
     * nothing of the device, its layout or whether one is connected. The
     * shape stays the contract's.
     */
    fun statusForPage(status: KeyboardStatus, serverAllows: Boolean): KeyboardStatus =
        if (serverAllows) status else KeyboardStatus(available = status.available)

    /**
     * Whether [event] goes to the page: everything when its server may type
     * ([serverAllows]); otherwise only `not-enabled`, the one answer its own
     * requests get. A `status` would tell it when the keyboard is switched or
     * a device comes and goes, which its keyboardStatus() keeps from it; and
     * the end of an earlier page's request (typed, secret-typed, cancelled —
     * still running when another endpoint's page was loaded) that a device is
     * connected, and how much was typed. The end of a request this page load
     * made itself ([requested]: the ids the gate accepted for it) still
     * passes when its switch was turned off meanwhile — it is its own, and
     * the page would otherwise wait for it until its watchdog gives up.
     */
    fun eventForPage(event: KeyboardEvent, serverAllows: Boolean, requested: Set<String>): Boolean =
        serverAllows ||
            (event.state == KeyboardEvent.ERROR && event.error == KeyboardError.NotEnabled) ||
            (event.state in FINAL_STATES && event.id != null && event.id in requested)

    /** The events that end a request: its id's last word. */
    private val FINAL_STATES = setOf(KeyboardEvent.TYPED, KeyboardEvent.SECRET_TYPED, KeyboardEvent.SECRET_CANCELLED, KeyboardEvent.ERROR)

    /** The `count` of a `typed` event: the text's characters, a CRLF line break as one. */
    fun charCount(text: CharSequence): Int {
        val s = text.toString()
        return s.codePointCount(0, s.length) - Regex("\r\n").findAll(s).count()
    }

    /**
     * How long a secret's typing is held beyond [elapsedMs]: to the next
     * multiple of [bucketMs], at least one, so the time it takes — which a
     * page can watch through `busy` answers — tells it little of the length.
     */
    fun secretPad(elapsedMs: Long, bucketMs: Long): Long {
        val buckets = maxOf(1L, (elapsedMs + bucketMs - 1) / bucketMs)
        return buckets * bucketMs - elapsedMs
    }

    /** How long a window of the keyboard's own (confirmation, password dialog) asked for counts as open before its activity is up. */
    const val WINDOW_OPENING_MS = 5000L

    /**
     * Whether a window asked for at [askedAt] (0: none) is still on its way
     * at [now] — bounded, so a window that never came up does not shut the
     * page out for good ([KeyboardWindows]).
     */
    fun windowOpening(askedAt: Long, now: Long): Boolean =
        askedAt != 0L && now - askedAt < WINDOW_OPENING_MS

    /** What the page hears of a secret that failed: never why it lies in the secret itself. */
    fun secretErrorForPage(error: KeyboardError): KeyboardError = when (error) {
        KeyboardError.UnsupportedChar, KeyboardError.TooLong -> KeyboardError.Failed
        else -> error
    }

    // ── What a page may type by itself ─────────────────────────────────────

    /**
     * What Python's `str.isspace()` holds for, so what `str.strip()` strips:
     * the web app's twin of [onlyWrites] decides with exactly these (see there).
     */
    private const val PY_WHITESPACE = "\t\n\u000B\u000C\r\u001C\u001D\u001E\u001F \u0085\u00A0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"

    /** A blank in the twin's sense (Python's `str.isspace()`), not Kotlin's or Java's. */
    fun isBlank(c: Char): Boolean = c in PY_WHITESPACE

    /**
     * Whether a page's text only writes: Enter not asked for, the text not
     * empty, no blank at either end and no carriage return, line feed or tab
     * in it. Everything else acts — a text with Enter, a line break (typed as
     * Shift+Enter, which submits in a terminal or a one-line field), a tab
     * (to the next field or a button), a blank at an end (a space presses a
     * focused button), and every single key.
     *
     * The twin is the web app's own rule for what runs by itself:
     * `runs_by_itself` in the retinue repository's scripts/shell_actions.py
     * (and needsTap in webapp/components/shell-keyboard.js), less its
     * `sensitive`, which the app cannot see. This one must never be stricter:
     * what the dashboard types by itself must never meet a confirmation on a
     * phone set to "one-line text by itself". Its blanks are Python's
     * ([isBlank]), and KeyboardRuleTest pins the twin's table of cases.
     */
    fun onlyWrites(text: String, enter: Boolean): Boolean =
        !enter && text.isNotEmpty() && !isBlank(text.first()) && !isBlank(text.last()) &&
            text.none { it == '\r' || it == '\n' || it == '\t' }

    /** [onlyWrites] for a request: only a text can; a key always acts. */
    fun onlyWrites(request: PageRequest): Boolean = request is PageRequest.Type && onlyWrites(request.text, request.enter)

    /**
     * Whether a page's request needs the user's approval on the phone first:
     * by the device's [mode] (every time; when it acts; never), and in every
     * mode after the strip's Stop, until the user approves one ([stopPending], [StopPending]).
     */
    fun needsConfirmation(mode: ConfirmMode, onlyWrites: Boolean, stopPending: Boolean): Boolean = stopPending || when (mode) {
        ConfirmMode.Always -> true
        ConfirmMode.OneLine -> !onlyWrites
        ConfirmMode.Never -> false
    }

    /**
     * What a request typed without a confirmation adds to the device's count
     * ([UnconfirmedCounts]): its characters, Enter after them as one more; a
     * key as one.
     */
    fun unconfirmedChars(request: PageRequest): Int = when (request) {
        is PageRequest.Type -> charCount(request.text) + if (request.enter) 1 else 0
        is PageRequest.Key -> 1
        else -> 0
    }

    /** How long a confirmation waits for the user: shorter than the page waits (30 s and 0.1 s a character for a text). */
    const val CONFIRM_TEXT_MS = 25_000L

    /** Likewise for a key (the page waits 15 s). */
    const val CONFIRM_KEY_MS = 12_000L

    fun confirmTimeoutMs(request: PageRequest): Long = if (request is PageRequest.Key) CONFIRM_KEY_MS else CONFIRM_TEXT_MS

    /**
     * The gate every keyboard request of a page goes through (MainActivity
     * gathers the facts, KeyboardRuntime.fromPage carries the answer out). In
     * this order: the server's own switch ([serverAllows]); then the checks
     * that stood before — the keyboard on, a device connected, the page in
     * front and the phone unlocked ([inFront], ShellDecisions.mayType), no
     * window of the keyboard's own open ([windowOpen]), nothing being typed,
     * the text not too long; then the rule and the device's [mode], which
     * either type at once or ask first — or, while not-approved confirmations
     * pause them ([confirmPaused], [ConfirmStreak]), refuse as `cancelled`
     * without a window. A Stop from the page needs only its server's switch;
     * the keyboard screen needs the page in front too (it is where the
     * keyboard is switched on). The password dialog is the user's say itself.
     */
    fun decide(
        request: PageRequest,
        serverAllows: Boolean,
        status: KeyboardStatus,
        inFront: Boolean,
        windowOpen: Boolean,
        mode: ConfirmMode,
        stopPending: Boolean,
        confirmPaused: Boolean,
    ): PageDecision {
        if (!serverAllows) return PageDecision.Refuse(KeyboardError.NotEnabled)
        when (request) {
            PageRequest.Cancel -> return PageDecision.Cancel
            PageRequest.Open -> return if (inFront) PageDecision.OpenScreen else PageDecision.Refuse(KeyboardError.NotInFront)
            else -> Unit
        }
        val length = (request as? PageRequest.Type)?.text?.length ?: 0
        val error = when {
            !status.enabled || !status.available -> KeyboardError.NotEnabled
            !status.connected -> KeyboardError.NotConnected
            !inFront -> KeyboardError.NotInFront
            windowOpen || status.busy -> KeyboardError.Busy
            length > MAX_TEXT -> KeyboardError.TooLong
            else -> null
        }
        if (error != null) return PageDecision.Refuse(error)
        if (request is PageRequest.Secret) return PageDecision.OpenSecret
        return when {
            !needsConfirmation(mode, onlyWrites(request), stopPending) -> PageDecision.TypeNow
            confirmPaused -> PageDecision.Refuse(KeyboardError.Cancelled)
            else -> PageDecision.Confirm
        }
    }

    /**
     * A text as the confirmation shows it, in pieces: as it is typed, or a
     * mark ([Shown.mark]) for what cannot be seen — a line break (↵, the
     * break itself follows), a tab (⇥) and the blanks at either end (· for a
     * space, ␣ for any other). The confirmation draws the marks apart from
     * the text, so a text that holds the same signs cannot pass for them.
     */
    fun shownText(text: String): List<Shown> {
        val out = ArrayList<Shown>()
        fun add(piece: String, mark: Boolean) {
            val last = out.lastOrNull()
            if (last != null && last.mark == mark) out[out.size - 1] = Shown(last.text + piece, mark) else out += Shown(piece, mark)
        }
        val first = text.indexOfFirst { !isBlank(it) }.let { if (it < 0) text.length else it }
        val last = text.indexOfLast { !isBlank(it) }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' || c == '\n' -> {
                    add(LINE_BREAK_MARK, mark = true)
                    add("\n", mark = false)
                    // CRLF is one line break, as it is typed.
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\t' -> add(TAB_MARK, mark = true)
                (i < first || i > last) && isBlank(c) -> add(if (c == ' ') SPACE_MARK else BLANK_MARK, mark = true)
                else -> add(c.toString(), mark = false)
            }
            i++
        }
        return out
    }

    /** One piece of [shownText]. */
    data class Shown(val text: String, val mark: Boolean)

    const val LINE_BREAK_MARK = "↵"
    const val TAB_MARK = "⇥"
    const val SPACE_MARK = "·"
    const val BLANK_MARK = "␣"

    /** The text has a line break (typed as Shift+Enter). */
    fun hasLineBreak(text: String): Boolean = text.any { it == '\r' || it == '\n' }

    /**
     * What stands in the way of typing, checked before anything is sent:
     * the keyboard is off, no device is connected, typing is going on, the
     * text is too long. Null when nothing does.
     */
    fun precheck(status: KeyboardStatus, length: Int, max: Int = MAX_TEXT): KeyboardError? = when {
        !status.enabled || !status.available -> KeyboardError.NotEnabled
        !status.connected -> KeyboardError.NotConnected
        status.busy -> KeyboardError.Busy
        length > max -> KeyboardError.TooLong
        else -> null
    }

    /**
     * What the live field typed: the text went from [old] to [new]. The
     * common prefix stays; whatever followed it in [old] is deleted with
     * Backspace (the cursor is at the end of the field's text, the only edit
     * the live field allows), and the rest of [new] is typed.
     */
    fun liveDiff(old: String, new: String): Pair<Int, String> {
        var p = 0
        val n = minOf(old.length, new.length)
        while (p < n && old[p] == new[p]) p++
        return (old.length - p) to new.substring(p)
    }
}
