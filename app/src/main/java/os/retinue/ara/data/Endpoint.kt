package os.retinue.ara.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import os.retinue.ara.shell.Origin
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.wake.WakeMode

/**
 * One Retinue server this phone can show: where it is, how the shell signs in
 * there, and whether the phone's health data may go to its letterbox. The
 * shell keeps a list of these and shows one at a time (see [ShellSettings]).
 *
 * The password is the one secret here, and it is stored sealed
 * ([CredentialVault]): [secret] is ciphertext, never the clear text. The
 * client certificate is referenced by its KeyChain alias only.
 */
@Serializable
data class Endpoint(
    val id: String,
    /** The Retinue web app's origin (and optional path), as the user typed it. */
    val baseUrl: String,
    /** KeyChain alias of the client certificate presented to this origin (mTLS). */
    val certificateAlias: String? = null,
    /** The basic-auth user name, where the edge asks for a password instead of (or besides) a certificate. */
    val username: String? = null,
    /** The password, sealed by [CredentialVault]; null when none is stored. */
    val secret: String? = null,
    /**
     * Health Connect data from this phone may be uploaded to this endpoint's
     * letterbox. Off, nothing is read for it and nothing is sent, not even on
     * request. Off for a new endpoint until the user says otherwise.
     */
    val healthUpload: Boolean = false,
    /**
     * The instance's own name, the `short_name` of its web manifest, as last
     * fetched (see EndpointNames); null until the first successful fetch. It is
     * what the menus show for this endpoint: there is no label of the shell's own.
     */
    val name: String? = null,
    /** When [name] was last fetched successfully (epoch ms; 0 for never). */
    val nameFetchedAt: Long = 0L,
    /**
     * While another endpoint is shown, this one keeps working for the phone:
     * its health data goes on being synced (where [healthUpload] is on) and
     * its wake phrase is still listened for. Off, it rests until it is shown
     * again (see [InstanceState]). Off for a new endpoint.
     */
    val background: Boolean = false,
    /**
     * The wake phrase of this instance on this phone: mode, threshold and
     * phrase model. Null in a list an older build wrote: the one device-wide
     * wake setting of those builds stands in (see EndpointCodec.withLegacyWake),
     * and the next write stores it here.
     */
    val wake: EndpointWake? = null,
) {
    /** The normalized base URL, or null when [baseUrl] is not a usable https URL. */
    val base: String? get() = ShellUrls.normalizeBase(baseUrl)

    val origin: Origin? get() = ShellUrls.origin(base)

    /** A password is stored for [username]. */
    val hasCredentials: Boolean get() = !username.isNullOrBlank() && !secret.isNullOrEmpty()

    /** The host (and port, when not the default) for display, or the raw URL when it does not parse. */
    val hostLabel: String
        get() = origin?.toString()?.removePrefix("https://")?.removePrefix("http://") ?: baseUrl.trim()

    /** The instance name when known and not blank. */
    val knownName: String? get() = name?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * How the wake phrase works for one instance on this phone: when it is
 * listened for, how sure the detector must be, and which phrase model wakes
 * this instance (an id of the wake model library; null for none chosen).
 * Different instances may listen for different phrases at once; a phrase
 * heard brings its instance to the front.
 */
@Serializable
data class EndpointWake(
    /** [WakeMode.key]. */
    val mode: String = WakeMode.Off.key,
    /** Score at or above which the phrase counts as heard (openWakeWord's default 0.5). */
    val threshold: Float = DEFAULT_THRESHOLD,
    /**
     * The phrase model listened for, by [os.retinue.ara.wake.WakePhraseModel.id]
     * (null: none chosen). The models themselves are the phone's library
     * ([os.retinue.ara.wake.WakeModelFiles.library]); an id that is not in
     * it, or whose model is not downloaded, counts as none.
     */
    val modelId: String? = null,
) {
    val wakeMode: WakeMode get() = WakeMode.fromKey(mode)

    /**
     * This wake with the model [id], unless it has one already: how the one
     * phrase file of earlier builds, moved into the library as [id], stays
     * the one listened for (see [EndpointCodec.adoptingWakeModel]).
     */
    fun adopting(id: String): EndpointWake = if (modelId == null) copy(modelId = id) else this

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val MIN_THRESHOLD = 0.05f
        const val MAX_THRESHOLD = 0.99f

        val OFF = EndpointWake()

        fun of(mode: WakeMode, threshold: Float, modelId: String? = null) =
            EndpointWake(mode.key, threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD), modelId)
    }
}

