package os.retinue.ara.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.LauncherEntry

/**
 * "All apps": every launchable app, searchable. Tap launches; long-press
 * offers pinning the app to the launcher bar, adding it to a folder, or a new
 * folder with it. Required once the shell is the home screen, since otherwise
 * other apps would be unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    folders: List<LauncherEntry.Folder>,
    isPinned: (AppInfo) -> Boolean,
    onLaunch: (AppInfo) -> Unit,
    onPin: (AppInfo) -> Unit,
    onAddToFolder: (AppInfo, LauncherEntry.Folder) -> Unit,
    onNewFolder: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(apps, query) { apps.filter { it.matches(query) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.drawer_search)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                // The shell's own settings (base URL, certificate, launcher, wake phrase).
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            }
            Text(
                stringResource(R.string.drawer_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.packageName + "/" + it.activityName }) { app ->
                    var menu by remember { mutableStateOf(false) }
                    val pinned = isPinned(app)
                    Box {
                        AppTile(
                            label = if (pinned) "• " + app.label else app.label,
                            packageName = app.packageName,
                            activityName = app.activityName,
                            onClick = { onLaunch(app) },
                            onLongClick = { menu = true },
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem(
                                text = stringResource(if (pinned) R.string.drawer_pinned_already else R.string.drawer_pin),
                                icon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                enabled = !pinned,
                            ) {
                                menu = false
                                onPin(app)
                            }
                            folders.forEach { folder ->
                                val inFolder = folder.containsApp(app.packageName, app.activityName)
                                MenuItem(
                                    text = stringResource(
                                        if (inFolder) R.string.drawer_in_folder else R.string.drawer_add_to_folder,
                                        folder.label,
                                    ),
                                    icon = { FolderIcon(folder, size = 24.dp) },
                                    enabled = !inFolder,
                                ) {
                                    menu = false
                                    onAddToFolder(app, folder)
                                }
                            }
                            MenuItem(R.string.drawer_new_folder, Icons.Filled.CreateNewFolder) {
                                menu = false
                                onNewFolder(app)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Whether the app's name or package contains [query] (an empty query matches every app). */
internal fun AppInfo.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

/** An app in a grid (All apps, an open folder): its icon above its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    label: String,
    packageName: String,
    activityName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.launcher_options),
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(packageName, activityName, size = 44.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
