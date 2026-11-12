package os.retinue.ara.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import os.retinue.ara.data.Endpoint
import os.retinue.ara.net.CertificateImport
import os.retinue.ara.ui.EndpointDraft
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode

/** An endpoint as the settings page's editor sends it for saving (see [SettingsMessages.toEndpointDraft]). */
data class PageDraft(
    val id: String,
    val isNew: Boolean,
    val baseUrl: String,
    val certificateAlias: String?,
    val username: String,
    val password: String,
    val forgetPassword: Boolean,
    val healthUpload: Boolean,
)

/** What the settings page (assets/settings/page.mjs) asks of the app. */
sealed class SettingsAction {
    /** The page loaded: the app answers with the state, and keeps answering it. */
    data object Hello : SettingsAction()
    data object Close : SettingsAction()

    /** The dashboard failed to load: try again. */
    data object Retry : SettingsAction()

    /** Open [url], a page of the current endpoint, in the dashboard (the page closes). */
    data class Open(val url: String) : SettingsAction()
    data class SelectEndpoint(val id: String) : SettingsAction()
    data class SaveEndpoint(val request: String, val draft: PageDraft) : SettingsAction()
    data class RemoveEndpoint(val id: String) : SettingsAction()

    /** The KeyChain chooser, for the endpoint at [baseUrl]; the answer goes back with [request]. */
    data class ChooseCertificate(val request: String, val baseUrl: String, val current: String?) : SettingsAction()

    /**
     * Import a PKCS#12 file through Android's certificate installer, for the
     * endpoint at [baseUrl]: the outcome goes back with [request], and once it
     * is installed the chooser's answer too, as for [ChooseCertificate].
     */
    data class ImportCertificate(val request: String, val baseUrl: String) : SettingsAction()

    /** The current endpoint's health switch. */
    data class HealthUpload(val on: Boolean) : SettingsAction()
    data object HealthSync : SettingsAction()
    data class HealthAuto(val on: Boolean) : SettingsAction()
    data class HealthInterval(val minutes: Int) : SettingsAction()
    data object HomeRequest : SettingsAction()
    data class SplitLaunch(val on: Boolean) : SettingsAction()
    data class SplitRecents(val on: Boolean) : SettingsAction()

    /** Calls asked for by voice are placed without a tap (the app asks for the permission when turned on). */
    data class DirectCalls(val on: Boolean) : SettingsAction()

    /** The launcher bar's own editor (native: it is the bar's). */
    data object LauncherEdit : SettingsAction()

    /** Android's page where the user gives (or takes back) Ara's usage access, for the tray's order. */
    data object LauncherUsageAccess : SettingsAction()
    data class WakeModeSet(val mode: WakeMode) : SettingsAction()
    data class WakeThreshold(val value: Float) : SettingsAction()
    data object WakeFeatures : SettingsAction()
    data class WakePhrase(val url: String) : SettingsAction()
    data object WakePick : SettingsAction()
    data object WakeFullScreen : SettingsAction()
    data object WakeOverlay : SettingsAction()
    data object WakeNotifications : SettingsAction()
    data class UpdateAuto(val on: Boolean) : SettingsAction()
    data class UpdateChannelSet(val channel: UpdateChannel) : SettingsAction()
    data object UpdateCheck : SettingsAction()
    data object UpdateInstall : SettingsAction()
    data object UpdateConfirm : SettingsAction()
    data object UpdateCancel : SettingsAction()
    data object UpdateAllowInstalls : SettingsAction()

    /** One of the dashboard's per-device preferences (DeviceWebSettings), for every endpoint. */
    data class DeviceSet(val key: String, val value: String?) : SettingsAction()
}

/**
 * The settings page's messages, both ways: parsing what it sends (anything
 * malformed or unknown is ignored) and building what the app answers. Pure,
 * so the wire format is pinned down by unit tests.
 */
object SettingsMessages {

    private val json = Json { ignoreUnknownKeys = true }
    private val idChars = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val requestChars = Regex("^[A-Za-z0-9_-]{1,32}$")
    private const val MAX_URL = 2048
    private const val MAX_TEXT = 1024

