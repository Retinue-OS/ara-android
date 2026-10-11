package os.retinue.ara.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.FolderIcons
import os.retinue.ara.launcher.FolderKind
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PreparedIcon

// --- Folder types --------------------------------------------------------------

/** The symbol of a folder of this type that has no image of its own. */
val FolderKind.symbol: ImageVector
    get() = when (this) {
        FolderKind.General -> Icons.Filled.Folder
        FolderKind.Chat -> Icons.Filled.Forum
        FolderKind.Social -> Icons.Filled.Groups
        FolderKind.Media -> Icons.Filled.PlayCircle
        FolderKind.Photos -> Icons.Filled.PhotoLibrary
        FolderKind.Games -> Icons.Filled.SportsEsports
        FolderKind.News -> Icons.Filled.Newspaper
        FolderKind.Travel -> Icons.Filled.Explore
        FolderKind.Work -> Icons.Filled.Work
        FolderKind.Tools -> Icons.Filled.Build
        FolderKind.Shopping -> Icons.Filled.ShoppingCart
        FolderKind.Finance -> Icons.Filled.AccountBalance
        FolderKind.Health -> Icons.Filled.MonitorHeart
        FolderKind.SmartHome -> Icons.Filled.Home
    }

/** The type's name, which is also a new folder's name until the user gives it one. */
val FolderKind.nameRes: Int
    get() = when (this) {
        FolderKind.General -> R.string.folder_kind_general
        FolderKind.Chat -> R.string.folder_kind_chat
        FolderKind.Social -> R.string.folder_kind_social
        FolderKind.Media -> R.string.folder_kind_media
        FolderKind.Photos -> R.string.folder_kind_photos
        FolderKind.Games -> R.string.folder_kind_games
        FolderKind.News -> R.string.folder_kind_news
        FolderKind.Travel -> R.string.folder_kind_travel
        FolderKind.Work -> R.string.folder_kind_work
        FolderKind.Tools -> R.string.folder_kind_tools
        FolderKind.Shopping -> R.string.folder_kind_shopping
        FolderKind.Finance -> R.string.folder_kind_finance
        FolderKind.Health -> R.string.folder_kind_health
        FolderKind.SmartHome -> R.string.folder_kind_smart_home
    }

// --- Folder icon ---------------------------------------------------------------

/**
 * A folder's icon: its own [image], or else its type's symbol on a tinted
 * tile, so a folder stands out from apps and links on the bar.
 */
@Composable
fun FolderGlyph(kind: FolderKind, image: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                kind.symbol,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/** The stored icon image [name] (null while loading, or when there is none). */
@Composable
private fun storedIcon(name: String?): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, name) {
        value = name?.let { FolderIcons.load(context, it) }
    }
    return image
}

@Composable
fun FolderIcon(folder: LauncherEntry.Folder, size: Dp = 34.dp) {
    FolderGlyph(folder.kind, storedIcon(folder.icon), size)
}

// --- Open folder ---------------------------------------------------------------

