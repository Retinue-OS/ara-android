package os.retinue.ara.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.retinue.ara.service.AraNotifications

/**
 * The single Activity. It hosts the Compose UI, drives foreground/background
 * transitions through the ViewModel, requests runtime permissions, and runs the
 * KeyChain client-certificate chooser (which requires an Activity context).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AraViewModel by viewModels()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicPermissionResult(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AraNotifications.ensureChannel(this)

        setContent {
            AraTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
                KeepScreenOn(ui.keepScreenOn)
                AraScreen(
                    ui = ui,
                    settings = settings,
                    onPrimaryAction = viewModel::onPrimaryAction,
                    onToggleMic = viewModel::toggleMic,
                    onToggleSpeaker = viewModel::toggleSpeaker,
                    onAcceptCloudDisclosure = viewModel::acceptCloudDisclosure,
                    onDeclineCloudDisclosure = viewModel::declineCloudDisclosure,
                    onCapturedSend = viewModel::capturedDecisionSend,
                    onCapturedDiscard = viewModel::capturedDecisionDiscard,
                    onDismissError = viewModel::dismissError,
                    onRetry = viewModel::retry,
                    onSaveSettings = viewModel::saveSettings,
                    onSelectCertificate = ::chooseCertificate,
                    onClearCertificate = { viewModel.setCertificateAlias(null) },
                )
            }
        }

        ensureMicPermission()
        ensureNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onMicPermissionResult(true)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Ask the user to pick an installed client certificate. The alias is all the
     * app stores; the private key and chain stay in the Android KeyChain.
     */
    private fun chooseCertificate() {
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias -> runOnUiThread { viewModel.setCertificateAlias(alias) } },
            null, // any key type
            null, // any issuer
            null, // no host constraint
            -1, // no port constraint
            null, // no preselected alias
        )
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
