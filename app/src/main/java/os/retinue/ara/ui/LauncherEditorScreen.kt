package os.retinue.ara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.launcher.LauncherEntry

/**
 * The launcher bar's editor, opened from the settings page's Launcher bar
 * card: the bar's entries in their order, each to move, edit or remove, and
 * links, web-app pages and folders to add. Native, as the bar itself is; the
 * entry and folder editors open over it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LauncherEditorScreen(
    entries: List<LauncherEntry>,
    onClose: () -> Unit,
    onAdd: (LauncherEntry) -> Unit,
    onAddFolder: () -> Unit,
    onEdit: (LauncherEntry) -> Unit,
    onRemove: (LauncherEntry) -> Unit,
    onMove: (LauncherEntry, Int) -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_launcher)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
            Text(stringResource(R.string.settings_launcher_hint), style = MaterialTheme.typography.bodySmall)
            if (entries.isEmpty()) {
                Text(stringResource(R.string.settings_launcher_empty), style = MaterialTheme.typography.bodyMedium)
            }
            entries.forEachIndexed { index, entry ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        EntryIcon(entry)
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(entryLabel(entry), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(describe(entry), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onMove(entry, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.launcher_move_left))
                    }
                    IconButton(onClick = { onMove(entry, 1) }, enabled = index < entries.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.launcher_move_right))
                    }
                    IconButton(onClick = { onEdit(entry) }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.launcher_edit))
                    }
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.launcher_remove))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAdd(LauncherEntry.Link(ShellViewModel.newId(), "", "")) }) {
                    Text(stringResource(R.string.launcher_add_link))
                }
                OutlinedButton(onClick = { onAdd(LauncherEntry.WebPage(ShellViewModel.newId(), "", "/")) }) {
                    Text(stringResource(R.string.launcher_add_web))
                }
                OutlinedButton(onClick = onAddFolder) { Text(stringResource(R.string.folder_add)) }
            }
            OutlinedButton(onClick = onReset) { Text(stringResource(R.string.launcher_reset)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The second line of an entry: what it opens. */
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