    fun parse(raw: String?): SettingsAction? {
        if (raw.isNullOrBlank() || raw.length > 64 * 1024) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return when (obj.str("type")) {
            "hello" -> SettingsAction.Hello
            "close" -> SettingsAction.Close
            "retry" -> SettingsAction.Retry
            "open" -> SettingsAction.Open(obj.text("url", MAX_URL) ?: return null)
            "endpoint.select" -> SettingsAction.SelectEndpoint(obj.id("id") ?: return null)
            "endpoint.remove" -> SettingsAction.RemoveEndpoint(obj.id("id") ?: return null)
            "endpoint.save" -> {
                val request = obj.request() ?: return null
                val d = obj["draft"] as? JsonObject ?: return null
                SettingsAction.SaveEndpoint(
                    request,
                    PageDraft(
                        id = d.id("id") ?: return null,
                        isNew = d.bool("isNew") ?: false,
                        baseUrl = d.text("baseUrl", MAX_URL) ?: "",
                        certificateAlias = d.text("certificateAlias", MAX_TEXT)?.takeIf { it.isNotBlank() },
                        username = d.text("username", MAX_TEXT) ?: "",
                        password = d.text("password", MAX_TEXT) ?: "",
                        forgetPassword = d.bool("forgetPassword") ?: false,
                        healthUpload = d.bool("healthUpload") ?: false,
                    ),
                )
            }
            "endpoint.certificate" -> SettingsAction.ChooseCertificate(
                request = obj.request() ?: return null,
                baseUrl = obj.text("baseUrl", MAX_URL) ?: "",
                current = obj.text("current", MAX_TEXT)?.takeIf { it.isNotBlank() },
            )
            "endpoint.certificateImport" -> SettingsAction.ImportCertificate(
                request = obj.request() ?: return null,
                baseUrl = obj.text("baseUrl", MAX_URL) ?: "",
            )
            "health.upload" -> SettingsAction.HealthUpload(obj.bool("on") ?: return null)
            "health.sync" -> SettingsAction.HealthSync
            "health.auto" -> SettingsAction.HealthAuto(obj.bool("on") ?: return null)
            "health.interval" -> {
                val minutes = obj.long("minutes")?.takeIf { it in 1..1440 } ?: return null
                SettingsAction.HealthInterval(minutes.toInt())
            }
            "home.request" -> SettingsAction.HomeRequest
            "split.launch" -> SettingsAction.SplitLaunch(obj.bool("on") ?: return null)
            "split.recents" -> SettingsAction.SplitRecents(obj.bool("on") ?: return null)
            "calls.direct" -> SettingsAction.DirectCalls(obj.bool("on") ?: return null)
            "launcher.edit" -> SettingsAction.LauncherEdit
            "launcher.usageAccess" -> SettingsAction.LauncherUsageAccess
            "wake.mode" -> {
                val key = obj.str("mode") ?: return null
                val mode = WakeMode.entries.firstOrNull { it.key == key } ?: return null
                SettingsAction.WakeModeSet(mode)
            }
            "wake.threshold" -> {
                val v = obj.num("value")?.takeIf { it.isFinite() } ?: return null
                SettingsAction.WakeThreshold(v.toFloat())
            }
            "wake.features" -> SettingsAction.WakeFeatures
            "wake.phrase" -> SettingsAction.WakePhrase(obj.text("url", MAX_URL)?.takeIf { it.isNotBlank() } ?: return null)
            "wake.pick" -> SettingsAction.WakePick
            "wake.fullScreen" -> SettingsAction.WakeFullScreen
            "wake.overlay" -> SettingsAction.WakeOverlay
            "wake.notifications" -> SettingsAction.WakeNotifications
            "update.auto" -> SettingsAction.UpdateAuto(obj.bool("on") ?: return null)
            "update.channel" -> {
                val key = obj.str("channel") ?: return null
                SettingsAction.UpdateChannelSet(UpdateChannel.entries.firstOrNull { it.key == key } ?: return null)
            }
            "update.check" -> SettingsAction.UpdateCheck
            "update.install" -> SettingsAction.UpdateInstall
            "update.confirm" -> SettingsAction.UpdateConfirm
            "update.cancel" -> SettingsAction.UpdateCancel
            "update.allowInstalls" -> SettingsAction.UpdateAllowInstalls
            "device.set" -> {
                val key = obj.text("key", 128)?.takeIf { it.isNotBlank() } ?: return null
                val value = when (val v = obj["value"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> if (v.isString) v.content else return null
                    else -> return null
                }
                SettingsAction.DeviceSet(key, value)
            }
            else -> null
        }
    }

    /**
     * The editor's draft as the app checks it before saving: what is stored
     * already ([existing]: a stored password, the name) comes from the app,
     * never from the page.
     */
    fun toEndpointDraft(d: PageDraft, existing: Endpoint?): EndpointDraft = EndpointDraft(
        id = d.id,
        isNew = existing == null,
        baseUrl = d.baseUrl,
        certificateAlias = d.certificateAlias,
        username = d.username,
        password = d.password,
        hasStoredPassword = existing?.hasCredentials == true,
        forgetPassword = d.forgetPassword,
        healthUpload = d.healthUpload,
        name = existing?.knownName,
    )

    /** How the page names a [EndpointDraft.Problem]. */
    fun problemKey(problem: EndpointDraft.Problem): String = when (problem) {
        EndpointDraft.Problem.Address -> "address"
        EndpointDraft.Problem.Password -> "password"
        EndpointDraft.Problem.UserName -> "user"
    }

    fun state(state: JsonObject): String = buildJsonObject {
        put("type", "state")
        put("state", state)
    }.toString()

    /** The answer to an endpoint save: [problem] says why it was refused. */
    fun saved(request: String, problem: EndpointDraft.Problem?): String = buildJsonObject {
        put("type", "saved")
        put("request", request)
        put("ok", problem == null)
        if (problem != null) put("problem", problemKey(problem))
    }.toString()

    /** The chooser's answer: the alias picked, or null (nothing picked; the draft keeps what it had). */
    fun certificate(request: String, alias: String?): String = buildJsonObject {
        put("type", "certificate")
        put("request", request)
        put("alias", alias?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    /** How a certificate import ended (see [CertificateImport.Outcome]); after `installed` the chooser's answer follows. */
    fun certificateImport(request: String, outcome: CertificateImport.Outcome): String = buildJsonObject {
        put("type", "certificateImport")
        put("request", request)
        put("outcome", outcome.key)
    }.toString()

    /** A health sync's event ([detail] as the dashboard receives it too). */
    fun health(detail: JsonObject): String = buildJsonObject {
        put("type", "health")
        put("event", detail)
    }.toString()

    private fun JsonObject.prim(key: String): JsonPrimitive? {
        val e: JsonElement = this[key] ?: return null
        return e as? JsonPrimitive
    }

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.text(key: String, max: Int): String? = str(key)?.takeIf { it.length <= max }
    private fun JsonObject.id(key: String): String? = str(key)?.takeIf { idChars.matches(it) }
    private fun JsonObject.request(): String? = str("request")?.takeIf { requestChars.matches(it) }
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { !it.isString }?.longOrNull
}
