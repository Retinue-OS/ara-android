package os.retinue.ara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.data.Endpoint
import os.retinue.ara.shell.ShellUrls

/**
 * An endpoint as it is being edited: what the editor shows and what Save
 * turns into an [Endpoint] (see ShellViewModel.saveEndpoint). The password is
 * the one field that is not round-tripped: a stored one is never shown, a
 * typed one replaces it, an empty field keeps it unless [forgetPassword].
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
) {
    val base: String? get() = ShellUrls.normalizeBase(baseUrl)

    /** A password is in force after Save: typed now, or stored and kept. */
    val hasPassword: Boolean get() = password.isNotEmpty() || (hasStoredPassword && !forgetPassword)

    /** A usable https URL, and a user name only together with a password. */
    val canSave: Boolean get() = base != null && (username.isBlank() || hasPassword)

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

/**
 * The editor of one endpoint (plan §1): address, client certificate, sign-in
 * and the health data switch. Controlled: every change goes out through
 * [onChange] (the certificate chooser is a system dialog whose answer arrives
 * later, so the draft lives with the window, not here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndpointEditor(
    draft: EndpointDraft,
    onChange: (EndpointDraft) -> Unit,
    onSave: () -> Unit,
    onSelectCertificate: () -> Unit,
    onClearCertificate: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        draft.name ?: stringResource(if (draft.isNew) R.string.settings_endpoint_add else R.string.settings_endpoint_edit),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_close))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = draft.canSave) { Text(stringResource(R.string.settings_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { onChange(draft.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.settings_base_url)) },
                placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = draft.baseUrl.isNotBlank() && draft.base == null,
                supportingText = if (ShellUrls.isCleartextBase(draft.baseUrl)) {
                    { Text(stringResource(R.string.settings_base_url_https)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.settings_certificate), style = MaterialTheme.typography.labelLarge)
            Text(
                draft.certificateAlias ?: stringResource(R.string.settings_certificate_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelectCertificate) { Text(stringResource(R.string.settings_select_certificate)) }
                if (draft.certificateAlias != null) {
                    OutlinedButton(onClick = onClearCertificate) { Text(stringResource(R.string.settings_clear_certificate)) }
                }
            }
            HorizontalDivider()

            Text(stringResource(R.string.settings_endpoint_signin), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.settings_endpoint_signin_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = draft.username,
                onValueChange = { onChange(draft.copy(username = it)) },
                label = { Text(stringResource(R.string.auth_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { onChange(draft.copy(password = it, forgetPassword = false)) },
                label = { Text(stringResource(R.string.auth_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = draft.username.isNotBlank() && !draft.hasPassword,
                supportingText = if (draft.hasStoredPassword && !draft.forgetPassword && draft.password.isEmpty()) {
                    { Text(stringResource(R.string.settings_endpoint_password_stored)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.hasStoredPassword && !draft.forgetPassword) {
                OutlinedButton(onClick = { onChange(draft.copy(forgetPassword = true, password = "")) }) {
                    Text(stringResource(R.string.settings_endpoint_forget_password))
                }
            }
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = draft.healthUpload, onValueChange = { onChange(draft.copy(healthUpload = it)) }, role = Role.Switch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_endpoint_health), modifier = Modifier.weight(1f))
                Switch(checked = draft.healthUpload, onCheckedChange = null)
            }
            Text(stringResource(R.string.settings_endpoint_health_hint), style = MaterialTheme.typography.bodySmall)

            if (onRemove != null) {
                HorizontalDivider()
                OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.settings_endpoint_remove)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Removing an endpoint forgets its address, certificate choice and stored password on this phone: confirmed first. */
@Composable
fun EndpointRemoveDialog(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_endpoint_remove_title, label)) },
        text = { Text(stringResource(R.string.settings_endpoint_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_endpoint_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A `retinue-shell:` link for another configured endpoint: switch to it, or leave things as they are. */
@Composable
fun LinkSwitchDialog(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_switch_title, label)) },
        text = { Text(stringResource(R.string.link_switch_body, label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.link_switch_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
