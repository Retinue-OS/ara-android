package os.retinue.ara.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
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
import os.retinue.ara.shell.PageState
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.update.UpdateRuntime
import os.retinue.ara.update.UpdateState
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakeStatus

/** Everything the settings screen shows. */
data class SettingsState(
    val settings: ShellSettings,
    val launcher: LauncherConfig,
    val isDefaultHome: Boolean,
    /** Android lets other apps pin shortcuts to Ara (ShortcutManager.isRequestPinShortcutSupported). */
    val pinShortcutsSupported: Boolean,
    val models: WakeModelStatus,
    val modelBusy: Boolean,
    val modelMessage: String?,
    val wakeStatus: WakeStatus,
    val micGranted: Boolean,
    /** Android 14+: the wake alert may open the shell full-screen (see WakeAlert). */
    val fullScreenIntentAllowed: Boolean,
    val overlayAllowed: Boolean,
    /** The wake alert can be posted at all: permission, app notifications and its channel on. */
    val notificationsAllowed: Boolean,
    /** The web page's load status and console errors, for the diagnostics. */
    val page: PageState,
    /** Where the updater is (see UpdateRuntime). */
    val update: UpdateState,
    /** Android lets Ara install apps ("Install unknown apps"). */
    val installAllowed: Boolean,
)

/** What the settings screen can ask for. */
interface SettingsActions {
    fun close()
    fun saveBaseUrl(url: String)
    fun selectCertificate()
    fun clearCertificate()
    fun requestHomeRole()

    /** Ask Android to pin a shortcut to Ara, the way a browser installing a web app does. */
    fun testPinShortcut()
    fun addEntry(template: LauncherEntry)
    fun addFolder()
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
    fun allowOverlay()
    fun openNotificationSettings()
    fun setAutoUpdate(on: Boolean)
    fun setUpdateChannel(channel: UpdateChannel)
    fun checkForUpdates()

    /** Install the build the last check found. */
    fun installUpdate()

    /** Show the installer's confirmation again. */
    fun confirmUpdate()

    /** Open the system page where the user allows Ara to install apps. */
    fun allowUpdateInstalls()
}

