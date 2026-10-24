package os.retinue.ara.ui

import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.launcher.AppCatalog
import os.retinue.ara.launcher.LauncherEntry

/**
 * The home app's half of `ShortcutManager.requestPinShortcut()`: Android starts
 * this (action CONFIRM_PIN_SHORTCUT, resolved among the default home app's
 * activities) when an app asks to pin a shortcut — Chrome's "Install app" and
 * "Add to home screen" among them. Without it Android reports pinning as
 * unsupported. Accepting pins the shortcut for Ara, which lists it in All apps
 * with the apps, as it would a newly installed app; the user may also put it
 * on the bar or into a folder right away.
 */
class PinShortcutActivity : ComponentActivity() {

    private var adding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = try {
            getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent)
        } catch (e: Exception) {
            // A malformed extra from whoever started us.
            Log.w(TAG, "unreadable pin request", e)
            null
        }
        val info = request
            ?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }
            ?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val label = labelOf(info)
        setContent {
            AraTheme {
                // The folders it may go into as well (none until read, or if the store cannot be read).
                val folders by produceState(emptyList<LauncherEntry.Folder>()) {
                    value = try {
                        AraStore(applicationContext).launcher.first().folders
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "could not read the folders", e)
                        emptyList()
                    }
                }
                ConfirmDialog(
                    info = info,
                    label = label,
                    folders = folders,
                    onAdd = { placement -> add(request, info, label, placement) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun add(request: LauncherApps.PinItemRequest, info: ShortcutInfo, label: String, placement: Placement) {
        if (adding) return
        adding = true
        val accepted = try {
            request.accept()
        } catch (e: Exception) {
            // The request went stale meanwhile (IllegalStateException) or the caller is gone.
            Log.w(TAG, "pin request not accepted", e)
            false
        }
        if (!accepted) {
            Toast.makeText(applicationContext, R.string.pin_shortcut_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Pinned to Ara now, so in All apps whatever else happens.
        lifecycleScope.launch {
            val entry = LauncherEntry.Shortcut(ShellViewModel.newId(), label, info.`package`, info.id)
            val placed = try {
                // The same store the bar reads: the shell's flow picks the change up at once.
                val store = AraStore(applicationContext)
                when (placement) {
                    Placement.AllAppsOnly -> Unit
                    Placement.Bar -> store.updateLauncher { it.add(entry) }
                    is Placement.InFolder -> store.updateLauncher { it.addToFolder(placement.folderId, entry) }
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not store the shortcut", e)
                false
            }
            val message = when {
                placed && placement is Placement.Bar -> getString(R.string.pin_shortcut_added_bar, label)
                placed && placement is Placement.InFolder ->
                    getString(R.string.pin_shortcut_added_folder, label, placement.name)
                else -> getString(R.string.pin_shortcut_added, label)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** The short label, else the long one, else the app's name. */
    private fun labelOf(info: ShortcutInfo): String {
        info.shortLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        info.longLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            // The flags-object overload is API 33+; the int one works on every level.
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            info.`package`
        }
    }

    private companion object {
        const val TAG = "PinShortcut"
    }
}

/** Where an accepted shortcut goes besides All apps, where it is in any case. */
private sealed interface Placement {
    data object AllAppsOnly : Placement

    data object Bar : Placement

    data class InFolder(val folderId: String, val name: String) : Placement
}

/** The shortcut, and where else to put it: nowhere (the default, as for an installed app), the bar or a folder. */
@Composable
private fun ConfirmDialog(
    info: ShortcutInfo,
    label: String,
    folders: List<LauncherEntry.Folder>,
    onAdd: (Placement) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val icon: ImageBitmap? by produceState<ImageBitmap?>(null, info) {
        value = AppCatalog.shortcutIcon(context, info)
    }
    var busy by remember { mutableStateOf(false) }
    var placement by remember { mutableStateOf<Placement>(Placement.AllAppsOnly) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pin_shortcut_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val b = icon
                    if (b != null) {
                        Image(bitmap = b, contentDescription = null, modifier = Modifier.size(40.dp))
                    } else {
                        Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(40.dp))
                    }
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.selectableGroup()) {
                    PlacementOption(
                        text = stringResource(R.string.pin_shortcut_only_all_apps),
                        selected = placement == Placement.AllAppsOnly,
                        onSelect = { placement = Placement.AllAppsOnly },
                    )
                    PlacementOption(
                        text = stringResource(R.string.pin_shortcut_also_bar),
                        selected = placement == Placement.Bar,
                        onSelect = { placement = Placement.Bar },
                    )
                    folders.forEach { folder ->
                        val name = folderName(folder)
                        PlacementOption(
                            text = stringResource(R.string.pin_shortcut_also_folder, name),
                            selected = (placement as? Placement.InFolder)?.folderId == folder.id,
                            onSelect = { placement = Placement.InFolder(folder.id, name) },
                            icon = { FolderIcon(folder, size = 24.dp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    onAdd(placement)
                },
            ) { Text(stringResource(R.string.pin_shortcut_add)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PlacementOption(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        icon?.invoke()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
