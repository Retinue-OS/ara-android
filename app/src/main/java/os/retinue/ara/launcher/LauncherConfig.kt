package os.retinue.ara.launcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The launcher bar's entries and every edit the UI makes to them — pure and
 * immutable, so serialization and reordering are unit-tested. The "All apps"
 * slot is not an entry: the bar always appends it. Apps inside a folder are
 * addressed by the folder's id and their own.
 */
data class LauncherConfig(val entries: List<LauncherEntry>) {

    fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }

    /** Append [entry]; an app or a shortcut already on the bar is not added twice. */
    fun add(entry: LauncherEntry): LauncherConfig {
        if (entry is LauncherEntry.App && containsApp(entry.packageName, entry.activityName)) return this
        if (entry is LauncherEntry.Shortcut && containsShortcut(entry.packageName, entry.shortcutId)) return this
        if (indexOf(entry.id) >= 0) return this
        return copy(entries = entries + entry)
    }

    fun remove(id: String): LauncherConfig = copy(entries = entries.filterNot { it.id == id })

    /** Replace the entry with the same id (an edit); unknown ids change nothing. */
    fun replace(entry: LauncherEntry): LauncherConfig {
        val i = indexOf(entry.id)
        if (i < 0) return this
        return copy(entries = entries.toMutableList().also { it[i] = entry })
    }

    /** Move the entry [id] by [delta] places (−1 = left), clamped to the ends. */
    fun move(id: String, delta: Int): LauncherConfig {
        val from = indexOf(id)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, entries.lastIndex)
        return moveTo(from, to)
    }

    fun moveLeft(id: String) = move(id, -1)
    fun moveRight(id: String) = move(id, +1)

    /** Move the entry at [from] to index [to] (both clamped); the others keep their order. */
    fun moveTo(from: Int, to: Int): LauncherConfig {
        if (from !in entries.indices) return this
        val target = to.coerceIn(0, entries.lastIndex)
        if (from == target) return this
        return copy(entries = entries.moved(from, target))
    }

    /** Whether the app is pinned to the bar itself (apps in folders do not count). */
    fun containsApp(packageName: String, activityName: String): Boolean =
        entries.any { it is LauncherEntry.App && it.packageName == packageName && it.activityName == activityName }

    fun containsShortcut(packageName: String, shortcutId: String): Boolean =
        entries.any { it is LauncherEntry.Shortcut && it.packageName == packageName && it.shortcutId == shortcutId }

    /**
     * The shortcut ids of [packageName] on the bar itself. Not all that Ara keeps
     * pinned for the package: shortcuts only in All apps or in folders are pinned
     * too, so this is no set for LauncherApps.pinShortcuts (which replaces the
     * whole set per package).
     */
    fun shortcutIds(packageName: String): List<String> =
        entries.filterIsInstance<LauncherEntry.Shortcut>().filter { it.packageName == packageName }.map { it.shortcutId }

    fun canMoveLeft(id: String) = indexOf(id) > 0
    fun canMoveRight(id: String) = indexOf(id).let { it >= 0 && it < entries.lastIndex }

    // --- Folders -----------------------------------------------------------------

    val folders: List<LauncherEntry.Folder> get() = entries.filterIsInstance<LauncherEntry.Folder>()

    fun folder(id: String): LauncherEntry.Folder? = entries.getOrNull(indexOf(id)) as? LauncherEntry.Folder

    /** Apply [change] to the folder [folderId]; an unknown id, or an entry that is no folder, changes nothing. */
    fun updateFolder(folderId: String, change: (LauncherEntry.Folder) -> LauncherEntry.Folder): LauncherConfig {
        val i = indexOf(folderId)
        val folder = entries.getOrNull(i) as? LauncherEntry.Folder ?: return this
        val changed = change(folder)
        if (changed == folder) return this
        return copy(entries = entries.toMutableList().also { it[i] = changed })
    }

    /** Add [item] (an app or a web app) to the folder [folderId]; one already in it is not added twice. */
    fun addToFolder(folderId: String, item: LauncherEntry.Launchable) = updateFolder(folderId) { it.add(item) }

    fun removeFromFolder(folderId: String, appId: String) = updateFolder(folderId) { it.remove(appId) }

    /** Move the app [appId] within the folder [folderId] by [delta] places, clamped to the ends. */
    fun moveInFolder(folderId: String, appId: String, delta: Int) = updateFolder(folderId) { it.move(appId, delta) }

    /**
     * Move the bar's app or web app [entryId] into the folder [folderId] (if
     * the folder has it already, it just leaves the bar). Only those go into
     * folders: a link, a web page or another folder stays where it is.
     */
    fun moveIntoFolder(entryId: String, folderId: String): LauncherConfig {
        val item = entries.getOrNull(indexOf(entryId)) as? LauncherEntry.Launchable ?: return this
        if (folder(folderId) == null) return this
        return addToFolder(folderId, item).remove(entryId)
    }

    /** The custom folder icons in use (file names; see FolderIcons). */
    fun iconNames(): Set<String> = folders.mapNotNullTo(mutableSetOf()) { it.icon }

    // --- Uninstalled apps --------------------------------------------------------

    /**
     * The bar without the apps and pinned shortcuts of [packageNames] (apps that
     * were uninstalled; see PackageRemovedReceiver), on the bar and inside
     * folders. Folders stay, also when this empties them.
     */
    fun withoutPackages(packageNames: Set<String>): LauncherConfig {
        if (packageNames.isEmpty()) return this
        val kept = entries.mapNotNull { entry ->
            when (entry) {
                is LauncherEntry.App -> entry.takeUnless { it.packageName in packageNames }
                is LauncherEntry.Shortcut -> entry.takeUnless { it.packageName in packageNames }
                is LauncherEntry.Folder -> {
                    val apps = entry.apps.filterNot { it.packageName in packageNames }
                    if (apps.size == entry.apps.size) entry else entry.copy(apps = apps)
                }
                is LauncherEntry.Link, is LauncherEntry.WebPage -> entry
            }
        }
        return if (kept == entries) this else copy(entries = kept)
    }

    /**
     * The bar without the pinned shortcut [shortcutId] of [packageName], on the
     * bar and in folders: it was removed from the phone.
     */
    fun withoutShortcut(packageName: String, shortcutId: String): LauncherConfig {
        val key = LauncherEntry.Launchable.shortcutKey(packageName, shortcutId)
        val kept = entries.mapNotNull { entry ->
            when {
                entry is LauncherEntry.Shortcut && entry.key == key -> null
                entry is LauncherEntry.Folder && entry.containsKey(key) -> entry.copy(apps = entry.apps.filterNot { it.key == key })
                else -> entry
            }
        }
        return if (kept == entries) this else copy(entries = kept)
    }

    companion object {
        /** The four OS actions the web dock had (plan §1). */
        val DEFAULT = LauncherConfig(
            listOf(
                LauncherEntry.Link(id = "phone", label = "Phone", uri = "tel:"),
                LauncherEntry.Link(id = "messages", label = "Messages", uri = "sms:"),
                LauncherEntry.Link(id = "mail", label = "Mail", uri = "mailto:"),
                LauncherEntry.Link(id = "maps", label = "Maps", uri = "geo:0,0?q="),
            ),
        )
    }
}

