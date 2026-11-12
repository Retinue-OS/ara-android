package os.retinue.ara.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import os.retinue.ara.data.DeviceWebSettings
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.InstanceState
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.wake.WakeModelEntry

/** One endpoint in the page's list. */
data class EndpointRow(
    val id: String,
    /** What the shell shows for it: the instance's own name (EndpointLabels). */
    val label: String,
    val baseUrl: String,
    val host: String,
    val certificateAlias: String?,
    val username: String?,
    /** A password is stored (never the password). */
    val hasPassword: Boolean,
    val healthUpload: Boolean,
    val current: Boolean,
    /** The instance's name as last fetched, for the editor's title. */
    val name: String?,
    /** It keeps working while another is shown (Endpoint.background). */
    val background: Boolean = false,
    /** [InstanceState.key]: foreground (shown), background or disabled. */
    val state: String = InstanceState.Disabled.key,
)

/** The endpoint shown, and where its own parts of the page come from. */
data class CurrentEndpoint(
    val id: String,
    val label: String,
    /** `https://host[:port]`: the only origin the page trusts a frame message from. */
    val origin: String,
    /** The instance's section, embedded as the page's last part; null when its origin cannot be framed (AppPage.frameSource). */
    val instanceUrl: String?,
    /** The dashboard's own Settings page, for when the section cannot be shown. */
    val settingsUrl: String,
    /** Changes whenever what signs the section in changes, so the page loads it afresh ([SettingsPageState.frameKey]). */
    val frameKey: String,
)

/** Why the dashboard did not load, for the banner at the top. */
data class FailureInfo(val reason: String, val url: String?, val consoleErrors: List<String>)

/**
 * Direct calls by voice (`RetinueShell.placeCall`, see shell/CallLogic): the
 * current endpoint's switch, and what the phone allows whichever endpoint
 * is shown.
 */
data class CallsInfo(
    /** The current endpoint's switch: a call its page asks for in a voice conversation is placed without a tap. */
    val direct: Boolean,
    /** Android lets Ara place calls (`CALL_PHONE`); without it calls open the dialer. */
    val permissionGranted: Boolean,
    /** The phone can make calls at all. */
    val telephony: Boolean,
)

/** The wake phrase card: the instance shown, its phrase on this phone, and what the phone needs for it. */
data class WakeInfo(
    /** [os.retinue.ara.wake.WakeMode.key], of the instance shown. */
    val mode: String,
    val threshold: Float,
    val status: String,
    val micGranted: Boolean,
    val modelsReady: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val overlayAllowed: Boolean,
    val melspectrogram: Boolean,
    val embedding: Boolean,
    /** The phrase models of the list, in its order. */
    val models: List<WakeModelRow>,
    val busy: Boolean,
    /** The model a job works on, while [busy]. */
    val busyId: String?,
    val message: String?,
    /** The other instances whose phrase is listened for now (labels): they work in the background. */
    val alsoListening: List<String> = emptyList(),
)

/** One phrase model of the wake phrase's list. */
data class WakeModelRow(
    val id: String,
    val name: String,
    /** [os.retinue.ara.wake.WakeModelSource.key]. */
    val source: String,
    /** Where it downloads from (catalog and URL models). */
    val url: String?,
    val downloaded: Boolean,
    /** The model listened for. */
    val active: Boolean,
    /** The user's own: taken off the list by removing it. */
    val removable: Boolean,
)

/** The list's rows; [activeId] is the model listened for (its row is active only while downloaded). */
fun wakeModelRows(library: List<WakeModelEntry>, activeId: String?): List<WakeModelRow> = library.map { e ->
    WakeModelRow(
        id = e.model.id,
        name = e.model.name,
        source = e.model.source.key,
        url = e.model.url,
        downloaded = e.downloaded,
        active = e.downloaded && e.model.id == activeId,
        removable = e.model.removable,
    )
}

data class UpdatesInfo(
    val installed: String,
    val auto: Boolean,
    /** [os.retinue.ara.update.UpdateChannel.key]. */
    val channel: String,
    val status: String,
    val busy: Boolean,
    /** 0..1 while a build downloads, else null. */
    val progress: Float?,
    /** A build ready to install now. */
    val installTag: String?,
    val awaitingConfirmation: Boolean,
    val canCheck: Boolean,
    val installAllowed: Boolean,
)

/**
 * Everything the settings page shows, sent whole whenever any of it changes
 * (SettingsBridge drops a state equal to the last). Texts that depend on the
 * phone's language and the moment (statuses, "3 hours ago") come built; the
 * page's own labels are its own. Framework-free and unit-tested.
 */
