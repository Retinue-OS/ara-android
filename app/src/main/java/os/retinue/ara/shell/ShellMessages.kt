package os.retinue.ara.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import os.retinue.ara.data.DeviceWebSettings
import os.retinue.ara.health.HealthSyncStatus

/**
 * A message the page's `window.RetinueShell` (see assets/retinue-shell.js) posts
 * to the shell. The wire format is a JSON object with a `type`.
 */
sealed class WebMessage {
    /** `setVoiceActive(bool)`: the page has the mic open (wake pauses, screen stays on). */
    data class VoiceActive(val active: Boolean) : WebMessage()

    /**
     * `openSettings(section?)`: show the native settings — at [section] when it
     * is one the shell knows ([ShellMessages.SECTION_ENDPOINTS]), else at the top.
     */
    data class OpenSettings(val section: String? = null) : WebMessage()

    /** `selectEndpoint(id)`: show another of the configured endpoints (the page may only ask; the shell decides). */
    data class SelectEndpoint(val id: String) : WebMessage()

    /**
     * `setDeviceSetting(key, value)`: one of the dashboard's per-device
     * preferences, held by the app for every endpoint ([value] null removes
     * it; the app keeps only the keys DeviceWebSettings knows).
     */
    data class DeviceSet(val key: String, val value: String?) : WebMessage()

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

    /** `healthSync()`: a deliberate Health Connect sync, now. */
    data object HealthSync : WebMessage()

    /** `setHealthSyncInterval(minutes)`: automatic syncs at most this often (≥ 1). */
    data class HealthSyncInterval(val minutes: Int) : WebMessage()

    /** `setHealthAutoSync(bool)`: sync when Ara comes to the front. */
    data class HealthAutoSync(val on: Boolean) : WebMessage()

    /** `placeCall(number)`: call [number] — as it came from the page, see CallLogic. */
    data class PlaceCall(val number: String) : WebMessage()
}

/** A voice the stand-in exposes as a `SpeechSynthesisVoice`. */
data class ShellVoice(val name: String, val lang: String, val localService: Boolean, val isDefault: Boolean)

/**
 * One configured endpoint as the page sees it (`RetinueShell.endpoints()`): its
 * id, the label the shell shows for it (the instance's own name, see
 * EndpointLabels) and whether it is the one shown. No URL: the current page
 * knows its own origin, and the other servers are none of its business.
 */
data class EndpointLabel(val id: String, val label: String, val current: Boolean)

/**
 * Parsing of page → shell messages and building of shell → page scripts (plan
 * §4). Pure, so the wire format is pinned down by unit tests.
 */
object ShellMessages {

    private val json = Json { ignoreUnknownKeys = true }

    /** The event name the web app listens for (contract §4). */
    const val EVENT = "retinue-shell"

    /** The one settings section a page may ask for by name: the endpoint list (and the editor, with one endpoint). */
    const val SECTION_ENDPOINTS = "endpoints"

    /** The type of the `retinue-shell` event that says the endpoint list changed (nobody needs to take it). */
    const val ENDPOINTS_EVENT = "endpoints"

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
            "openSettings" -> WebMessage.OpenSettings(obj.str("section")?.takeIf { it.isNotBlank() })
            "endpoint.select" -> WebMessage.SelectEndpoint(obj.str("id")?.takeIf { it.isNotBlank() } ?: return null)
            "device.set" -> {
                val key = obj.str("key")?.takeIf { it.isNotBlank() } ?: return null
                val value = when (val v = obj["value"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> if (v.isString) v.content else return null
                    else -> return null
                }
                WebMessage.DeviceSet(key, value)
            }
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
            "health.sync" -> WebMessage.HealthSync
            "health.interval" -> {
                val minutes = obj.long("minutes")?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
                WebMessage.HealthSyncInterval(minutes.toInt())
            }
            "health.auto" -> WebMessage.HealthAutoSync(obj.bool("on") ?: return null)
            "call.place" -> WebMessage.PlaceCall(obj.str("number")?.takeIf { it.isNotBlank() } ?: return null)
            else -> null
        }
    }

    /**
     * `window.dispatchEvent(new CustomEvent('retinue-shell', {detail: {type, …extra}}))`.
     * The script evaluates to whether the page handled the event: it called
     * `preventDefault()` on it, or one of its `retinue-shell` listeners ran for
     * this very event (tracked by assets/retinue-shell.js). See [eventHandled].
     */
    fun eventScript(type: String, extra: JsonObject? = null): String {
        val detail = buildJsonObject {
            put("type", type)
            extra?.forEach { (k, v) -> if (k != "type") put(k, v) }
        }
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

    /** The type of the `retinue-shell` events about Health Connect sync. */
    const val HEALTH_SYNC_EVENT = "health-sync"

    /** The type of the `retinue-shell` events about a `placeCall` (see [callEvent]). */
    const val CALL_EVENT = "call"

    /**
     * What came of a `placeCall`: `placed`, `dialer` (with `reason`) or
     * `refused` (with `reason`), and the number as dialled when there was one.
     */
    fun callEvent(outcome: CallOutcome, failed: Boolean = false): String = eventScript(
        CALL_EVENT,
        buildJsonObject {
            when {
                failed -> {
                    put("state", "refused")
                    put("reason", CallLogic.FAILED)
                }
                outcome is CallOutcome.Call -> put("state", "placed")
                outcome is CallOutcome.Dial -> {
                    put("state", "dialer")
                    put("reason", outcome.reason)
                }
                outcome is CallOutcome.Refused -> {
                    put("state", "refused")
                    put("reason", outcome.reason)
                }
            }
            when (outcome) {
                is CallOutcome.Call -> put("number", outcome.number)
                is CallOutcome.Dial -> put("number", outcome.number)
                is CallOutcome.Refused -> Unit
            }
        },
    )

    /** The sync status the page's `healthSyncStatus()` and `healthConnectAvailable()` answer from. */
    fun healthStatus(status: HealthSyncStatus): String = receiveScript(
        buildJsonObject {
            put("type", "health.status")
            put("status", status.toJson())
        },
    )

    /** The endpoint list as the page's `endpoints()` answers it (and the shell bakes it into the script). */
    fun endpointsJson(list: List<EndpointLabel>): JsonArray = buildJsonArray {
        list.forEach { e ->
            add(
                buildJsonObject {
                    put("id", e.id)
                    put("label", e.label)
                    put("current", e.current)
                },
            )
        }
    }

    /**
     * The per-device preferences changed (here, on the app's settings page, or
     * on another endpoint's page): the page's copy is replaced, and the page
     * script announces the keys that changed with a `device-settings` event.
     */
    fun deviceStatus(values: Map<String, String>): String = receiveScript(
        buildJsonObject {
            put("type", "device.status")
            put("settings", DeviceWebSettings.toJson(values))
        },
    )

    /** The endpoint list changed: the page's copy is replaced (an `endpoints` event follows separately). */
    fun endpointsStatus(list: List<EndpointLabel>): String = receiveScript(
        buildJsonObject {
            put("type", "endpoints.status")
            put("endpoints", endpointsJson(list))
        },
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
