package os.retinue.ara.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.shell.ConsoleError
import os.retinue.ara.shell.PageFailure

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
 * (retry, pick a client certificate, switch to another endpoint when there is
 * one, open the settings).
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
    onSwitchEndpoint: (() -> Unit)? = null,
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
                    if (onSwitchEndpoint != null) {
                        OutlinedButton(onClick = onSwitchEndpoint) { Text(stringResource(R.string.page_switch_endpoint)) }
                    }
                    OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.page_open_settings)) }
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
fun describeFailure(failure: PageFailure): String = failureText(LocalContext.current, failure)

/** The failure in plain words, outside of Compose (for the settings page's banner). */
fun failureText(context: Context, failure: PageFailure): String = when (failure) {
    is PageFailure.Network -> context.getString(R.string.page_failed_network, failure.description, failure.code)
    is PageFailure.Http -> context.getString(R.string.page_failed_http, failure.status, failure.reason).trim()
    is PageFailure.Ssl -> context.getString(R.string.page_failed_ssl, sslErrorText(context, failure.primaryError))
    is PageFailure.SignInCancelled -> context.getString(R.string.page_failed_sign_in_cancelled)
}

private fun sslErrorText(context: Context, primaryError: Int): String = when (primaryError) {
    SslError.SSL_NOTYETVALID -> context.getString(R.string.page_ssl_not_yet_valid)
    SslError.SSL_EXPIRED -> context.getString(R.string.page_ssl_expired)
    SslError.SSL_IDMISMATCH -> context.getString(R.string.page_ssl_id_mismatch)
    SslError.SSL_UNTRUSTED -> context.getString(R.string.page_ssl_untrusted)
    SslError.SSL_DATE_INVALID -> context.getString(R.string.page_ssl_date_invalid)
    SslError.SSL_INVALID -> context.getString(R.string.page_ssl_invalid)
    else -> context.getString(R.string.page_ssl_other, primaryError)
}

/** A console error of the page, as the settings page's banner lists it. */
fun consoleErrorText(context: Context, error: ConsoleError): String =
    context.getString(R.string.page_console_line, error.source, error.line, error.message)