/**
 * JSON form of [LauncherConfig]: `{"version":1,"entries":[{"type":"uri",…},…]}`.
 * Decoding is forgiving per entry, so one entry of an unknown future type does
 * not cost the user the rest of the bar — and per item inside a folder
 * (`{"type":"folder",…,"apps":[{"type":"app",…},{"type":"shortcut",…}]}`),
 * where anything but an app or a web app is dropped: folders do not nest.
 */
object LauncherCodec {

    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(config: LauncherConfig): String {
        val entries = JsonArray(config.entries.map { json.encodeToJsonElement(LauncherEntry.serializer(), it) })
        return JsonObject(
            mapOf(
                "version" to JsonPrimitive(VERSION),
                "entries" to entries,
            ),
        ).toString()
    }

    /** Decode; null input (never configured) or an unreadable document gives the defaults. */
    fun decode(raw: String?): LauncherConfig {
        if (raw.isNullOrBlank()) return LauncherConfig.DEFAULT
        val root = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return LauncherConfig.DEFAULT
        }
        val array = when (root) {
            is JsonObject -> root["entries"] as? JsonArray ?: return LauncherConfig.DEFAULT
            is JsonArray -> root
            else -> return LauncherConfig.DEFAULT
        }
        val entries = array.mapNotNull { element ->
            try {
                normalize(json.decodeFromJsonElement(LauncherEntry.serializer(), forgiveFolderApps(element)))
            } catch (_: Exception) {
                null
            }
        }
        // Ids must be unique for edits to address one entry; keep the first.
        return LauncherConfig(entries.distinctBy { it.id })
    }

    /**
     * A folder's `apps`, keeping the items that decode as an app or a web app
     * (one without a type is an app: folders first stored apps only) and
     * dropping the rest; a malformed list counts as empty.
     */
    private fun forgiveFolderApps(element: JsonElement): JsonElement {
        if (element !is JsonObject || element.typeOf() != "folder") return element
        val apps = element["apps"] as? JsonArray ?: JsonArray(emptyList())
        val kept = apps.mapNotNull { item ->
            if (item !is JsonObject) return@mapNotNull null
            val typed = if (item.typeOf() == null) JsonObject(item + ("type" to JsonPrimitive("app"))) else item
            typed.takeIf { runCatching { json.decodeFromJsonElement(LauncherEntry.Launchable.serializer(), it) }.isSuccess }
        }
        return JsonObject(element + ("apps" to JsonArray(kept)))
    }

    private fun JsonElement.typeOf(): String? = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

    /** A folder holds each app once (and each id once), and an icon only by a plain file name, never a path. */
    private fun normalize(entry: LauncherEntry): LauncherEntry {
        if (entry !is LauncherEntry.Folder) return entry
        return entry.copy(
            icon = entry.icon?.takeIf { LauncherEntry.Folder.isIconName(it) },
            apps = entry.apps.distinctBy { it.key }.distinctBy { it.id },
        )
    }
}