/**
 * What an endpoint is to the phone right now. [Foreground]: the one shown
 * (exactly one, when any is set up). [Background]: not shown, but its health
 * data is synced and its wake phrase listened for ([Endpoint.background]).
 * [Disabled]: not shown, and nothing runs for it.
 */
enum class InstanceState(val key: String) {
    Foreground("foreground"),
    Background("background"),
    Disabled("disabled"),
}

/** The endpoints and which of them the shell shows. */
data class EndpointSet(val endpoints: List<Endpoint>, val currentId: String?) {
    /** The endpoint the shell shows: the one chosen, or, should that be gone, the first. */
    val current: Endpoint? get() = endpoints.firstOrNull { it.id == currentId } ?: endpoints.firstOrNull()

    /** Add or replace [endpoint]; the first endpoint ever becomes the current one. */
    fun upsert(endpoint: Endpoint): EndpointSet {
        val replaced = endpoints.any { it.id == endpoint.id }
        val list = if (replaced) endpoints.map { if (it.id == endpoint.id) endpoint else it } else endpoints + endpoint
        return EndpointSet(list, currentId ?: endpoint.id)
    }

    /** Remove the endpoint [id]; when it was the current one, the first that is left takes over. */
    fun remove(id: String): EndpointSet {
        val list = endpoints.filter { it.id != id }
        val current = if (currentId == id) list.firstOrNull()?.id else currentId
        return EndpointSet(list, current)
    }

    /** Show the endpoint [id]; an unknown id changes nothing. */
    fun select(id: String): EndpointSet =
        if (endpoints.any { it.id == id }) EndpointSet(endpoints, id) else this

    /** Apply [change] to the endpoint [id], if there is one. */
    fun update(id: String, change: (Endpoint) -> Endpoint): EndpointSet =
        EndpointSet(endpoints.map { if (it.id == id) change(it) else it }, currentId)

    /** What [endpoint] is to the phone now (see [InstanceState]). */
    fun stateOf(endpoint: Endpoint): InstanceState = when {
        endpoint.id == current?.id -> InstanceState.Foreground
        endpoint.background -> InstanceState.Background
        else -> InstanceState.Disabled
    }

    /** The endpoints that work for the phone while not shown, in list order. */
    val backgroundEndpoints: List<Endpoint>
        get() = endpoints.filter { stateOf(it) == InstanceState.Background }

    /**
     * The endpoints besides the one shown whose phrase model is [modelId],
     * whatever their mode or state, in list order. While there are any, the
     * model is not deleted from the card of the endpoint shown.
     */
    fun otherUsersOfWakeModel(modelId: String): List<Endpoint> {
        val shown = current?.id
        return endpoints.filter { it.id != shown && it.wake?.modelId == modelId }
    }

    /**
     * The phrase model [modelId] is to leave the library: the endpoint shown
     * lets go of it. Null when another endpoint uses it too
     * ([otherUsersOfWakeModel]): it stays then, so that no endpoint is left
     * with a model that is gone.
     */
    fun releasingWakeModel(modelId: String): EndpointSet? {
        if (otherUsersOfWakeModel(modelId).isNotEmpty()) return null
        val shown = current ?: return this
        return update(shown.id) { e ->
            val wake = e.wake
            if (wake != null && wake.modelId == modelId) e.copy(wake = wake.copy(modelId = null)) else e
        }
    }

