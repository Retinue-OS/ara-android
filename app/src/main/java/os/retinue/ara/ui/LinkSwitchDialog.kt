package os.retinue.ara.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import os.retinue.ara.R

/**
 * A `retinue-shell:` link, or a chamber app named [appName], for another
 * configured endpoint: switch to it, or leave things as they are.
 */
@Composable
fun LinkSwitchDialog(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit, appName: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_switch_title, label)) },
        text = {
            Text(
                if (appName == null) {
                    stringResource(R.string.link_switch_body, label)
                } else {
                    stringResource(R.string.link_switch_app_body, label, appName)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.link_switch_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
