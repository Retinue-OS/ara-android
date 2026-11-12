package os.retinue.ara.ui

import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.shell.ShellUrls

/**
 * An endpoint as the settings page's editor sends it for saving (see
 * SettingsMessages.toEndpointDraft), checked here before ShellViewModel.saveEndpoint
 * stores it as an [Endpoint] ([toEndpoint]): the page decides what closing
 * its editor does, the app what it stores. The password is the one field
 * that is not round-tripped: a stored one never goes to the page, a typed
 * one replaces it, an empty field keeps it unless [forgetPassword].
 *
 * Framework-free and unit-tested.
 */
data class EndpointDraft(
    val id: String,
    /** No endpoint with this id is stored (the app's answer, not the page's). */
    val isNew: Boolean,
    val baseUrl: String,
    val certificateAlias: String?,
    val username: String,
    /** A password typed now; "" leaves the stored one (if any) as it is. */
    val password: String,
    /** A password is stored for this endpoint. */
    val hasStoredPassword: Boolean,
    /** Drop the stored password on save. */
    val forgetPassword: Boolean,
    val healthUpload: Boolean,
    /** The instance's name as last fetched; null while unknown. */
    val name: String?,
    /** While another endpoint is shown, this one keeps working in the background (Endpoint.background). */
    val background: Boolean = false,
) {
    /** Why a draft cannot be saved as it is. */
    enum class Problem {
        /** No address, or one that is not an https URL. */
        Address,

        /** A user name without a password. */
        Password,

        /** A password typed without a user name (saving would drop it). */
        UserName,
    }

    val base: String? get() = ShellUrls.normalizeBase(baseUrl)

    /** A password is in force after saving: typed now, or stored and kept. */
    val hasPassword: Boolean get() = password.isNotEmpty() || (hasStoredPassword && !forgetPassword)

    /** Why the draft cannot be saved, or null when it can. */
    val problem: Problem?
        get() = when {
            base == null -> Problem.Address
            username.isNotBlank() && !hasPassword -> Problem.Password
            username.isBlank() && password.isNotEmpty() -> Problem.UserName
            else -> null
        }

    /** A usable https URL, and a user name and a password only together. */
    val canSave: Boolean get() = problem == null

    /** The user name as it is stored: trimmed, null for none. */
    val user: String? get() = username.trim().takeIf { it.isNotEmpty() }

    /**
     * The endpoint this draft is stored as: [existing], the one stored under
     * its id (null for a new endpoint), with the editor's fields replaced.
     * [sealed] is the password typed now, sealed (CredentialVault); without
     * one the stored password stays, unless the draft forgets it or has no
     * user name. A changed address drops the fetched name, the other
     * server's. What else the editor does not hold stays the endpoint's as
     * it is stored, each set on its own card: its call switch and its
     * switch for typing on other devices (both off for a new endpoint) and
     * its wake phrase (off, without a phrase model, for a new endpoint).
     */
    fun toEndpoint(existing: Endpoint?, sealed: String?): Endpoint {
        val sameAddress = existing != null && existing.base == base
        // A copy, so that a save cannot reset what the editor does not hold. A new endpoint's wake
        // is its own from the start: stored without one, it would read the one device-wide setting
        // of earlier builds (EndpointCodec.withLegacyWake) on a phone that still holds it.
        return (existing ?: Endpoint(id, baseUrl, wake = EndpointWake.OFF)).copy(
            baseUrl = baseUrl.trim(),
            certificateAlias = certificateAlias,
            username = user,
            secret = when {
                user == null -> null
                sealed != null -> sealed
                forgetPassword -> null
                else -> existing?.secret
            },
            healthUpload = healthUpload,
            name = if (sameAddress) existing?.name else null,
            nameFetchedAt = if (sameAddress) existing?.nameFetchedAt ?: 0L else 0L,
            background = background,
        )
    }
}
