package os.retinue.ara.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.apps.ChamberApp
import os.retinue.ara.apps.ChamberAppsState

/**
 * The shown endpoint's chamber apps (Retinue's docs/chamber-apps.md), with the
 * dashboard first: Ara has no home screen of its own, so this is how the user
 * switches between the server's apps and its dashboard. A tap loads the page
 * in the shell's WebView, on the endpoint's origin, where the app finds
 * `window.RetinueShell` like the dashboard does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChamberAppsSheet(
    instanceName: String,
    state: ChamberAppsState,
    onDashboard: () -> Unit,
    onOpen: (ChamberApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.chamber_apps_title, instanceName),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    AppRow(
                        Icons.Filled.Home,
                        stringResource(R.string.chamber_apps_dashboard),
                        stringResource(R.string.chamber_apps_dashboard_hint),
                        onDashboard,
                    )
                    HorizontalDivider()
                }
                items(state.apps, key = { "${it.chamber}/${it.id}" }) { app ->
                    AppRow(Icons.Filled.Widgets, app.name, app.description.ifEmpty { app.chamber }) { onOpen(app) }
                }
                item {
                    val note = when {
                        state.failed -> R.string.chamber_apps_failed
                        state.stale -> R.string.chamber_apps_offline
                        state.loading && state.apps.isEmpty() -> R.string.chamber_apps_loading
                        state.apps.isEmpty() -> R.string.chamber_apps_none
                        else -> null
                    }
                    if (note != null) {
                        Text(
                            stringResource(note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
