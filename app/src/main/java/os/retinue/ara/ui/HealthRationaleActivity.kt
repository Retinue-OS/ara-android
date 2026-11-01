package os.retinue.ara.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.shell.UrlRouter

/**
 * Why Ara asks for health data, as Health Connect requires every app that
 * reads it to say (its permission screen links here): the data goes only to
 * the configured Retinue server. A dialog over a translucent window, like the
 * pin-shortcut confirmation.
 */
class HealthRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AraStore(this)
        setContent {
            AraTheme {
                val origin by produceState<String?>(null) {
                    value = UrlRouter(store.settingsNow().baseUrl).origin?.toString()
                }
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.health_rationale_title)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.health_rationale_text))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                origin?.let { stringResource(R.string.health_rationale_server, it) }
                                    ?: stringResource(R.string.health_rationale_no_server),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.health_rationale_ok)) }
                    },
                )
            }
        }
    }
}
