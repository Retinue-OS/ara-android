package os.retinue.ara.shell

/** What the shell does with a `placeCall` from the page. */
sealed class CallOutcome {
    /** `ACTION_CALL`: the call starts without a tap. */
    data class Call(val number: String) : CallOutcome()

    /** `ACTION_DIAL`: the dialer opens with the number, the user starts it. */
    data class Dial(val number: String, val reason: String) : CallOutcome()

    /** Nothing is started. */
    data class Refused(val reason: String) : CallOutcome()
}

/**
 * The decisions behind `RetinueShell.placeCall(number)` (the shell ↔ web
 * contract). Pure, so the order of the checks is pinned down by unit tests.
 */
object CallLogic {

    const val EMERGENCY = "emergency"
    const val DISABLED = "disabled"
    const val NO_PERMISSION = "no-permission"
    const val SERVICE_CODE = "service-code"
    const val INVALID_NUMBER = "invalid-number"
    const val NOT_IN_VOICE = "not-in-voice"
    const val NO_TELEPHONY = "no-telephony"
    const val FAILED = "failed"

    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 20

    /** Longer than any number as people write it; nothing longer is even looked at. */
    private const val MAX_RAW = 64

    /** Digits, `*` and `#` together: the longest number, with room for a code around it. */
    private const val MAX_LENGTH = 32

    // \p{Zs}: the no-break spaces formatted numbers come with, which \s does not cover.
    private val separators = Regex("[\\s\\p{Zs}\\-./()]")
    // "+44 (0)20 …": the trunk zero written beside a country code is not dialled with it.
    private val trunkZero = Regex("\\(\\s*0\\s*\\)")
    private val dialable = Regex("\\+?[0-9*#]+")

    /**
     * The number as the dialer gets it: separators (spaces, dashes, dots,
     * parentheses, slashes) and a leading `tel:` dropped, as is the `(0)` of
     * `+44 (0)20 …`. An optional leading `+`, then digits, `*` and `#` only,
     * with 3 to 20 digits and at most 32 characters; null otherwise — a page
     * never hands the dialer anything else (no `,` pauses or `;` extensions
     * that dial on by themselves).
     */
    fun normalize(raw: String?): String? {
        if (raw == null || raw.length > MAX_RAW) return null
        var s = raw.trim()
        if (s.startsWith("tel:", ignoreCase = true)) s = s.substring(4)
        s = s.trimStart()
        if (s.startsWith("+")) s = s.replaceFirst(trunkZero, "")
        s = s.replace(separators, "")
        if (s.length > MAX_LENGTH || !dialable.matches(s)) return null
        val digits = s.count { it.isDigit() }
        return if (digits in MIN_DIGITS..MAX_DIGITS) s else null
    }

    /**
     * A service code rather than a number to talk to: anything with `*` or
     * `#` (`*21*…#` forwards every call, `*123#` asks the network something).
     * Placed directly, the phone would carry it out unseen.
     */
    fun isServiceCode(number: String): Boolean = number.any { it == '*' || it == '#' }

    /**
     * What a `placeCall` comes to. In this order: no valid number, or no phone
     * to call with, starts nothing; neither does a call outside a spoken
     * conversation ([voiceEngaged]: the page reports one with
     * `setVoiceActive(true)`), so a number that turns up anywhere else, in a
     * typed chat or a page just loading, dials nothing. (The page says so
     * itself: this guards against its mistakes, the bridge trusts the
     * configured origin.) An emergency number always opens the dialer (Android
     * lets no third-party app call one directly); so does a service code
     * ([isServiceCode]), whatever the setting, and a call with direct calls
     * switched off ([enabled]) or without the `CALL_PHONE` permission.
     * Otherwise the call is placed.
     */
    fun decide(
        number: String?,
        voiceEngaged: Boolean,
        enabled: Boolean,
        permissionGranted: Boolean,
        isEmergency: Boolean,
        hasTelephony: Boolean,
    ): CallOutcome = when {
        number == null -> CallOutcome.Refused(INVALID_NUMBER)
        !hasTelephony -> CallOutcome.Refused(NO_TELEPHONY)
        !voiceEngaged -> CallOutcome.Refused(NOT_IN_VOICE)
        isEmergency -> CallOutcome.Dial(number, EMERGENCY)
        isServiceCode(number) -> CallOutcome.Dial(number, SERVICE_CODE)
        !enabled -> CallOutcome.Dial(number, DISABLED)
        !permissionGranted -> CallOutcome.Dial(number, NO_PERMISSION)
        else -> CallOutcome.Call(number)
    }
}
