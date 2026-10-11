package os.retinue.ara.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.launcher.AppCatalog
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.LauncherLinks

/** Height of the launcher bar (plan §1: about 64 dp, above the gesture inset). */
val LauncherBarHeight = 68.dp

/** Slot width bounds: slots share the bar's width evenly, and scroll only below the minimum. */
private val SlotMinWidth = 60.dp
private val SlotMaxWidth = 88.dp

/**
 * The width of one slot when [slots] share [available]: an even share, so a
 * few entries fill the bar without gaps, but never narrower than a readable
 * minimum (the entries scroll then) and never wider than the maximum.
 */
internal fun slotWidth(available: Dp, slots: Int): Dp =
    (available / slots.coerceAtLeast(1)).coerceIn(SlotMinWidth, SlotMaxWidth)

/**
 * The bottom launcher bar: the configured entries (scrolling horizontally when
 * there are many) and, always last, "All apps". Long-press an entry for edit /
 * remove / move (an app also into a folder) and the shell settings; long-press
 * "All apps" for a new folder.
 */
@Composable
fun LauncherBar(
    entries: List<LauncherEntry>,
    onOpen: (LauncherEntry) -> Unit,
    onEdit: (LauncherEntry) -> Unit,
    onRemove: (LauncherEntry) -> Unit,
    onMove: (LauncherEntry, Int) -> Unit,
    onMoveToFolder: (LauncherEntry.App, LauncherEntry.Folder) -> Unit,
    onNewFolder: () -> Unit,
    onAllApps: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folders = remember(entries) { entries.filterIsInstance<LauncherEntry.Folder>() }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The entries plus "All apps".
            val width = slotWidth(maxWidth, entries.size + 1)
            Row(
                modifier = Modifier.fillMaxWidth().height(LauncherBarHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).fillMaxHeight().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    entries.forEachIndexed { index, entry ->
                        var menu by remember(entry.id) { mutableStateOf(false) }
                        Box {
                            LauncherSlot(
                                label = entry.label,
                                icon = { EntryIcon(entry) },
                                onClick = { onOpen(entry) },
                                onLongClick = { menu = true },
                                width = width,
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem(R.string.launcher_edit, Icons.Filled.Edit) { menu = false; onEdit(entry) }
                                if (index > 0) {
                                    MenuItem(R.string.launcher_move_left, Icons.AutoMirrored.Filled.KeyboardArrowLeft) {
                                        menu = false
                                        onMove(entry, -1)
                                    }
                                }
                                if (index < entries.lastIndex) {
                                    MenuItem(R.string.launcher_move_right, Icons.AutoMirrored.Filled.KeyboardArrowRight) {
                                        menu = false
                                        onMove(entry, 1)
                                    }
                                }
                                // Only apps go into folders.
                                if (entry is LauncherEntry.App) {
                                    folders.forEach { folder ->
                                        MenuItem(
                                            text = stringResource(R.string.launcher_move_to_folder, folder.label),
                                            icon = { FolderIcon(folder, size = 24.dp) },
                                        ) {
                                            menu = false
                                            onMoveToFolder(entry, folder)
                                        }
                                    }
                                }
                                MenuItem(R.string.launcher_remove, Icons.Filled.Delete) { menu = false; onRemove(entry) }
                                MenuItem(R.string.settings_title, Icons.Filled.Settings) { menu = false; onSettings() }
                            }
                        }
                    }
                }
                var allMenu by remember { mutableStateOf(false) }
                Box {
                    LauncherSlot(
                        label = stringResource(R.string.launcher_all_apps),
                        icon = { Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(30.dp)) },
                        onClick = onAllApps,
                        onLongClick = { allMenu = true },
                        width = width,
                    )
                    DropdownMenu(expanded = allMenu, onDismissRequest = { allMenu = false }) {
                        MenuItem(R.string.folder_new, Icons.Filled.CreateNewFolder) { allMenu = false; onNewFolder() }
                        MenuItem(R.string.settings_title, Icons.Filled.Settings) { allMenu = false; onSettings() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MenuItem(textRes: Int, icon: ImageVector, onClick: () -> Unit) {
    MenuItem(stringResource(textRes), { Icon(icon, contentDescription = null) }, onClick = onClick)
}

@Composable
internal fun MenuItem(text: String, icon: @Composable () -> Unit, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon,
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherSlot(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 76.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = label)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * An entry's icon: the app's or shortcut's own, a symbol for the link scheme /
 * web page, or the folder's.
 */
@Composable
fun EntryIcon(entry: LauncherEntry) {
    when (entry) {
        is LauncherEntry.App -> AppIcon(entry.packageName, entry.activityName)
        is LauncherEntry.Shortcut -> ShortcutIcon(entry.packageName, entry.shortcutId)
        is LauncherEntry.Link -> Icon(linkIcon(entry.uri), contentDescription = null, modifier = Modifier.size(30.dp))
        is LauncherEntry.WebPage -> Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(30.dp))
        is LauncherEntry.Folder -> FolderIcon(entry)
    }
}

@Composable
fun AppIcon(packageName: String, activityName: String, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, packageName, activityName) {
        value = AppCatalog.icon(context, packageName, activityName)
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** A pinned shortcut's icon, else its app's, else a generic one (looked up off the main thread). */
@Composable
fun ShortcutIcon(packageName: String, shortcutId: String, size: Dp = 34.dp) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, packageName, shortcutId) {
        value = AppCatalog.shortcutIcon(context, packageName, shortcutId)
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(size))
    }
}

private fun linkIcon(uri: String): ImageVector = when (uri.substringBefore(':').trim().lowercase()) {
    "tel" -> Icons.Filled.Phone
    "sms", "smsto", "mms", "mmsto" -> Icons.Filled.Sms
    "mailto" -> Icons.Filled.Email
    "geo" -> Icons.Filled.Map
    "http", "https" -> Icons.Filled.Language
    else -> Icons.Filled.Link
}

/**
 * Add or edit an entry. [entry] is the one being edited, or a blank template of
 * the kind being added (a Link or a WebPage). Apps and shortcuts only have a
 * label to edit, as would a folder here (folders have their own editor,
 * FolderEditor).
 */
@Composable
fun EntryEditorDialog(
    entry: LauncherEntry,
    isNew: Boolean,
    onSave: (LauncherEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(entry) { mutableStateOf(entry.label) }
    var target by remember(entry) {
        mutableStateOf(
            when (entry) {
                is LauncherEntry.Link -> entry.uri
                is LauncherEntry.WebPage -> entry.path
                is LauncherEntry.App -> entry.packageName
                is LauncherEntry.Shortcut -> entry.packageName
                is LauncherEntry.Folder -> ""
            },
        )
    }
    val targetValid = when (entry) {
        is LauncherEntry.Link -> LauncherLinks.isValid(target)
        is LauncherEntry.WebPage -> !target.contains("://")
        is LauncherEntry.App, is LauncherEntry.Shortcut, is LauncherEntry.Folder -> true
    }
    val canSave = label.isNotBlank() && targetValid
    val title = when {
        entry is LauncherEntry.Link && isNew -> R.string.launcher_add_link
        entry is LauncherEntry.WebPage && isNew -> R.string.launcher_add_web
        else -> R.string.launcher_edit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.launcher_label)) },
                    singleLine = true,
                )
                when (entry) {
                    is LauncherEntry.Link -> OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(stringResource(R.string.launcher_uri)) },
                        placeholder = { Text("tel:  sms:  mailto:  geo:0,0?q=  https://…") },
                        singleLine = true,
                        isError = target.isNotEmpty() && !targetValid,
                    )
                    is LauncherEntry.WebPage -> OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(stringResource(R.string.launcher_path)) },
                        placeholder = { Text("/#contacts") },
                        singleLine = true,
                        isError = !targetValid,
                    )
                    is LauncherEntry.App, is LauncherEntry.Shortcut ->
                        Text(target, style = MaterialTheme.typography.bodySmall)
                    is LauncherEntry.Folder -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val l = label.trim()
                    val t = target.trim()
                    onSave(
                        when (entry) {
                            is LauncherEntry.Link -> entry.copy(label = l, uri = t)
                            is LauncherEntry.WebPage -> entry.copy(label = l, path = t.ifEmpty { "/" })
                            is LauncherEntry.App -> entry.copy(label = l)
                            is LauncherEntry.Shortcut -> entry.copy(label = l)
                            is LauncherEntry.Folder -> entry.copy(label = l)
                        },
                    )
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
