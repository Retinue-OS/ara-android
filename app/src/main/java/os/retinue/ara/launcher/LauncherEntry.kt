package os.retinue.ara.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One slot of the launcher bar (plan §1). Stored on the device as JSON (see
 * [LauncherConfig]); the `type` discriminator is `app`, `uri` or `web`.
 */
@Serializable
sealed class LauncherEntry {
    abstract val id: String
    abstract val label: String

    /** An installed app's launcher activity. */
    @Serializable
    @SerialName("app")
    data class App(
        override val id: String,
        override val label: String,
        val packageName: String,
        val activityName: String,
    ) : LauncherEntry()

    /** A URI handed to Android: `tel:`, `sms:`, `mailto:`, `geo:`, `https:`, `intent:` … */
    @Serializable
    @SerialName("uri")
    data class Link(
        override val id: String,
        override val label: String,
        val uri: String,
    ) : LauncherEntry()

    /** A page of the web app, opened in the shell's WebView (path under the base URL). */
    @Serializable
    @SerialName("web")
    data class WebPage(
        override val id: String,
        override val label: String,
        val path: String,
    ) : LauncherEntry()
}

/** How a [LauncherEntry.Link] is started on Android, chosen by its scheme. */
enum class LinkAction {
    /** `tel:` — open the dialer with the number filled in (never call directly). */
    Dial,

    /** `sms:`, `smsto:`, `mms:`, `mailto:` — open a composer. */
    SendTo,

    /** `intent:` — parse with Intent.parseUri (sanitized). */
    ParseIntent,

    /** Everything else (`geo:`, `https:`, `market:` …) — ACTION_VIEW. */
    View,
}

object LauncherLinks {
    fun actionFor(uri: String): LinkAction {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
        return when (scheme) {
            "tel" -> LinkAction.Dial
            "sms", "smsto", "mms", "mmsto", "mailto" -> LinkAction.SendTo
            "intent" -> LinkAction.ParseIntent
            else -> LinkAction.View
        }
    }

    /** A URI the editor accepts: it has a scheme and something after it, or is a bare scheme like `sms:`. */
    fun isValid(uri: String): Boolean {
        val u = uri.trim()
        val colon = u.indexOf(':')
        if (colon <= 0) return false
        val scheme = u.substring(0, colon)
        if (!scheme[0].isLetter()) return false
        return scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }
}
