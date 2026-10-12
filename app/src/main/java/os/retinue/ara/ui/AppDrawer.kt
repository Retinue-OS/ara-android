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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import os.retinue.ara.launcher.PinnedShortcut
import java.text.Collator

/** One tile of the drawer: an app, or a shortcut pinned to Ara (an installed web app). */
private sealed interface DrawerItem {
    val label: String
    val key: String

    data class App(val app: AppInfo) : DrawerItem {
        override val label get() = app.label
        override val key get() = "app:" + app.packageName + "/" + app.activityName
    }

    data class Shortcut(val shortcut: PinnedShortcut) : DrawerItem {
        override val label get() = shortcut.label
        override val key get() = "shortcut:" + shortcut.packageName + "/" + shortcut.shortcutId
    }
}

/**
 * "All apps": every launchable app and every shortcut pinned to Ara (web apps
 * a browser installed), searchable. Tap opens; long-press pins an app to the
 * launcher bar, and offers a shortcut "pin" or "remove". Required once the
 * shell is the home screen, since otherwise other apps would be unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    shortcuts: List<PinnedShortcut>,
    isPinned: (AppInfo) -> Boolean,
    isShortcutPinned: (PinnedShortcut) -> Boolean,
    onLaunch: (AppInfo) -> Unit,
    onPin: (AppInfo) -> Unit,
    onLaunchShortcut: (PinnedShortcut) -> Unit,
    onPinShortcut: (PinnedShortcut) -> Unit,
    onRemoveShortcut: (PinnedShortcut) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember(apps, shortcuts) {
        val collator = Collator.getInstance()
        (apps.map { DrawerItem.App(it) } + shortcuts.map { DrawerItem.Shortcut(it) })
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            items
        } else {
            items.filter {
                val pkg = when (it) {
                    is DrawerItem.App -> it.app.packageName
                    is DrawerItem.Shortcut -> it.shortcut.packageName
                }
                it.label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true)
            }
        }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
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
                items(shown, key = { it.key }) { item ->
                    val pinned = when (item) {
                        is DrawerItem.App -> isPinned(item.app)
                        is DrawerItem.Shortcut -> isShortcutPinned(item.shortcut)
                    }
                    Box {
                        Column(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        when (item) {
                                            is DrawerItem.App -> onLaunch(item.app)
                                            is DrawerItem.Shortcut -> onLaunchShortcut(item.shortcut)
                                        }
                                    },
                                    onLongClick = {
                                        when (item) {
                                            is DrawerItem.App -> onPin(item.app)
                                            is DrawerItem.Shortcut -> menuFor = item.key
                                        }
                                    },
                                    onLongClickLabel = stringResource(R.string.drawer_pin),
                                )
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (item) {
                                is DrawerItem.App -> AppIcon(item.app.packageName, item.app.activityName, size = 44.dp)
                                is DrawerItem.Shortcut -> ShortcutIcon(item.shortcut.packageName, item.shortcut.shortcutId, size = 44.dp)
                            }
                            Text(
                                text = if (pinned) "• " + item.label else item.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                            )
                        }
                        if (item is DrawerItem.Shortcut) {
                            DropdownMenu(expanded = menuFor == item.key, onDismissRequest = { menuFor = null }) {
                                if (!pinned) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.drawer_pin)) },
                                        onClick = {
                                            menuFor = null
                                            onPinShortcut(item.shortcut)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.drawer_remove_shortcut)) },
                                    onClick = {
                                        menuFor = null
                                        onRemoveShortcut(item.shortcut)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
