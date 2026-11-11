package os.retinue.ara.keyboard

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /** What the page hears of a secret that failed: never why it lies in the secret itself. */
    fun secretErrorForPage(error: KeyboardError): KeyboardError = when (error) {
        KeyboardError.UnsupportedChar, KeyboardError.TooLong -> KeyboardError.Failed
        else -> error
    }

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
