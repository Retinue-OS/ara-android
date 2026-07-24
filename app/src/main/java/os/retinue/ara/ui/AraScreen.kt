package os.retinue.ara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.data.AraSettings
import os.retinue.ara.state.AraState
import os.retinue.ara.state.PrivacyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AraScreen(
    ui: AraUi,
    settings: AraSettings,
    onPrimaryAction: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onAcceptCloudDisclosure: () -> Unit,
    onDeclineCloudDisclosure: () -> Unit,
    onCapturedSend: () -> Unit,
    onCapturedDiscard: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onSaveSettings: (AraSettings) -> Unit,
    onSelectCertificate: () -> Unit,
    onClearCertificate: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            settings = settings,
            certificateAlias = ui.prefs.selectedCertificateAlias,
            onSave = onSaveSettings,
            onSelectCertificate = onSelectCertificate,
            onClearCertificate = onClearCertificate,
            onClose = { showSettings = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings_open))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TogglesRow(ui, onToggleMic, onToggleSpeaker)
            StatusCard(ui)
            PromptCard(ui)
            AnswerCard(ui)
            ui.error?.let { ErrorCard(it, onDismissError, onRetry) }
            PrimaryAction(ui, onPrimaryAction)
        }
    }

    if (ui.showCloudDisclosure) {
        CloudDisclosureDialog(onAcceptCloudDisclosure, onDeclineCloudDisclosure)
    }
    if (ui.showCapturedDecision) {
        CapturedInputDialog(onCapturedSend, onCapturedDiscard)
    }
}

@Composable
private fun TogglesRow(ui: AraUi, onToggleMic: () -> Unit, onToggleSpeaker: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToggleColumn(
            checked = ui.prefs.continuousConversation,
            onToggle = onToggleMic,
            onIcon = Icons.Filled.Mic,
            offIcon = Icons.Filled.MicOff,
            label = stringResource(R.string.mic_toggle_label),
            description = stringResource(
                if (ui.prefs.continuousConversation) R.string.mic_toggle_on_desc
                else R.string.mic_toggle_off_desc,
            ),
        )
        ToggleColumn(
            checked = ui.prefs.automaticPlayback,
            onToggle = onToggleSpeaker,
            onIcon = Icons.Filled.VolumeUp,
            offIcon = Icons.Filled.VolumeOff,
            label = stringResource(R.string.speaker_toggle_label),
            description = stringResource(
                if (ui.prefs.automaticPlayback) R.string.speaker_toggle_on_desc
                else R.string.speaker_toggle_off_desc,
            ),
        )
    }
}

@Composable
private fun ToggleColumn(
    checked: Boolean,
    onToggle: () -> Unit,
    onIcon: androidx.compose.ui.graphics.vector.ImageVector,
    offIcon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(if (checked) onIcon else offIcon, contentDescription = null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusCard(ui: AraUi) {
    val stateLabel = stringResource(stateLabelRes(ui.state))
    val privacyLabel = stringResource(privacyRes(ui.privacy))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.label_status), style = MaterialTheme.typography.labelMedium)
            // Announce state changes to accessibility services (not icon-only).
            Text(
                stateLabel,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (ui.state == AraState.Submitting ||
                ui.state == AraState.WaitingForResponseForeground ||
                ui.state == AraState.WaitingForResponseBackground
            ) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(8.dp))
            Text("${stringResource(R.string.label_privacy)}: $privacyLabel",
                style = MaterialTheme.typography.bodySmall)
            ui.status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PromptCard(ui: AraUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.label_prompt), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val fallback = stringResource(R.string.no_prompt_yet)
            val text = ui.prompt.ifBlank { fallback }
            Text(if (ui.partial && ui.prompt.isNotBlank()) "$text…" else text,
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AnswerCard(ui: AraUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.label_answer), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val fallback = stringResource(R.string.no_answer_yet)
            val text = ui.answer?.takeIf { it.isNotBlank() } ?: fallback
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorCard(error: UiError, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.state_error),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(error.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!error.terminal) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss_error))
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(ui: AraUi, onClick: () -> Unit) {
    val isSend = ui.state == AraState.Listening || ui.state == AraState.Recognizing
    val isStart = ui.state == AraState.Inactive || ui.state == AraState.Error
    val enabled = isSend || isStart
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (isSend) {
            Icon(Icons.Filled.Send, contentDescription = null)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            stringResource(if (isSend) R.string.action_send else R.string.action_start_listening),
        )
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun CloudDisclosureDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.cloud_disclosure_title)) },
        text = { Text(stringResource(R.string.cloud_disclosure_body)) },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.cloud_disclosure_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.cloud_disclosure_decline)) }
        },
    )
}

@Composable
private fun CapturedInputDialog(onSend: () -> Unit, onDiscard: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        // Modal decision: no dismiss-by-outside-tap; the user must choose.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.captured_title)) },
        text = { Text(stringResource(R.string.captured_body)) },
        confirmButton = {
            Button(onClick = onSend) { Text(stringResource(R.string.action_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
        },
    )
}

private fun stateLabelRes(state: AraState): Int = when (state) {
    AraState.Initializing -> R.string.state_initializing
    AraState.Inactive -> R.string.state_inactive
    AraState.Listening -> R.string.state_listening
    AraState.Recognizing -> R.string.state_recognizing
    AraState.CapturedInputDecision -> R.string.state_captured_decision
    AraState.Submitting -> R.string.state_submitting
    AraState.WaitingForResponseForeground -> R.string.state_waiting
    AraState.WaitingForResponseBackground -> R.string.state_waiting
    AraState.AnswerPendingPresentation -> R.string.state_answer_pending
    AraState.Speaking -> R.string.state_speaking
    AraState.Error -> R.string.state_error
}

private fun privacyRes(p: PrivacyState): Int = when (p) {
    PrivacyState.OnDevice -> R.string.privacy_on_device
    PrivacyState.Cloud -> R.string.privacy_cloud
    PrivacyState.Unknown -> R.string.privacy_unknown
}
