package os.retinue.ara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.data.AraSettings

/**
 * Runtime configuration: the Retinue endpoint (the one environment-specific
 * value), polling/timeout/retry tuning, and client-certificate selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AraSettings,
    certificateAlias: String?,
    onSave: (AraSettings) -> Unit,
    onSelectCertificate: () -> Unit,
    onClearCertificate: () -> Unit,
    onClose: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var messagePath by remember { mutableStateOf(settings.messagePath) }
    var pollSeconds by remember { mutableStateOf((settings.pollIntervalMs / 1000).toString()) }
    var requestSeconds by remember { mutableStateOf((settings.requestTimeoutMs / 1000).toString()) }
    var jobSeconds by remember { mutableStateOf((settings.overallJobTimeoutMs / 1000).toString()) }
    var maxRetries by remember { mutableStateOf(settings.maxConsecutivePollFailures.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.settings_base_url)) },
                placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = messagePath,
                onValueChange = { messagePath = it },
                label = { Text(stringResource(R.string.settings_message_path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(pollSeconds, { pollSeconds = it }, R.string.settings_poll_interval)
            NumberField(requestSeconds, { requestSeconds = it }, R.string.settings_request_timeout)
            NumberField(jobSeconds, { jobSeconds = it }, R.string.settings_job_timeout)
            NumberField(maxRetries, { maxRetries = it }, R.string.settings_max_retries)

            Text(stringResource(R.string.settings_certificate), style = MaterialTheme.typography.labelLarge)
            Text(
                certificateAlias ?: stringResource(R.string.settings_certificate_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelectCertificate) {
                    Text(stringResource(R.string.settings_select_certificate))
                }
                if (certificateAlias != null) {
                    OutlinedButton(onClick = onClearCertificate) {
                        Text(stringResource(R.string.settings_clear_certificate))
                    }
                }
            }

            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            baseUrl = baseUrl.trim(),
                            messagePath = messagePath.trim().ifBlank { "/message" },
                            pollIntervalMs = pollSeconds.toSeconds(settings.pollIntervalMs),
                            requestTimeoutMs = requestSeconds.toSeconds(settings.requestTimeoutMs),
                            overallJobTimeoutMs = jobSeconds.toSeconds(settings.overallJobTimeoutMs),
                            maxConsecutivePollFailures = maxRetries.toIntOrNull()
                                ?.coerceAtLeast(1) ?: settings.maxConsecutivePollFailures,
                        ),
                    )
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Parse a seconds string into millis, falling back to the previous value. */
private fun String.toSeconds(fallbackMs: Long): Long =
    this.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 } ?: fallbackMs
