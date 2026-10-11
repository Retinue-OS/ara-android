package os.retinue.ara.ui

import android.net.http.SslError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.shell.PageFailure
import os.retinue.ara.shell.PageState
import os.retinue.ara.shell.PageStatus

/** A thin bar at the top of the web area while the main frame loads. */
@Composable
fun PageProgressBar(progress: Int, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0, 100) / 100f },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Covers the web area when the main frame failed: why, where, and what to do
 * (retry, pick a client certificate, open the shell settings).
 */
@Composable
fun PageFailureCard(
    failure: PageFailure,
    url: String?,
    certificateSelected: Boolean,
    onRetry: () -> Unit,
    onChooseCertificate: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.page_failed_title), style = MaterialTheme.typography.titleMedium)
                    Text(describeFailure(failure), style = MaterialTheme.typography.bodyMedium)
                    (failure.url ?: url)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (!certificateSelected && suggestsCertificate(failure)) {
                        Text(stringResource(R.string.page_failed_no_certificate), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onRetry) { Text(stringResource(R.string.page_retry)) }
                    if (!certificateSelected) {
                        OutlinedButton(onClick = onChooseCertificate) {
                            Text(stringResource(R.string.page_choose_certificate))
                        }
                    }
                    OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
                }
            }
        }
    }
}

/** A refusal or an unreachable host: what a missing client certificate at the edge looks like. */
private fun suggestsCertificate(failure: PageFailure): Boolean = when (failure) {
    is PageFailure.Http -> failure.status == 401 || failure.status == 403
    is PageFailure.Network -> true
    is PageFailure.Ssl, is PageFailure.SignInCancelled -> false
}

/** The failure in plain words. */
@Composable
fun describeFailure(failure: PageFailure): String = when (failure) {
    is PageFailure.Network -> stringResource(R.string.page_failed_network, failure.description, failure.code)
    is PageFailure.Http -> stringResource(R.string.page_failed_http, failure.status, failure.reason).trim()
    is PageFailure.Ssl -> stringResource(R.string.page_failed_ssl, describeSslError(failure.primaryError))
    is PageFailure.SignInCancelled -> stringResource(R.string.page_failed_sign_in_cancelled)
}

@Composable
private fun describeSslError(primaryError: Int): String = when (primaryError) {
    SslError.SSL_NOTYETVALID -> stringResource(R.string.page_ssl_not_yet_valid)
    SslError.SSL_EXPIRED -> stringResource(R.string.page_ssl_expired)
    SslError.SSL_IDMISMATCH -> stringResource(R.string.page_ssl_id_mismatch)
    SslError.SSL_UNTRUSTED -> stringResource(R.string.page_ssl_untrusted)
    SslError.SSL_DATE_INVALID -> stringResource(R.string.page_ssl_date_invalid)
    SslError.SSL_INVALID -> stringResource(R.string.page_ssl_invalid)
    else -> stringResource(R.string.page_ssl_other, primaryError)
}

/** One line for the status, as the diagnostics show it. */
@Composable
fun describeStatus(status: PageStatus): String = when (status) {
    PageStatus.Idle -> stringResource(R.string.page_status_idle)
    is PageStatus.Loading -> stringResource(R.string.page_status_loading, status.progress)
    PageStatus.Ready -> stringResource(R.string.page_status_ready)
    is PageStatus.Failed -> stringResource(R.string.page_status_failed, describeFailure(status.failure))
}

/**
 * The page's status for the settings screen, laid out to be screenshotted:
 * status, last URL, client certificate and the page's recent console errors.
 */
@Composable
fun PageDiagnostics(page: PageState, certificateAlias: String?) {
    Text(stringResource(R.string.settings_section_page), style = MaterialTheme.typography.titleMedium)
    Text(describeStatus(page.status), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.page_last_url, page.url ?: stringResource(R.string.page_url_none)),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(
            R.string.page_certificate,
            certificateAlias ?: stringResource(R.string.settings_certificate_none),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (page.consoleErrors.isEmpty()) {
        Text(stringResource(R.string.page_console_none), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.page_console_errors), style = MaterialTheme.typography.labelLarge)
        page.consoleErrors.forEach { e ->
            Text(
                stringResource(R.string.page_console_line, e.source, e.line, e.message),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