    companion object {
        val EMPTY = EndpointSet(emptyList(), null)
    }
}

/**
 * What the shell shows for an endpoint in its menus, native and on the web
 * app's Settings page: the instance's own name, never a label of the shell's.
 * While the name is unknown (never fetched, or unreachable so far) the host
 * stands in; when two endpoints carry the same name — two unnamed instances
 * both say "Retinue" — the host is appended to tell them apart.
 */
object EndpointLabels {

    fun label(endpoint: Endpoint, all: List<Endpoint>): String {
        val name = endpoint.knownName ?: return endpoint.hostLabel
        val shared = all.count { it.knownName == name } > 1
        return if (shared) "$name (${endpoint.hostLabel})" else name
    }

    /** Every endpoint's label, by id. */
    fun labels(all: List<Endpoint>): Map<String, String> = all.associate { it.id to label(it, all) }
}

/**
 * The endpoints as stored (a JSON document in the preferences), and the
 * reading of the one base URL and certificate alias older builds stored, so
 * an update carries the configured server over as the first endpoint.
 */
object EndpointCodec {

    const val VERSION = 1

    /** The id the one endpoint of an older build gets: fixed, so every read of the legacy keys agrees. */
    const val LEGACY_ID = "endpoint-1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(endpoints: List<Endpoint>): String = JsonObject(
        mapOf(
            "version" to JsonPrimitive(VERSION),
            "endpoints" to JsonArray(endpoints.map { json.encodeToJsonElement(Endpoint.serializer(), it) }),
        ),
    ).toString()

    /**
     * The endpoints of a stored document; null for no document (never written,
     * so the legacy keys decide) and an empty list for an unreadable one.
     * Entries that do not decode are dropped; ids are unique (the first wins).
     */
    fun decode(raw: String?): List<Endpoint>? {
        if (raw == null) return null
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val array = root["endpoints"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                json.decodeFromJsonElement(Endpoint.serializer(), element).takeIf { it.id.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.id }
    }

    /**
     * [endpoints] with the wake setting of builds before per-instance wake
     * phrases ([legacy]: the one device-wide mode and threshold, and the one
     * phrase model chosen for the phone where a build had a library of them)
     * filled in where an endpoint has none yet. Every endpoint gets it: the
     * phrase woke whichever endpoint was shown, so each keeps doing so when
     * shown. Only the shown one listens until another is set to work in the
     * background, so nothing changes for the user.
     */
    fun withLegacyWake(endpoints: List<Endpoint>, legacy: EndpointWake): List<Endpoint> =
        if (endpoints.all { it.wake != null }) endpoints else endpoints.map { if (it.wake == null) it.copy(wake = legacy) else it }

    /**
     * [endpoints], the list as stored, once the one phrase file of earlier
     * builds has joined the library as [modelId]: an endpoint with a wake of
     * its own and no model gets it. Such an endpoint is of a build that had
     * per-instance wake phrases and still the one phrase file, which every
     * endpoint listened with. An endpoint without a wake of its own is left
     * as it is: it reads the device-wide choice ([withLegacyWake]).
     *
     * Only for a start that still finds the earlier file: that is what tells
     * such an endpoint from one set to no model later on.
     */
    fun adoptingWakeModel(endpoints: List<Endpoint>, modelId: String): List<Endpoint> =
        endpoints.map { e -> e.wake?.let { e.copy(wake = it.adopting(modelId)) } ?: e }

    /**
     * The endpoint an older build's settings describe: its base URL and
     * certificate alias, with health upload on as it was for that one server
     * (the phone synced to it until now). None without a base URL.
     */
    fun legacy(baseUrl: String?, certificateAlias: String?): List<Endpoint> {
        val url = baseUrl?.trim().orEmpty()
        if (url.isEmpty()) return emptyList()
        return listOf(Endpoint(LEGACY_ID, url, certificateAlias = certificateAlias, healthUpload = true))
    }
}
