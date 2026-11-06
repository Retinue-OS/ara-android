package os.retinue.ara.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import os.retinue.ara.keyboard.KeyLayouts
import os.retinue.ara.keyboard.KeyboardStatus
import os.retinue.ara.data.DeviceWebSettings
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.ShellSettings

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

data class WakeInfo(
    /** [os.retinue.ara.wake.WakeMode.key]. */
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
    val phrase: Boolean,
    val modelUrl: String,
    val busy: Boolean,
    val message: String?,
)

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
    val wake: WakeInfo,
    val updates: UpdatesInfo,
    /** The dashboard's per-device preferences (DeviceWebSettings). */
    val device: Map<String, String>,
    /** Ara has usage access, so the tray's order counts apps however they were opened. */
    val usageAccess: Boolean = false,
    /** The keyboard for other devices: its card shows whether it is on and the device it types into. */
    val keyboard: KeyboardStatus = KeyboardStatus(),
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
                put("phrase", wake.phrase)
                put("modelUrl", wake.modelUrl)
                put("busy", wake.busy)
                put("message", wake.message.json())
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
        put(
            "keyboard",
            buildJsonObject {
                put("available", keyboard.available)
                put("enabled", keyboard.enabled)
                put("connected", keyboard.connected)
                put("host", keyboard.host?.name.json())
                put("layout", KeyLayouts.byId(keyboard.layout)?.label ?: keyboard.layout)
            },
        )
    }

    private fun String?.json() = this?.let { JsonPrimitive(it) } ?: JsonNull

    companion object {
        /** The page's list of endpoints, from the settings. */
        fun endpointRows(settings: ShellSettings): List<EndpointRow> {
            val labels = EndpointLabels.labels(settings.endpoints)
            val current = settings.current?.id
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
    }
}
