package os.retinue.ara.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One slot of the launcher bar (plan §1). Stored on the device as JSON (see
 * [LauncherConfig]); the `type` discriminator is `app`, `shortcut`, `uri`, `web` or `folder`.
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

    /**
     * A pinned shortcut another app asked the launcher to hold (Chrome's
     * "Install app" / "Add to home screen", an app's own shortcuts), accepted
     * through the pin request. Started through LauncherApps, so it works only
     * while Ara is the default home app.
     */
    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        override val id: String,
        override val label: String,
        val packageName: String,
        val shortcutId: String,
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

    /**
     * A folder of installed apps. It holds apps only — never pinned shortcuts,
     * links, web pages or other folders — so folders cannot nest. [kind] is
     * the folder type picked when it was made (its symbol, default name and
     * suggested apps); [icon], when set, names an image in app storage (see
     * FolderIcons) drawn instead of the type's symbol.
     */
    @Serializable
    @SerialName("folder")
    data class Folder(
        override val id: String,
        override val label: String,
        val kind: FolderKind = FolderKind.General,
        val icon: String? = null,
        val apps: List<App> = emptyList(),
    ) : LauncherEntry() {

        fun containsApp(packageName: String, activityName: String): Boolean =
            apps.any { it.packageName == packageName && it.activityName == activityName }

        /** Append [app]; an app already in the folder is not added twice. */
        fun add(app: App): Folder {
            if (containsApp(app.packageName, app.activityName) || apps.any { it.id == app.id }) return this
            return copy(apps = apps + app)
        }

        fun remove(appId: String): Folder =
            if (apps.none { it.id == appId }) this else copy(apps = apps.filterNot { it.id == appId })

        /** Move the app [appId] by [delta] places (−1 = earlier), clamped to the ends. */
        fun move(appId: String, delta: Int): Folder {
            val from = apps.indexOfFirst { it.id == appId }
            if (from < 0) return this
            val to = (from + delta).coerceIn(0, apps.lastIndex)
            if (from == to) return this
            return copy(apps = apps.moved(from, to))
        }

        companion object {
            private val ICON_NAME = Regex("[A-Za-z0-9-]{1,64}\\.png")

            /** A custom icon is a plain file name the shell generated, never a path. */
            fun isIconName(name: String): Boolean = ICON_NAME.matches(name)
        }
    }
}

/** This list with the item at [from] moved to index [to] (both valid); the others keep their order. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    val list = toMutableList()
    list.add(to, list.removeAt(from))
    return list
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