/**
 * An open folder, over the bar like All apps: its apps (tap to open,
 * long-press to move or take one out), and buttons to add apps or edit it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSheet(
    folder: LauncherEntry.Folder,
    onLaunch: (LauncherEntry.App) -> Unit,
    onMove: (LauncherEntry.App, Int) -> Unit,
    onRemove: (LauncherEntry.App) -> Unit,
    /** Open the editor; true to start at the apps to add. */
    onEdit: (addApps: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FolderIcon(folder, size = 40.dp)
                Text(
                    folder.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                IconButton(onClick = { onEdit(true) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.folder_add_apps))
                }
                IconButton(onClick = { onEdit(false) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.folder_edit))
                }
            }
            if (folder.apps.isEmpty()) {
                Text(
                    stringResource(R.string.folder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = { onEdit(true) }) { Text(stringResource(R.string.folder_add_apps)) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(84.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(folder.apps, key = { _, app -> app.id }) { index, app ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            AppTile(
                                label = app.label,
                                packageName = app.packageName,
                                activityName = app.activityName,
                                onClick = { onLaunch(app) },
                                onLongClick = { menu = true },
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (index > 0) {
                                    MenuItem(R.string.launcher_move_left, Icons.AutoMirrored.Filled.KeyboardArrowLeft) {
                                        menu = false
                                        onMove(app, -1)
                                    }
                                }
                                if (index < folder.apps.lastIndex) {
                                    MenuItem(R.string.launcher_move_right, Icons.AutoMirrored.Filled.KeyboardArrowRight) {
                                        menu = false
                                        onMove(app, 1)
                                    }
                                }
                                MenuItem(R.string.folder_remove_app, Icons.Filled.Delete) {
                                    menu = false
                                    onRemove(app)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- Folder editor -------------------------------------------------------------

/** What the folder editor opens on. */
data class FolderEditorRequest(
    val folder: LauncherEntry.Folder,
    val isNew: Boolean,
    /** Opened to add apps: start at the apps to add. */
    val addApps: Boolean = false,
)

/** The icon as edited: the type's symbol, the image the folder has, or one just picked (stored on Save). */
private sealed interface IconChoice {
    data object Symbol : IconChoice

    data class Stored(val name: String) : IconChoice

    class Picked(val icon: PreparedIcon) : IconChoice
}

/**
 * Make or edit a folder: its name, its type (a standard folder type: symbol,
 * default name and the apps it suggests), its icon (the type's symbol, or an
 * image from the photo picker or any document provider, cropped to a square)
 * and its apps — apps only, so no folder goes into another. Full screen like
 * the settings; nothing is stored before Save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolderEditor(
    request: FolderEditorRequest,
    apps: List<AppInfo>,
    onSave: (folder: LauncherEntry.Folder, newIcon: ByteArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val kindNames = FolderKind.entries.associateWith { stringResource(it.nameRes) }
    val original = request.folder
    // Label, type and apps; the icon is kept apart until Save.
    var draft by remember(request) {
        mutableStateOf(original.copy(label = original.label.ifBlank { kindNames.getValue(original.kind) }))
    }
    var icon by remember(request) {
        mutableStateOf<IconChoice>(original.icon?.let { IconChoice.Stored(it) } ?: IconChoice.Symbol)
    }
    var importing by remember(request) { mutableStateOf(false) }
    var query by remember(request) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val onPicked: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    icon = IconChoice.Picked(FolderIcons.prepare(context, uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.folder_icon_unreadable, Toast.LENGTH_SHORT).show()
                } finally {
                    importing = false
                }
            }
        }
    }
    // Images on the device (the system photo picker) or from any document provider.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onPicked)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onPicked)
    val pick: (() -> Unit) -> Unit = { start ->
        try {
            start()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    val chooseKind: (FolderKind) -> Unit = { kind ->
        // The name follows the type until the user names the folder.
        val named = draft.label.isNotBlank() && draft.label.trim() != kindNames.getValue(draft.kind)
        draft = draft.copy(kind = kind, label = if (named) draft.label else kindNames.getValue(kind))
    }
    val save: () -> Unit = {
        val folder = draft.copy(
            label = draft.label.trim(),
            icon = when (val chosen = icon) {
                IconChoice.Symbol -> null
                is IconChoice.Stored -> chosen.name
                // Stored on save; should that fail, the folder keeps the icon it had.
                is IconChoice.Picked -> original.icon
            },
        )
        onSave(folder, (icon as? IconChoice.Picked)?.icon?.png)
    }

    val inFolder = remember(draft.apps) { draft.apps.mapTo(HashSet()) { it.packageName + "/" + it.activityName } }
    val available = remember(apps, inFolder, query) {
        apps.filter { it.packageName + "/" + it.activityName !in inFolder && it.matches(query) }
    }
    // Apps of the type's Android categories first, unless searching.
    val suggested = if (query.isBlank()) available.filter { draft.kind.suggests(it.category) } else emptyList()
    val others = if (suggested.isEmpty()) available else available.filterNot { draft.kind.suggests(it.category) }

    // Items before "Add apps": name, type, icon, "In this folder", its apps (or a note).
    val listState = remember(request) {
        LazyListState(firstVisibleItemIndex = if (request.addApps) 4 + maxOf(original.apps.size, 1) else 0)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (request.isNew) R.string.folder_new else R.string.folder_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = save, enabled = draft.label.isNotBlank() && !importing) {
                        Text(stringResource(R.string.settings_save))
                    }
                },
            )
        },
        // Insets including the keyboard, so the search field and its results stay above it.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item(key = "name") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    when (val chosen = icon) {
                        IconChoice.Symbol -> FolderGlyph(draft.kind, null, 56.dp)
                        is IconChoice.Stored -> FolderGlyph(draft.kind, storedIcon(chosen.name), 56.dp)
                        is IconChoice.Picked -> FolderGlyph(draft.kind, chosen.icon.preview, 56.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = draft.label,
                        onValueChange = { draft = draft.copy(label = it) },
                        label = { Text(stringResource(R.string.folder_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(key = "type") {
                Column {
                    EditorTitle(stringResource(R.string.folder_type))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FolderKind.entries.forEach { kind ->
                            FilterChip(
                                selected = kind == draft.kind,
                                onClick = { chooseKind(kind) },
                                label = { Text(kindNames.getValue(kind)) },
                                leadingIcon = {
                                    Icon(kind.symbol, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                                },
                            )
                        }
                    }
                }
            }
            item(key = "icon") {
                Column {
                    EditorTitle(stringResource(R.string.folder_icon))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                pick {
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                            enabled = !importing,
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.folder_icon_photos))
                        }
                        OutlinedButton(onClick = { pick { filePicker.launch(arrayOf("image/*")) } }, enabled = !importing) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.folder_icon_files))
                        }
                        if (icon != IconChoice.Symbol) {
                            TextButton(onClick = { icon = IconChoice.Symbol }, enabled = !importing) {
                                Text(stringResource(R.string.folder_icon_symbol))
                            }
                        }
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterVertically).padding(8.dp).size(24.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.folder_icon_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "in-title") { EditorTitle(stringResource(R.string.folder_apps)) }
            if (draft.apps.isEmpty()) {
                item(key = "in-none") {
                    Text(stringResource(R.string.folder_apps_none), style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemsIndexed(draft.apps, key = { _, app -> "in/" + app.id }) { index, app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, app.activityName, size = 36.dp)
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    IconButton(onClick = { draft = draft.move(app.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.launcher_move_left))
                    }
                    IconButton(onClick = { draft = draft.move(app.id, 1) }, enabled = index < draft.apps.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.launcher_move_right))
                    }
                    IconButton(onClick = { draft = draft.remove(app.id) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.folder_remove_app))
                    }
                }
            }
            item(key = "add-title") {
                Column {
                    EditorTitle(stringResource(R.string.folder_add_apps))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.drawer_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
            }
            if (suggested.isNotEmpty()) {
                item(key = "suggested-title") {
                    EditorSubtitle(stringResource(R.string.folder_suggested, kindNames.getValue(draft.kind)))
                }
                items(suggested, key = { "suggested/" + it.packageName + "/" + it.activityName }) { app ->
                    AddAppRow(app) { draft = draft.add(app.toEntry()) }
                }
                item(key = "others-title") { EditorSubtitle(stringResource(R.string.launcher_all_apps)) }
            }
            items(others, key = { "other/" + it.packageName + "/" + it.activityName }) { app ->
                AddAppRow(app) { draft = draft.add(app.toEntry()) }
            }
        }
    }
}

@Composable
private fun EditorTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun EditorSubtitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** An app that can go into the folder: tap the row to add it. */
@Composable
private fun AddAppRow(app: AppInfo, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.folder_add_app), onClick = onAdd)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, app.activityName, size = 36.dp)
        Text(
            app.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Icon(Icons.Filled.Add, contentDescription = null)
    }
}