/**
 * The shell's native settings (plan §1): base URL, client certificate, page
 * status, home role, launcher editor, wake phrase, updates.
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
            PageDiagnostics(state.page, state.settings.certificateAlias)
            HorizontalDivider()
            HomeSection(state, actions)
            HorizontalDivider()
            LauncherSection(state, actions)
            HorizontalDivider()
            WakeSection(state, actions)
            HorizontalDivider()
            UpdatesSection(state, actions)
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
    // What browsers check before they offer "Install app" / "Add to home screen".
    Text(
        stringResource(
            if (state.pinShortcutsSupported) R.string.settings_pin_supported else R.string.settings_pin_unsupported,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = actions::testPinShortcut) {
        Text(stringResource(R.string.settings_pin_test))
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                Text(entryLabel(entry), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            actions.addEntry(LauncherEntry.Link(ShellViewModel.newId(), "", ""))
        }) { Text(stringResource(R.string.launcher_add_link)) }
        OutlinedButton(onClick = {
            actions.addEntry(LauncherEntry.WebPage(ShellViewModel.newId(), "", "/"))
        }) { Text(stringResource(R.string.launcher_add_web)) }
        OutlinedButton(onClick = actions::addFolder) { Text(stringResource(R.string.folder_add)) }
    }
    OutlinedButton(onClick = actions::resetLauncher) { Text(stringResource(R.string.launcher_reset)) }
}

@Composable
private fun describe(entry: LauncherEntry): String = when (entry) {
    is LauncherEntry.App -> entry.packageName
    is LauncherEntry.Shortcut -> entry.packageName
    is LauncherEntry.Link -> entry.uri
    is LauncherEntry.WebPage -> entry.path
    is LauncherEntry.Folder -> if (entry.apps.isEmpty()) {
        stringResource(R.string.folder_empty)
    } else {
        pluralStringResource(R.plurals.folder_app_count, entry.apps.size, entry.apps.size, entry.apps.joinToString { it.label })
    }
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
            WakeStatus.PausedForOtherApp -> stringResource(R.string.wake_status_paused_other_app)
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
    if (state.settings.wakeMode == WakeMode.Always && !state.overlayAllowed) {
        Text(stringResource(R.string.wake_overlay_needed), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::allowOverlay) {
            Text(stringResource(R.string.wake_overlay_allow))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdatesSection(state: SettingsState, actions: SettingsActions) {
    SectionTitle(R.string.settings_section_updates)
    Text(stringResource(R.string.update_explainer), style = MaterialTheme.typography.bodySmall)
    val installed = UpdateRuntime.installed
    Text(
        when {
            installed.fromChannel -> stringResource(R.string.update_installed_build, installed.tag, installed.shortSha)
            installed.sha.isNotEmpty() -> stringResource(R.string.update_installed_branch_build, installed.shortSha)
            else -> stringResource(R.string.update_installed_local_build)
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.settings.autoUpdate,
                onValueChange = actions::setAutoUpdate,
                role = Role.Switch,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.update_auto), modifier = Modifier.weight(1f))
        Switch(checked = state.settings.autoUpdate, onCheckedChange = null)
    }
    Text(stringResource(R.string.update_auto_hint), style = MaterialTheme.typography.bodySmall)

    Text(stringResource(R.string.update_channel), style = MaterialTheme.typography.labelLarge)
    UpdateChannel.entries.forEach { channel ->
        val label = when (channel) {
            UpdateChannel.Nightly -> R.string.update_channel_nightly
            UpdateChannel.Release -> R.string.update_channel_release
        }
        val selected = state.settings.updateChannel == channel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = channel.selectable,
                    onClick = { actions.setUpdateChannel(channel) },
                    role = Role.RadioButton,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = channel.selectable)
            Text(
                stringResource(label),
                modifier = Modifier.padding(start = 8.dp),
                color = if (channel.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val update = state.update
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(updateStatus(update), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (update.busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
    if (update is UpdateState.Downloading) {
        LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = actions::checkForUpdates,
            enabled = !update.busy && update !is UpdateState.AwaitingConfirmation,
        ) { Text(stringResource(R.string.update_check_now)) }
        if (update is UpdateState.Available) {
            Button(onClick = actions::installUpdate) { Text(stringResource(R.string.update_install, update.release.tag)) }
        }
        if (update is UpdateState.AwaitingConfirmation) {
            Button(onClick = actions::confirmUpdate) { Text(stringResource(R.string.update_confirm)) }
        }
    }
    if (!state.installAllowed) {
        Text(stringResource(R.string.update_install_not_allowed), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::allowUpdateInstalls) { Text(stringResource(R.string.update_allow_installs)) }
    }
}

@Composable
private fun updateStatus(update: UpdateState): String = when (update) {
    UpdateState.Idle -> stringResource(R.string.update_status_idle)
    UpdateState.Checking -> stringResource(R.string.update_status_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date, update.latest.tag, ago(update.checkedAt))
    is UpdateState.NoBuilds -> stringResource(R.string.update_status_no_builds, ago(update.checkedAt))
    is UpdateState.Available -> stringResource(
        if (update.waitingForWifi) R.string.update_status_available_wifi else R.string.update_status_available,
        update.release.tag,
    )
    is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, update.release.tag, (update.progress * 100).toInt())
    is UpdateState.Installing -> stringResource(R.string.update_status_installing, update.release.tag)
    is UpdateState.AwaitingConfirmation -> stringResource(
        R.string.update_status_confirm,
        update.release?.tag ?: stringResource(R.string.update_the_update),
    )
    UpdateState.Installed -> stringResource(R.string.update_status_installed)
    is UpdateState.Failed -> stringResource(R.string.update_status_failed, update.message)
}

/** "3 hours ago", in the phone's language. */
private fun ago(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