data class SettingsPageState(
    val version: String,
    val endpoints: List<EndpointRow>,
    val current: CurrentEndpoint?,
    val failure: FailureInfo?,
    /** HealthSyncStatus.toJson() of the current endpoint (its `enabled` is the endpoint's switch). */
    val health: JsonObject,
    val isDefaultHome: Boolean,
    val splitLaunch: Boolean,
    val keepSplitInRecents: Boolean,
    /** Direct calls for the current endpoint ([callsInfo]). */
    val calls: CallsInfo,
    val wake: WakeInfo,
    val updates: UpdatesInfo,
    /** The dashboard's per-device preferences (DeviceWebSettings). */
    val device: Map<String, String>,
    /** Ara has usage access, so the tray's order counts apps however they were opened. */
    val usageAccess: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put(
            "endpoints",
            buildJsonArray {
                endpoints.forEach { e ->
                    add(
                        buildJsonObject {
                            put("id", e.id)
                            put("label", e.label)
                            put("baseUrl", e.baseUrl)
                            put("host", e.host)
                            put("certificateAlias", e.certificateAlias.json())
                            put("username", e.username.json())
                            put("hasPassword", e.hasPassword)
                            put("healthUpload", e.healthUpload)
                            put("current", e.current)
                            put("name", e.name.json())
                            put("background", e.background)
                            put("state", e.state)
                        },
                    )
                }
            },
        )
        put(
            "current",
            current?.let { c ->
                buildJsonObject {
                    put("id", c.id)
                    put("label", c.label)
                    put("origin", c.origin)
                    put("instanceUrl", c.instanceUrl.json())
                    put("settingsUrl", c.settingsUrl)
                    put("frameKey", c.frameKey)
                }
            } ?: JsonNull,
        )
        put(
            "failure",
            failure?.let { f ->
                buildJsonObject {
                    put("reason", f.reason)
                    put("url", f.url.json())
                    put("consoleErrors", buildJsonArray { f.consoleErrors.forEach { add(JsonPrimitive(it)) } })
                }
            } ?: JsonNull,
        )
        put("health", health)
        put("home", buildJsonObject { put("isDefault", isDefaultHome) })
        put(
            "split",
            buildJsonObject {
                put("launch", splitLaunch)
                put("recents", keepSplitInRecents)
            },
        )
        put(
            "calls",
            buildJsonObject {
                put("direct", calls.direct)
                put("permissionGranted", calls.permissionGranted)
                put("telephony", calls.telephony)
            },
        )
        put(
            "wake",
            buildJsonObject {
                put("mode", wake.mode)
                put("threshold", wake.threshold)
                put("status", wake.status)
                put("micGranted", wake.micGranted)
                put("modelsReady", wake.modelsReady)
                put("notificationsAllowed", wake.notificationsAllowed)
                put("fullScreenAllowed", wake.fullScreenAllowed)
                put("overlayAllowed", wake.overlayAllowed)
                put("melspectrogram", wake.melspectrogram)
                put("embedding", wake.embedding)
                put(
                    "models",
                    buildJsonArray {
                        wake.models.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("id", m.id)
                                    put("name", m.name)
                                    put("source", m.source)
                                    put("url", m.url.json())
                                    put("downloaded", m.downloaded)
                                    put("active", m.active)
                                    put("removable", m.removable)
                                },
                            )
                        }
                    },
                )
                put("busy", wake.busy)
                put("busyId", wake.busyId.json())
                put("message", wake.message.json())
                put("alsoListening", buildJsonArray { wake.alsoListening.forEach { add(JsonPrimitive(it)) } })
            },
        )
        put(
            "updates",
            buildJsonObject {
                put("installed", updates.installed)
                put("auto", updates.auto)
                put("channel", updates.channel)
                put("status", updates.status)
                put("busy", updates.busy)
                put("progress", updates.progress?.let { JsonPrimitive(it) } ?: JsonNull)
                put("installTag", updates.installTag.json())
                put("awaitingConfirmation", updates.awaitingConfirmation)
                put("canCheck", updates.canCheck)
                put("installAllowed", updates.installAllowed)
            },
        )
        put("device", DeviceWebSettings.toJson(device))
        put("launcher", buildJsonObject { put("usageAccess", usageAccess) })
    }

    private fun String?.json() = this?.let { JsonPrimitive(it) } ?: JsonNull

    companion object {
        /** The page's list of endpoints, from the settings. */
        fun endpointRows(settings: ShellSettings): List<EndpointRow> {
            val labels = EndpointLabels.labels(settings.endpoints)
            val current = settings.current?.id
            val set = settings.endpointSet
            return settings.endpoints.map { e ->
                EndpointRow(
                    id = e.id,
                    label = labels[e.id] ?: e.hostLabel,
                    baseUrl = e.baseUrl,
                    host = e.hostLabel,
                    certificateAlias = e.certificateAlias,
                    username = e.username?.takeIf { it.isNotBlank() },
                    hasPassword = e.hasCredentials,
                    healthUpload = e.healthUpload,
                    current = e.id == current,
                    name = e.knownName,
                    background = e.background,
                    state = set.stateOf(e).key,
                )
            }
        }

        /** The endpoint shown, or null when none is set up (or its address is unusable). */
        fun currentEndpoint(settings: ShellSettings): CurrentEndpoint? {
            val e = settings.current ?: return null
            val base = e.base ?: return null
            val origin = e.origin?.toString() ?: return null
            return CurrentEndpoint(
                id = e.id,
                label = EndpointLabels.label(e, settings.endpoints),
                origin = origin,
                instanceUrl = if (AppPage.frameSource(origin) != null) "$base/settings-instance.html" else null,
                settingsUrl = "$base/settings.html",
                frameKey = frameKey(e),
            )
        }

        /**
         * Changes whenever what signs the instance section in changes: the
         * endpoint shown, its address, its certificate or its sign-in (the
         * sealed password counts, and each save seals afresh, so a new
         * password is a change). A checksum: nothing of the password can be
         * read from it.
         */
        fun frameKey(e: Endpoint): String =
            listOf(e.id, e.base, e.certificateAlias, e.username, e.secret).hashCode().toUInt().toString(16)

        /**
         * The phone calls card: the switch of the endpoint shown, its own and
         * no other's (off with none set up), beside what holds for the phone.
         */
        fun callsInfo(settings: ShellSettings, permissionGranted: Boolean, telephony: Boolean) = CallsInfo(
            direct = settings.current?.directCalls == true,
            permissionGranted = permissionGranted,
            telephony = telephony,
        )
    }
}
