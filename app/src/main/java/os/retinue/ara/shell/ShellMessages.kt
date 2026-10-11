package os.retinue.ara.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A message the page's `window.RetinueShell` (see assets/retinue-shell.js) posts
 * to the shell. The wire format is a JSON object with a `type`.
 */
sealed class WebMessage {
    /** `setVoiceActive(bool)`: the page has the mic open (wake pauses, screen stays on). */
    data class VoiceActive(val active: Boolean) : WebMessage()

    /** `openSettings()`: show the native settings. */
    data object OpenSettings : WebMessage()

    /** The `speechSynthesis` stand-in asks for one utterance to be spoken now. */
    data class Speak(
        val id: Long,
        val text: String,
        val lang: String?,
        val voice: String?,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
    ) : WebMessage()

    /** Stop speaking (cancel and pause both stop the engine; the page keeps the queue). */
    data object StopSpeaking : WebMessage()

    /** The stand-in wants the voice list. */
    data object Voices : WebMessage()
}

/** A voice the stand-in exposes as a `SpeechSynthesisVoice`. */
data class ShellVoice(val name: String, val lang: String, val localService: Boolean, val isDefault: Boolean)

/**
 * Parsing of page → shell messages and building of shell → page scripts (plan
 * §4). Pure, so the wire format is pinned down by unit tests.
 */
object ShellMessages {

    private val json = Json { ignoreUnknownKeys = true }

    /** The event name the web app listens for (contract §4). */
    const val EVENT = "retinue-shell"

    /** Parse one message; null for malformed or unknown ones (ignored). */
    fun parse(raw: String?): WebMessage? {
        if (raw.isNullOrBlank()) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return when (obj.str("type")) {
            "voiceActive" -> WebMessage.VoiceActive(obj.bool("active") ?: false)
            "openSettings" -> WebMessage.OpenSettings
            "tts.speak" -> {
                val id = obj.long("id") ?: return null
                WebMessage.Speak(
                    id = id,
                    text = obj.str("text").orEmpty(),
                    lang = obj.str("lang")?.takeIf { it.isNotBlank() },
                    voice = obj.str("voice")?.takeIf { it.isNotBlank() },
                    rate = (obj.num("rate") ?: 1.0).toFloat().coerceIn(0.1f, 10f),
                    pitch = (obj.num("pitch") ?: 1.0).toFloat().coerceIn(0f, 2f),
                    volume = (obj.num("volume") ?: 1.0).toFloat().coerceIn(0f, 1f),
                )
            }
            "tts.cancel", "tts.pause" -> WebMessage.StopSpeaking
            "tts.voices" -> WebMessage.Voices
            else -> null
        }
    }

    /**
     * `window.dispatchEvent(new CustomEvent('retinue-shell', {detail: {type}}))`.
     * The script evaluates to whether the page handled the event: it called
     * `preventDefault()` on it, or one of its `retinue-shell` listeners ran for
     * this very event (tracked by assets/retinue-shell.js). See [eventHandled].
     */
    fun eventScript(type: String): String {
        val detail = buildJsonObject { put("type", type) }
        return "(function(){var e=new CustomEvent('$EVENT',{detail:$detail,cancelable:true});" +
            "window.dispatchEvent(e);" +
            "return e.defaultPrevented||!!(window.__retinueShellTaken&&window.__retinueShellTaken(e));})();"
    }

    /** The result of [eventScript] as `evaluateJavascript` reports it (JSON). */
    fun eventHandled(result: String?): Boolean = result?.trim() == "true"

    /** Deliver an internal message to the page-side shim (speech events, voices). */
    fun receiveScript(message: JsonObject): String =
        "window.__retinueShellReceive&&window.__retinueShellReceive($message);"

    fun speechStart(id: Long) = receiveScript(buildJsonObject { put("type", "tts.start"); put("id", id) })

    fun speechEnd(id: Long) = receiveScript(buildJsonObject { put("type", "tts.end"); put("id", id) })

    fun speechError(id: Long, error: String) = receiveScript(
        buildJsonObject { put("type", "tts.error"); put("id", id); put("error", error) },
    )

    fun speechBoundary(id: Long, charIndex: Int) = receiveScript(
        buildJsonObject { put("type", "tts.boundary"); put("id", id); put("charIndex", charIndex) },
    )

    fun voices(list: List<ShellVoice>): String = receiveScript(
        buildJsonObject {
            put("type", "tts.voices")
            put(
                "voices",
                buildJsonArray {
                    list.forEach { v ->
                        add(
                            buildJsonObject {
                                put("name", v.name)
                                put("lang", v.lang)
                                put("localService", v.localService)
                                put("default", v.isDefault)
                            },
                        )
                    }
                },
            )
        },
    )

    private fun JsonObject.prim(key: String): JsonPrimitive? {
        val e: JsonElement = this[key] ?: return null
        if (e is JsonObject || e is JsonArray) return null
        return e.jsonPrimitive
    }

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.num(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.intOrNull?.toLong() }
}
