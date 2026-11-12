package os.retinue.ara.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The dashboard's per-device preferences, held by the app for every endpoint
 * it shows: the voice timeouts, the shelf size and the layout (column sizes,
 * list or card view per card). In a browser the dashboard keeps them in
 * localStorage, per browser and site; inside the app a quiet desk or a car, and
 * the size of the screen, belong to the phone, not to each server. The
 * dashboard reads and writes them through `RetinueShell.deviceSettings()` and
 * `setDeviceSetting(key, value)` (its components/device-store.js), and the
 * app's own settings page shows the voice timeouts and the shelf size.
 *
 * Values are strings under the dashboard's own localStorage keys, opaque to
 * the app: the dashboard parses and clamps what it reads. Only the keys below
 * are kept (the dashboard mirrors them), with bounded sizes, so a page cannot
 * use the store to pass other data to the next endpoint. Framework-free and
 * unit-tested.
 */
object DeviceWebSettings {

    /** The keys kept as they are. */
    val KEYS: Set<String> = setOf(
        "retinue-voice-silence-ms",
        "retinue-voice-nospeech-ms",
        "retinue.shelf.size",
        "retinue.layout.v1",
        "retinue.chatpage.v1",
    )

    /** Key families: one key per card (`retinue.view.<card>`). */
    val PREFIXES: List<String> = listOf("retinue.view.")

    const val SILENCE_KEY = "retinue-voice-silence-ms"
    const val NO_SPEECH_KEY = "retinue-voice-nospeech-ms"
    const val SHELF_SIZE_KEY = "retinue.shelf.size"

    const val MAX_KEY_LENGTH = 80
    const val MAX_VALUE_LENGTH = 4096
    const val MAX_ENTRIES = 64

    private val keyChars = Regex("^[A-Za-z0-9._:-]+$")
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether [key] is one the app keeps. */
    fun isKey(key: String?): Boolean {
        if (key.isNullOrEmpty() || key.length > MAX_KEY_LENGTH || !keyChars.matches(key)) return false
        return key in KEYS || PREFIXES.any { key.startsWith(it) && key.length > it.length }
    }

    /**
     * [current] with [key] set to [value] (null removes it), or null when the
     * change is refused: a key the app does not keep, a value too long, or a
     * new key beyond [MAX_ENTRIES].
     */
    fun apply(current: Map<String, String>, key: String?, value: String?): Map<String, String>? {
        if (!isKey(key)) return null
        key!!
        if (value == null) return current - key
        if (value.length > MAX_VALUE_LENGTH) return null
        if (key !in current && current.size >= MAX_ENTRIES) return null
        return current + (key to value)
    }

    /** The values as stored and as the page script receives them: a JSON object of strings. */
    fun toJson(values: Map<String, String>): JsonObject = JsonObject(values.mapValues { JsonPrimitive(it.value) })

    fun encode(values: Map<String, String>): String = toJson(values).toString()

    /**
     * The values of a stored document: only kept keys with string values of an
     * accepted size, at most [MAX_ENTRIES] of them; anything unreadable is none.
     */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, element) in root) {
            if (out.size >= MAX_ENTRIES) break
            val prim = element as? JsonPrimitive ?: continue
            if (!prim.isString || !isKey(key) || prim.content.length > MAX_VALUE_LENGTH) continue
            out[key] = prim.content
        }
        return out
    }

    /** The keys whose values differ between [before] and [after]. */
    fun changedKeys(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
}
