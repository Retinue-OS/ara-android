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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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
 * unsupported. Accepting pins the shortcut for Ara and appends it to the bar.
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
                ConfirmDialog(
                    info = info,
                    label = label,
                    onAdd = { add(request, info, label) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun add(request: LauncherApps.PinItemRequest, info: ShortcutInfo, label: String) {
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
        lifecycleScope.launch {
            val stored = try {
                // The same store the bar reads: the shell's flow picks the entry up at once.
                AraStore(applicationContext).updateLauncher {
                    it.add(LauncherEntry.Shortcut(ShellViewModel.newId(), label, info.`package`, info.id))
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not store the shortcut", e)
                false
            }
            val message = if (stored) R.string.pin_shortcut_added else R.string.pin_shortcut_failed
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

@Composable
private fun ConfirmDialog(info: ShortcutInfo, label: String, onAdd: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? by produceState<ImageBitmap?>(null, info) {
        value = AppCatalog.shortcutIcon(context, info)
    }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pin_shortcut_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val b = icon
                if (b != null) {
                    Image(bitmap = b, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(40.dp))
                }
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    onAdd()
                },
            ) { Text(stringResource(R.string.pin_shortcut_add)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
