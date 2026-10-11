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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.retinue.ara.BuildConfig
import os.retinue.ara.R
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakeStatus

/** Everything the settings screen shows. */
data class SettingsState(
    val settings: ShellSettings,
    val launcher: LauncherConfig,
    val isDefaultHome: Boolean,
    val models: WakeModelStatus,
    val modelBusy: Boolean,
    val modelMessage: String?,
    val wakeStatus: WakeStatus,
    val micGranted: Boolean,
    /** Android 14+: the wake alert may open the shell full-screen (see WakeAlert). */
    val fullScreenIntentAllowed: Boolean,
    /** The wake alert can be posted at all: permission, app notifications and its channel on. */
    val notificationsAllowed: Boolean,
)

/** What the settings screen can ask for. */
interface SettingsActions {
    fun close()
    fun saveBaseUrl(url: String)
    fun selectCertificate()
    fun clearCertificate()
    fun requestHomeRole()
    fun addEntry(template: LauncherEntry)
    fun editEntry(entry: LauncherEntry)
    fun removeEntry(entry: LauncherEntry)
    fun moveEntry(entry: LauncherEntry, delta: Int)
    fun resetLauncher()
    fun setWakeMode(mode: WakeMode)
    fun setWakeThreshold(value: Float)
    fun downloadFeatureModels()
    fun downloadPhraseModel(url: String)
    fun pickPhraseModel()
    fun allowFullScreenIntent()
    fun openNotificationSettings()
}

