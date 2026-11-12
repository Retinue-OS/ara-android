package os.retinue.ara.wake

import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Where a phrase model in the list comes from. */
enum class WakeModelSource(val key: String) {
    /** One of the catalog's downloadable models ([WakeModelCatalog]). */
    Builtin("builtin"),

    /** A file the user picked on the phone. */
    File("file"),

    /** Downloaded from a URL the user typed. */
    Url("url"),
}

/**
 * One phrase (keyword) model in the list. [id] is opaque and stable: the
 * catalog's are `oww:<name>`, the user's own `user:<uuid>`; it names the file
 * ([WakeModelNames.fileName]) and is what a setting stores to pick the model.
 */
data class WakePhraseModel(
    val id: String,
    /** What the list shows: the catalog's name, the picked file's name, or the URL's last segment. */
    val name: String,
    val source: WakeModelSource,
    /** Where it downloads from (catalog and URL models). */
    val url: String? = null,
    /** SHA-256 (hex) of the catalog file: a download must match it, and it is recognised by it on the phone. */
    val sha256: String? = null,
) {
    /** Only the user's own models can be taken off the list. */
    val removable: Boolean get() = source != WakeModelSource.Builtin
}

/** A model of the list, with whether its file is on the phone. */
data class WakeModelEntry(val model: WakePhraseModel, val downloaded: Boolean)

/**
 * The downloadable models every phone lists: the pre-trained phrases of the
 * openWakeWord release whose feature models Ara uses (a "Hey Retinue" can be
 * added from a URL or a file until it ships here).
 */
object WakeModelCatalog {
    const val OWW_RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    const val MELSPECTROGRAM_URL = "$OWW_RELEASE/melspectrogram.onnx"
    const val EMBEDDING_URL = "$OWW_RELEASE/embedding_model.onnx"

    const val HEY_JARVIS = "oww:hey_jarvis_v0.1"

    val builtins: List<WakePhraseModel> = listOf(
        oww("hey_jarvis_v0.1", "Hey Jarvis", "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb"),
        oww("hey_mycroft_v0.1", "Hey Mycroft", "c2a311e8fa1338de89c31b3b46dc4dffd4af2f9a8d6ddead48893c2d301b1f18"),
        oww("hey_rhasspy_v0.1", "Hey Rhasspy", "5a9b3ed3be2910e35780e097905aa9f35a9c10038df47914cf2b3ec4d670f6ea"),
        oww("alexa_v0.1", "Alexa", "6ff566a01d12670e8d9e3c59da32651db1575d17272a601b7f8a39283dfbae3e"),
    )

    fun builtin(id: String?): WakePhraseModel? = builtins.firstOrNull { it.id == id }

    /** The catalog model whose file has this hash, if any. */
    fun bySha256(sha256: String): WakePhraseModel? = builtins.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    private fun oww(name: String, label: String, sha256: String) =
        WakePhraseModel("oww:$name", label, WakeModelSource.Builtin, "$OWW_RELEASE/$name.onnx", sha256)
}

object WakeModelNames {
    private const val MAX_NAME = 80

    /** The model's file name in the library directory: its id with anything but `[A-Za-z0-9._-]` as `_`. */
    fun fileName(id: String): String =
        id.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }.joinToString("") + ".onnx"

    /** A picked file's name as the list shows it. */
    fun ofFile(displayName: String?): String = clean(displayName) ?: "Uploaded model"

    /** A URL model's name: the URL's last path segment, else its host. */
    fun ofUrl(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            null
        }
        val segment = uri?.rawPath?.trimEnd('/')?.substringAfterLast('/')?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (_: Exception) {
                it
            }
        }
        return clean(segment) ?: clean(uri?.host) ?: "Downloaded model"
    }

    private fun clean(s: String?): String? =
        s?.filter { !it.isISOControl() }?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
}

/**
 * The user's own models (uploads and URL downloads), stored as JSON next to
 * their files. Entries that do not parse are dropped, never the whole list.
 */
object WakeModelIndex {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(models: List<WakePhraseModel>): String = buildJsonArray {
        models.filter { it.removable }.forEach { m ->
            add(
                buildJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    put("source", m.source.key)
                    m.url?.let { put("url", it) }
                },
            )
        }
    }.toString()

    fun decode(raw: String?): List<WakePhraseModel> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.startsWith(USER_PREFIX) && it.length > USER_PREFIX.length } ?: return@mapNotNull null
            val source = WakeModelSource.entries.firstOrNull { it.key == o.str("source") && it != WakeModelSource.Builtin }
                ?: return@mapNotNull null
            WakePhraseModel(id, o.str("name")?.takeIf { it.isNotBlank() } ?: "Model", source, o.str("url"))
        }.distinctBy { it.id }
    }

    const val USER_PREFIX = "user:"

    /** The id of a model of the user's own known only by its file's hash: the same file, the same id. */
    fun idOfContent(sha256: String): String =
        USER_PREFIX + UUID.nameUUIDFromBytes(sha256.lowercase().toByteArray(Charsets.US_ASCII))

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** The list the settings page shows: the catalog first, then the user's own in the order added. */
fun wakeModelList(user: List<WakePhraseModel>, downloaded: (WakePhraseModel) -> Boolean): List<WakeModelEntry> =
    (WakeModelCatalog.builtins + user).distinctBy { it.id }.map { WakeModelEntry(it, downloaded(it)) }
