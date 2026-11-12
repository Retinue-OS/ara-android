package os.retinue.ara.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import os.retinue.ara.shell.Origin
import os.retinue.ara.shell.ShellUrls

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

/** The endpoints and which of them the shell shows. */
data class EndpointSet(val endpoints: List<Endpoint>, val currentId: String?) {
    /** The endpoint the shell shows: the one chosen, or, should that be gone, the first. */
    val current: Endpoint? get() = endpoints.firstOrNull { it.id == currentId } ?: endpoints.firstOrNull()

    /**
     * Add or replace [endpoint]. The endpoint shown stays the one shown, also
     * where it was never chosen: the one carried over from an older build is
     * shown without a stored choice. The endpoint added becomes the current
     * one only where nothing can be shown so far: it is the first ever, or
     * the carried-over one has an address the shell cannot use.
     */
    fun upsert(endpoint: Endpoint): EndpointSet {
        val replaced = endpoints.any { it.id == endpoint.id }
        val list = if (replaced) endpoints.map { if (it.id == endpoint.id) endpoint else it } else endpoints + endpoint
        val shown = current?.takeIf { it.base != null }
        return EndpointSet(list, currentId ?: (shown ?: endpoint).id)
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
