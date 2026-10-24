package os.retinue.ara.ui

import android.content.pm.ApplicationInfo
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import java.text.Collator

/**
 * An item of All apps: an installed app, or a shortcut pinned to Ara (an
 * installed web app), which is an app in every other way too. The folder
 * editor offers the same items.
 */
internal sealed interface DrawerItem {
    val label: String

    /** What it starts: the [LauncherEntry.Launchable.key] of an entry made from it. */
    val key: String

    /** The app's Android category, which folder types suggest apps by; web apps have none. */
    val category: Int

    /** Whether its name or package contains [query] (an empty query matches every item). */
    fun matches(query: String): Boolean

    /** A new launcher entry for it, on the bar or in a folder. */
    fun toEntry(): LauncherEntry.Launchable

    data class App(val app: AppInfo) : DrawerItem {
        override val label get() = app.label
        override val key get() = LauncherEntry.Launchable.appKey(app.packageName, app.activityName)
        override val category get() = app.category
        override fun matches(query: String): Boolean = app.matches(query)
        override fun toEntry(): LauncherEntry.Launchable = app.toEntry()
    }

    data class Shortcut(val shortcut: PinnedShortcut) : DrawerItem {
        override val label get() = shortcut.label
        override val key get() = LauncherEntry.Launchable.shortcutKey(shortcut.packageName, shortcut.shortcutId)
        override val category get() = ApplicationInfo.CATEGORY_UNDEFINED
        override fun matches(query: String): Boolean = shortcut.matches(query)
        override fun toEntry(): LauncherEntry.Launchable = shortcut.toEntry()
    }
}

/** [apps] and the web apps pinned to Ara ([shortcuts]) in one list, sorted by name. */
internal fun drawerItems(apps: List<AppInfo>, shortcuts: List<PinnedShortcut>): List<DrawerItem> {
    val collator = Collator.getInstance()
    return (apps.map { DrawerItem.App(it) } + shortcuts.map { DrawerItem.Shortcut(it) })
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

@Composable
internal fun DrawerItemIcon(item: DrawerItem, size: Dp) {
    when (item) {
        is DrawerItem.App -> AppIcon(item.app.packageName, item.app.activityName, size)
        is DrawerItem.Shortcut -> ShortcutIcon(item.shortcut.packageName, item.shortcut.shortcutId, size)
    }
}

/**
 * "All apps": every launchable app and every shortcut pinned to Ara (web apps
 * a browser installed), searchable. Tap opens; long-press offers pinning it to
 * the launcher bar, adding it to a folder, or a new folder with it — for an
 * app and a web app alike — and for a web app also removing it from the
 * phone. Required once the shell is the home screen, since otherwise other
 * apps would be unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    shortcuts: List<PinnedShortcut>,
    folders: List<LauncherEntry.Folder>,
    isPinned: (AppInfo) -> Boolean,
    isShortcutPinned: (PinnedShortcut) -> Boolean,
    onLaunch: (AppInfo) -> Unit,
    onPin: (AppInfo) -> Unit,
    onAddToFolder: (LauncherEntry.Launchable, LauncherEntry.Folder) -> Unit,
    onNewFolder: (LauncherEntry.Launchable) -> Unit,
    onLaunchShortcut: (PinnedShortcut) -> Unit,
    onPinShortcut: (PinnedShortcut) -> Unit,
    onRemoveShortcut: (PinnedShortcut) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember(apps, shortcuts) { drawerItems(apps, shortcuts) }
    val shown = remember(items, query) { items.filter { it.matches(query) } }
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
                    when (item) {
                        is DrawerItem.App -> DrawerTile(
                            item = item,
                            folders = folders,
                            pinned = isPinned(item.app),
                            onLaunch = { onLaunch(item.app) },
                            onPin = { onPin(item.app) },
                            onAddToFolder = onAddToFolder,
                            onNewFolder = onNewFolder,
                            onRemove = null,
                        )
                        is DrawerItem.Shortcut -> DrawerTile(
                            item = item,
                            folders = folders,
                            pinned = isShortcutPinned(item.shortcut),
                            onLaunch = { onLaunchShortcut(item.shortcut) },
                            onPin = { onPinShortcut(item.shortcut) },
                            onAddToFolder = onAddToFolder,
                            onNewFolder = onNewFolder,
                            onRemove = { onRemoveShortcut(item.shortcut) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * An app or web app in All apps. Long-press: pin it to the bar, add it to a
 * folder or a new one and, given [onRemove] (web apps), remove it from the
 * phone (unpin it).
 */
@Composable
private fun DrawerTile(
    item: DrawerItem,
    folders: List<LauncherEntry.Folder>,
    pinned: Boolean,
    onLaunch: () -> Unit,
    onPin: () -> Unit,
    onAddToFolder: (LauncherEntry.Launchable, LauncherEntry.Folder) -> Unit,
    onNewFolder: (LauncherEntry.Launchable) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppTile(
            label = if (pinned) "• " + item.label else item.label,
            icon = { DrawerItemIcon(item, size = 44.dp) },
            onClick = onLaunch,
            onLongClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(
                text = stringResource(if (pinned) R.string.drawer_pinned_already else R.string.drawer_pin),
                icon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                enabled = !pinned,
            ) {
                menu = false
                onPin()
            }
            folders.forEach { folder ->
                val inFolder = folder.containsKey(item.key)
                MenuItem(
                    text = stringResource(
                        if (inFolder) R.string.drawer_in_folder else R.string.drawer_add_to_folder,
                        folderName(folder),
                    ),
                    icon = { FolderIcon(folder, size = 24.dp) },
                    enabled = !inFolder,
                ) {
                    menu = false
                    onAddToFolder(item.toEntry(), folder)
                }
            }
            MenuItem(R.string.drawer_new_folder, Icons.Filled.CreateNewFolder) {
                menu = false
                onNewFolder(item.toEntry())
            }
            if (onRemove != null) {
                MenuItem(R.string.drawer_remove_shortcut, Icons.Filled.Delete) {
                    menu = false
                    onRemove()
                }
            }
        }
    }
}

/** Whether the shortcut's name or package contains [query] (an empty query matches every one). */
private fun PinnedShortcut.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

/** Whether the app's name or package contains [query] (an empty query matches every app). */
internal fun AppInfo.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

/** An app or web app in a grid (All apps, an open folder): its icon above its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    label: String,
    icon: @Composable () -> Unit,
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
        icon()
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
