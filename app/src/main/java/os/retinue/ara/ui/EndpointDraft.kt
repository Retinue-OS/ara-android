package os.retinue.ara.ui

import os.retinue.ara.data.Endpoint
import os.retinue.ara.shell.ShellUrls

/**
 * An endpoint as it is being edited: what the editor shows and what Save
 * turns into an [Endpoint] (see ShellViewModel.saveEndpoint). The password is
 * the one field that is not round-tripped: a stored one is never shown, a
 * typed one replaces it, an empty field keeps it unless [forgetPassword].
 *
 * Framework-free, so what closing the editor does ([onClose]) is unit-tested.
 */
data class EndpointDraft(
    val id: String,
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
    /** The instance's name as last fetched, for the title; null while unknown. */
    val name: String?,
    /** The fields as the editor opened with them, to tell whether anything was changed; [copy] keeps them. */
    val opened: EndpointDraft.Fields = EndpointDraft.Fields.of(baseUrl, certificateAlias, username, healthUpload),
) {
    /** The fields that tell a changed draft from the one the editor opened with (the password is told apart by itself). */
    data class Fields(val baseUrl: String, val certificateAlias: String?, val username: String, val healthUpload: Boolean) {
        companion object {
            /** Spaces around the address and the user name are not a change: Save trims them. */
            fun of(baseUrl: String, certificateAlias: String?, username: String, healthUpload: Boolean) =
                Fields(baseUrl.trim(), certificateAlias, username.trim(), healthUpload)
        }
    }

    /** Why a draft cannot be saved as it is. */
    enum class Problem {
        /** No address, or one that is not an https URL. */
        Address,

        /** A user name without a password. */
        Password,

        /** A password typed without a user name (Save would drop it). */
        UserName,
    }

    /** What closing the editor (Back, or the arrow in its bar) does with the draft. */
    sealed class Close {
        /** Nothing was changed: the editor just closes. */
        data object Unchanged : Close()

        /** Changes that can be saved: they are saved, and the editor closes. */
        data object Save : Close()

        /** Changes that cannot be saved: the user is asked whether to drop them or to go on editing. */
        data class Ask(val problem: Problem) : Close()
    }

    val base: String? get() = ShellUrls.normalizeBase(baseUrl)

    /** A password is in force after Save: typed now, or stored and kept. */
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

    /** Anything differs from what the editor opened with: a field, a typed password, or a stored one to forget. */
    val changed: Boolean
        get() = password.isNotEmpty() || forgetPassword || Fields.of(baseUrl, certificateAlias, username, healthUpload) != opened

    /**
     * What closing the editor does: changes are saved on the way out, as
     * Android editors do, and only changes that cannot be saved are asked
     * about. Nothing is ever dropped without the user's word.
     */
    fun onClose(): Close {
        if (!changed) return Close.Unchanged
        val p = problem ?: return Close.Save
        return Close.Ask(p)
    }

    companion object {
        fun forNew(id: String) = EndpointDraft(
            id = id, isNew = true, baseUrl = "", certificateAlias = null, username = "", password = "",
            hasStoredPassword = false, forgetPassword = false, healthUpload = false, name = null,
        )

        fun of(endpoint: Endpoint) = EndpointDraft(
            id = endpoint.id,
            isNew = false,
            baseUrl = endpoint.baseUrl,
            certificateAlias = endpoint.certificateAlias,
            username = endpoint.username.orEmpty(),
            password = "",
            hasStoredPassword = endpoint.hasCredentials,
            forgetPassword = false,
            healthUpload = endpoint.healthUpload,
            name = endpoint.knownName,
        )
    }
}