/**
 * The shell's native settings (plan §1): base URL, client certificate, home
 * role, launcher editor, wake phrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_close))
                    }
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
            RetinueSection(state, actions)
            HorizontalDivider()
            HomeSection(state, actions)
            HorizontalDivider()
            LauncherSection(state, actions)
            HorizontalDivider()
            WakeSection(state, actions)
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun RetinueSection(state: SettingsState, actions: SettingsActions) {
    var baseUrl by remember(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    SectionTitle(R.string.settings_section_retinue)
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text(stringResource(R.string.settings_base_url)) },
        placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = baseUrl.isNotBlank() && ShellSettings(baseUrl = baseUrl).base == null,
        supportingText = if (ShellUrls.isCleartextBase(baseUrl)) {
            { Text(stringResource(R.string.settings_base_url_https)) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { actions.saveBaseUrl(baseUrl) },
        enabled = baseUrl.trim() != state.settings.baseUrl,
    ) { Text(stringResource(R.string.settings_save)) }

    Text(stringResource(R.string.settings_certificate), style = MaterialTheme.typography.labelLarge)
    Text(
        state.settings.certificateAlias ?: stringResource(R.string.settings_certificate_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions::selectCertificate) { Text(stringResource(R.string.settings_select_certificate)) }
        if (state.settings.certificateAlias != null) {
            OutlinedButton(onClick = actions::clearCertificate) { Text(stringResource(R.string.settings_clear_certificate)) }
        }
    }
}

@Composable
private fun HomeSection(state: SettingsState, actions: SettingsActions) {
    SectionTitle(R.string.settings_section_home)
    Text(
        stringResource(if (state.isDefaultHome) R.string.settings_home_is_default else R.string.settings_home_not_default),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = actions::requestHomeRole) {
        Text(stringResource(if (state.isDefaultHome) R.string.settings_home_change else R.string.settings_home_set))
    }
}

@Composable
private fun LauncherSection(state: SettingsState, actions: SettingsActions) {
    SectionTitle(R.string.settings_section_launcher)
    Text(stringResource(R.string.settings_launcher_hint), style = MaterialTheme.typography.bodySmall)
    val entries = state.launcher.entries
    if (entries.isEmpty()) {
        Text(stringResource(R.string.settings_launcher_empty), style = MaterialTheme.typography.bodyMedium)
    }
    entries.forEachIndexed { index, entry ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                EntryIcon(entry)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(entry.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    describe(entry),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { actions.moveEntry(entry, -1) }, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.launcher_move_left))
            }
            IconButton(onClick = { actions.moveEntry(entry, 1) }, enabled = index < entries.lastIndex) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.launcher_move_right))
            }
            IconButton(onClick = { actions.editEntry(entry) }) {
                Icon(Icons.Filled.Edit, stringResource(R.string.launcher_edit))
            }
            IconButton(onClick = { actions.removeEntry(entry) }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.launcher_remove))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            actions.addEntry(LauncherEntry.Link(ShellViewModel.newId(), "", ""))
        }) { Text(stringResource(R.string.launcher_add_link)) }
        OutlinedButton(onClick = {
            actions.addEntry(LauncherEntry.WebPage(ShellViewModel.newId(), "", "/"))
        }) { Text(stringResource(R.string.launcher_add_web)) }
    }
    OutlinedButton(onClick = actions::resetLauncher) { Text(stringResource(R.string.launcher_reset)) }
}

private fun describe(entry: LauncherEntry): String = when (entry) {
    is LauncherEntry.App -> entry.packageName
    is LauncherEntry.Link -> entry.uri
    is LauncherEntry.WebPage -> entry.path
}

@Composable
private fun WakeSection(state: SettingsState, actions: SettingsActions) {
    SectionTitle(R.string.settings_section_wake)
    Text(stringResource(R.string.wake_explainer), style = MaterialTheme.typography.bodySmall)

    WakeMode.entries.forEach { mode ->
        val label = when (mode) {
            WakeMode.Off -> R.string.wake_mode_off
            WakeMode.WhileVisible -> R.string.wake_mode_visible
            WakeMode.Always -> R.string.wake_mode_always
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = state.settings.wakeMode == mode,
                    onClick = { actions.setWakeMode(mode) },
                    role = Role.RadioButton,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.settings.wakeMode == mode, onClick = null)
            Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
        }
    }

    Text(
        when (val s = state.wakeStatus) {
            WakeStatus.Idle -> stringResource(R.string.wake_status_idle)
            WakeStatus.Listening -> stringResource(R.string.wake_status_listening)
            WakeStatus.PausedForVoice -> stringResource(R.string.wake_status_paused)
            is WakeStatus.Error -> stringResource(R.string.wake_status_error, s.message)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.settings.wakeMode != WakeMode.Off && !state.micGranted) {
        Text(stringResource(R.string.wake_needs_mic), style = MaterialTheme.typography.bodySmall)
    }
    if (state.settings.wakeMode != WakeMode.Off && !state.models.ready) {
        Text(stringResource(R.string.wake_needs_models), style = MaterialTheme.typography.bodySmall)
    }
    if (state.settings.wakeMode == WakeMode.Always && !state.notificationsAllowed) {
        Text(stringResource(R.string.wake_notifications_needed), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::openNotificationSettings) {
            Text(stringResource(R.string.wake_notifications_open))
        }
    }
    if (state.settings.wakeMode == WakeMode.Always && !state.fullScreenIntentAllowed) {
        Text(stringResource(R.string.wake_full_screen_needed), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::allowFullScreenIntent) {
            Text(stringResource(R.string.wake_full_screen_allow))
        }
    }

    var threshold by remember(state.settings.wakeThreshold) { mutableFloatStateOf(state.settings.wakeThreshold) }
    Text(stringResource(R.string.wake_threshold, threshold), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = threshold,
        onValueChange = { threshold = it },
        onValueChangeFinished = { actions.setWakeThreshold(threshold) },
        valueRange = 0.1f..0.9f,
        steps = 15,
    )
    Text(stringResource(R.string.wake_threshold_hint), style = MaterialTheme.typography.bodySmall)

    Text(stringResource(R.string.wake_models), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(
            R.string.wake_models_state,
            yesNo(state.models.melspectrogram),
            yesNo(state.models.embedding),
            yesNo(state.models.phrase),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = actions::downloadFeatureModels, enabled = !state.modelBusy) {
        Text(stringResource(R.string.wake_download_features))
    }
    var modelUrl by remember(state.settings.wakeModelUrl) { mutableStateOf(state.settings.wakeModelUrl) }
    OutlinedTextField(
        value = modelUrl,
        onValueChange = { modelUrl = it },
        label = { Text(stringResource(R.string.wake_model_url)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { actions.downloadPhraseModel(modelUrl) }, enabled = !state.modelBusy && modelUrl.isNotBlank()) {
            Text(stringResource(R.string.wake_download_phrase))
        }
        OutlinedButton(onClick = actions::pickPhraseModel, enabled = !state.modelBusy) {
            Text(stringResource(R.string.wake_pick_phrase))
        }
        if (state.modelBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
    state.modelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun yesNo(present: Boolean): String =
    stringResource(if (present) R.string.wake_model_present else R.string.wake_model_missing)
